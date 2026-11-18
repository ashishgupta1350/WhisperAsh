// End-to-end test of the accounts gateway, word limits and admin API against `wrangler dev`,
// with a fake Groq/Gemini on 127.0.0.1:8791 (no real keys, no real quota).
// Start the Worker with the test vars first (see npm run dev:test), then: node test/gateway.test.mjs [baseUrl]
import assert from 'node:assert/strict';
import http from 'node:http';

const BASE = process.argv[2] || 'http://127.0.0.1:8789';
const ADMIN = 'admin@example.test';
const OWNER_GROQ = 'test-groq-owner-key-0000';     // matches dev:test vars
const OWNER_GEMINI = 'test-gemini-owner-key-000';
const pw = 'correct horse battery';
const run = Date.now();
let passed = 0;

// ---- fake upstream ----
const seen = [];
let sttText = 'one two three four five';
const upstream = http.createServer((req, res) => {
  let body = '';
  req.on('data', (c) => { body += c; });
  req.on('end', () => {
    seen.push({ path: req.url, auth: req.headers.authorization || req.headers['x-goog-api-key'], body });
    res.setHeader('content-type', 'application/json');
    res.setHeader('x-ratelimit-remaining-requests', '1999');
    if (req.url.endsWith('/audio/transcriptions')) return res.end(JSON.stringify({ text: sttText, segments: [{ no_speech_prob: 0.01 }] }));
    if (req.url.endsWith('/chat/completions')) return res.end(JSON.stringify({ choices: [{ message: { content: 'Clean text.' } }], usage: { total_tokens: 50 } }));
    if (req.url.endsWith('/interactions')) return res.end(JSON.stringify({ status: 'completed', output_text: 'gemini heard six words here' }));
    res.statusCode = 404; res.end('{}');
  });
});
await new Promise((r) => upstream.listen(8791, '127.0.0.1', r));

async function call(path, { method = 'POST', body, token, headers = {}, raw } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { ...(raw ? {} : { 'content-type': 'application/json' }), ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    body: raw ?? (body ? JSON.stringify(body) : undefined),
  });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch {}
  return { status: res.status, body: json, text, headers: res.headers };
}
function audioForm() {
  const f = new FormData();
  f.append('file', new Blob([new Uint8Array(2000)], { type: 'audio/mp4' }), 'audio.m4a');
  f.append('model', 'whisper-large-v3');
  return f;
}
async function stt(token, headers = {}) {
  const f = audioForm();
  const res = await fetch(BASE + '/groq/openai/v1/audio/transcriptions', { method: 'POST', headers: { authorization: `Bearer ${token}`, ...headers }, body: f });
  const text = await res.text();
  return { status: res.status, body: JSON.parse(text), text, headers: res.headers };
}
async function step(name, fn) { await fn(); passed++; console.log(`  ok  ${name}`); }
async function register(email) {
  const r = await call('/auth/register', { body: { email, password: pw, device: 'test' } });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  return r.body.token;
}

// India-time clock helpers: 10:00 IST on a Wednesday, then the next day.
const day1 = Date.UTC(2026, 10, 18, 4, 30);          // Wed 18 Nov 2026 10:00 IST
const day2 = day1 + 864e5;                           // Thu
const nextWeek = day1 + 6 * 864e5;                   // Tue 24 Nov
const at = (t) => ({ 'x-test-now': String(t) });

console.log(`Testing gateway at ${BASE}`);
const userEmail = `user-${run}@example.test`;
const tokenU = await register(userEmail);
let tokenAdmin;
const adminLogin = await call('/auth/login', { body: { email: ADMIN, password: pw } });
tokenAdmin = adminLogin.status === 200 ? adminLogin.body.token : await register(ADMIN);
let userId;

try {
  await step('/me shows the default plan: 2,000 words a day, resets at India midnight', async () => {
    const r = await call('/me', { method: 'GET', token: tokenU, headers: at(day1) });
    assert.equal(r.status, 200);
    assert.equal(r.body.plan.limit, 2000);
    assert.equal(r.body.plan.period, 'day');
    assert.equal(r.body.plan.used, 0);
    assert.equal((r.body.plan.resetsAt + 330 * 60000) % 864e5, 0);
    assert.equal(r.body.isAdmin, false);
    userId = r.body.user.id;
  });
  await step('speech goes through with the owner key; words are counted', async () => {
    const r = await stt(tokenU, at(day1));
    assert.equal(r.status, 200, r.text);
    assert.equal(r.body.text, sttText);
    assert.equal(seen.at(-1).auth, `Bearer ${OWNER_GROQ}`);
    assert.equal(r.headers.get('x-ratelimit-remaining-requests'), null, 'Groq org headers must not leak');
    assert.equal(r.headers.get('x-whisperash-words-left'), '2000');
    const me = await call('/me', { method: 'GET', token: tokenU, headers: at(day1) });
    assert.equal(me.body.plan.used, 5);
  });
  await step('no or bad session gets an OpenAI-style 401', async () => {
    const r = await call('/groq/openai/v1/chat/completions', { body: { model: 'qwen/qwen3.8-27b' } });
    assert.equal(r.status, 401);
    assert.equal(r.body.error.code, 'session_expired');
  });
  await step('CORS preflight answers for the desktop app', async () => {
    const r = await fetch(BASE + '/groq/openai/v1/chat/completions', { method: 'OPTIONS', headers: { origin: 'null', 'access-control-request-headers': 'authorization,content-type,x-stainless-os' } });
    assert.equal(r.status, 204);
    assert.equal(r.headers.get('access-control-allow-origin'), '*');
    assert.match(r.headers.get('access-control-allow-headers'), /x-stainless-os/);
  });
  await step('cleanup: allowed model passes, other models are refused', async () => {
    const ok = await call('/groq/openai/v1/chat/completions', { token: tokenU, headers: at(day1), body: { model: 'qwen/qwen3.8-27b', messages: [{ role: 'user', content: 'hi' }], max_completion_tokens: 99999 } });
    assert.equal(ok.status, 200, ok.text);
    assert.equal(ok.body.choices[0].message.content, 'Clean text.');
    assert.equal(JSON.parse(seen.at(-1).body).max_completion_tokens, 4096);
    const bad = await call('/groq/openai/v1/chat/completions', { token: tokenU, headers: at(day1), body: { model: 'llama-big', messages: [] } });
    assert.equal(bad.status, 400);
    assert.equal(bad.body.error.code, 'model_not_allowed');
  });
  await step('Gemini backup goes through with the owner Gemini key and counts words', async () => {
    const r = await call('/gemini/v1beta/interactions', { token: tokenU, headers: at(day1), body: { model: 'gemini-3.5-transcribe', input: [] } });
    assert.equal(r.status, 200, r.text);
    assert.equal(seen.at(-1).auth, OWNER_GEMINI);
    const me = await call('/me', { method: 'GET', token: tokenU, headers: at(day1) });
    assert.equal(me.body.plan.used, 10);
  });
  await step('non-admins cannot use the admin API; the admin can', async () => {
    assert.equal((await call('/admin/api/overview', { method: 'GET', token: tokenU })).status, 403);
    const r = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin, headers: at(day1) });
    assert.equal(r.status, 200, r.text);
    const u = r.body.users.find((x) => x.email === userEmail);
    assert.equal(u.words_today, 10);
    assert.equal(r.body.keys.groq.source, 'worker_secret');
    assert.ok(!r.text.includes(OWNER_GROQ) && !r.text.includes(OWNER_GEMINI), 'keys must never be returned');
  });
  await step('admin lowers the limit; the next request past it gets 429 word_limit', async () => {
    assert.equal((await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { word_limit: 12 } })).status, 200);
    assert.equal((await stt(tokenU, at(day1))).status, 200);           // 10 -> 15
    const r = await stt(tokenU, at(day1));
    assert.equal(r.status, 429);
    assert.equal(r.body.error.code, 'word_limit');
    assert.match(r.body.error.message, /midnight/);
  });
  await step('the limit resets the next India day', async () => {
    const r = await stt(tokenU, at(day2));
    assert.equal(r.status, 200, r.text);
  });
  await step('weekly limit counts the whole week and resets on Monday', async () => {
    await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { word_limit: 20, limit_period: 'week' } });
    const me = await call('/me', { method: 'GET', token: tokenU, headers: at(day2) });
    assert.equal(me.body.plan.period, 'week');
    assert.equal(me.body.plan.used, 20);                               // 15 Wed + 5 Thu
    assert.equal((await stt(tokenU, at(day2))).status, 429);
    assert.equal((await stt(tokenU, at(nextWeek))).status, 200);
  });
  await step('paused users are refused; unpaused users work again', async () => {
    await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { status: 'blocked', word_limit: null, limit_period: 'day' } });
    const r = await stt(tokenU, at(nextWeek));
    assert.equal(r.status, 403);
    assert.equal(r.body.error.code, 'account_blocked');
    await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { status: 'active' } });
    assert.equal((await stt(tokenU, at(nextWeek))).status, 200);
  });
  await step('a key the admin sets for one user is used for them, with no limit', async () => {
    const own = 'gsk_test_user_own_key_1234567890';
    assert.equal((await call(`/admin/api/users/${userId}/keys`, { method: 'PUT', token: tokenAdmin, body: { provider: 'groq', key: own } })).status, 200);
    await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { word_limit: 0 } });
    const r = await stt(tokenU, at(nextWeek));
    assert.equal(r.status, 200, r.text);
    assert.equal(seen.at(-1).auth, `Bearer ${own}`);
    const ov = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin });
    assert.ok(!ov.text.includes(own), 'user key must never be returned');
    assert.equal(ov.body.users.find((x) => x.id === userId).keys.groq, '…7890');
    const me = await call('/me', { method: 'GET', token: tokenU });
    assert.equal(me.body.plan.ownKeys.groq, true);
    await call(`/admin/api/users/${userId}/keys`, { method: 'PUT', token: tokenAdmin, body: { provider: 'groq', key: null } });
    await call(`/admin/api/users/${userId}`, { method: 'PATCH', token: tokenAdmin, body: { word_limit: null } });
  });
  await step('a default key saved in the dashboard replaces the Worker secret', async () => {
    const def = 'gsk_dashboard_default_key_abcdefgh';
    await call('/admin/api/keys', { method: 'PUT', token: tokenAdmin, body: { provider: 'groq', key: def } });
    await stt(tokenU, at(nextWeek + 864e5));
    assert.equal(seen.at(-1).auth, `Bearer ${def}`);
    const ov = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin });
    assert.equal(ov.body.keys.groq.source, 'dashboard');
    assert.ok(!ov.text.includes(def));
    await call('/admin/api/keys', { method: 'PUT', token: tokenAdmin, body: { provider: 'groq', key: null } });
    await stt(tokenU, at(nextWeek + 864e5));
    assert.equal(seen.at(-1).auth, `Bearer ${OWNER_GROQ}`);
  });
  await step('default limit for users without their own limit can be changed', async () => {
    await call('/admin/api/config', { method: 'PUT', token: tokenAdmin, body: { default_limit: 3000 } });
    const me = await call('/me', { method: 'GET', token: tokenU });
    assert.equal(me.body.plan.limit, 3000);
    await call('/admin/api/config', { method: 'PUT', token: tokenAdmin, body: { default_limit: 2000 } });
  });
  await step('password reset signs the user out and gives a temporary password', async () => {
    const r = await call(`/admin/api/users/${userId}/reset-password`, { token: tokenAdmin });
    assert.equal(r.status, 200);
    assert.equal((await call('/me', { method: 'GET', token: tokenU })).status, 401);
    const l = await call('/auth/login', { body: { email: userEmail, password: r.body.password } });
    assert.equal(l.status, 200);
  });
  await step('forgot password: same answer for any email; a real account shows up for the admin', async () => {
    const unknown = await call('/auth/forgot', { body: { email: `nobody-${run}@example.test` } });
    assert.equal(unknown.status, 200);
    const known = await call('/auth/forgot', { body: { email: userEmail.toUpperCase(), note: 'lost my phone' } });
    assert.equal(known.status, 200);
    assert.deepEqual(known.body, unknown.body);
    const ov = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin });
    const req = ov.body.resets.find((r) => r.email === userEmail);
    assert.ok(req, 'request listed');
    assert.equal(req.note, 'lost my phone');
  });
  let tempPw;
  await step('admin reset answers the request and clears it', async () => {
    const r = await call(`/admin/api/users/${userId}/reset-password`, { token: tokenAdmin });
    tempPw = r.body.password;
    const ov = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin });
    assert.ok(!ov.body.resets.some((x) => x.email === userEmail));
  });
  await step('a request can also be dismissed', async () => {
    await call('/auth/forgot', { body: { email: userEmail } });
    assert.equal((await call(`/admin/api/users/${userId}/reset-request`, { method: 'DELETE', token: tokenAdmin })).status, 200);
    const ov = await call('/admin/api/overview', { method: 'GET', token: tokenAdmin });
    assert.ok(!ov.body.resets.some((x) => x.email === userEmail));
  });
  await step('change password: needs the current one, signs out other devices', async () => {
    const a = (await call('/auth/login', { body: { email: userEmail, password: tempPw } })).body.token;
    const b = (await call('/auth/login', { body: { email: userEmail, password: tempPw } })).body.token;
    const wrong = await call('/auth/change-password', { token: a, body: { current: 'not it at all', password: 'my new password 1' } });
    assert.equal(wrong.status, 403);
    assert.equal(wrong.body.error, 'wrong_current_password');
    const short = await call('/auth/change-password', { token: a, body: { current: tempPw, password: 'short' } });
    assert.equal(short.status, 400);
    const ok = await call('/auth/change-password', { token: a, body: { current: tempPw, password: 'my new password 1' } });
    assert.equal(ok.status, 200);
    assert.equal((await call('/me', { method: 'GET', token: a })).status, 200, 'this device stays signed in');
    assert.equal((await call('/me', { method: 'GET', token: b })).status, 401, 'other devices signed out');
    assert.equal((await call('/auth/login', { body: { email: userEmail, password: 'my new password 1' } })).status, 200);
    assert.equal((await call('/auth/login', { body: { email: userEmail, password: tempPw } })).status, 401);
  });
  await step('admin page is served with a strict content security policy', async () => {
    const r = await fetch(BASE + '/admin');
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-security-policy'), /frame-ancestors 'none'/);
    assert.match(await r.text(), /WhisperAsh admin/);
  });
} finally {
  upstream.close();
}
console.log(`\nAll ${passed} gateway checks passed.`);
