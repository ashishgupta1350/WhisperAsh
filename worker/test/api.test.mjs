// End-to-end test of the sync API against `wrangler dev` (local D1).
// Usage: node test/api.test.mjs [baseUrl]   (default http://127.0.0.1:8787)
import assert from 'node:assert/strict';

const BASE = process.argv[2] || 'http://127.0.0.1:8787';
const email = `test-${Date.now()}@example.com`;
const password = 'correct horse battery';
let passed = 0;

async function call(path, { method = 'POST', body, token } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
}
async function step(name, fn) {
  await fn();
  passed++;
  console.log(`  ok  ${name}`);
}

console.log(`Testing ${BASE}`);
const signupCode = process.env.SIGNUP_CODE || undefined;
let tokenA, tokenB;

await step('health check', async () => {
  const r = await call('/', { method: 'GET' });
  assert.equal(r.status, 200);
});
await step('register rejects short password', async () => {
  const r = await call('/auth/register', { body: { email, password: 'short', signupCode } });
  assert.equal(r.status, 400);
});
if (signupCode) {
  await step('register rejects wrong signup code', async () => {
    const r = await call('/auth/register', { body: { email, password, signupCode: 'nope' } });
    assert.equal(r.status, 403);
  });
}
await step('register creates account + token', async () => {
  const r = await call('/auth/register', { body: { email, password, signupCode, device: 'PC-A' } });
  assert.equal(r.status, 201, JSON.stringify(r.body));
  assert.ok(r.body.token.length >= 40);
  tokenA = r.body.token;
});
await step('duplicate email rejected (case-insensitive)', async () => {
  const r = await call('/auth/register', { body: { email: email.toUpperCase(), password, signupCode } });
  assert.equal(r.status, 409);
});
await step('wrong password rejected', async () => {
  const r = await call('/auth/login', { body: { email, password: 'wrong password!' } });
  assert.equal(r.status, 401);
});
await step('unknown email rejected with same error', async () => {
  const r = await call('/auth/login', { body: { email: 'nobody-' + email, password } });
  assert.equal(r.status, 401);
  assert.equal(r.body.error, 'invalid_credentials');
});
await step('login on second device', async () => {
  const r = await call('/auth/login', { body: { email, password, device: 'PC-B' } });
  assert.equal(r.status, 200);
  tokenB = r.body.token;
  assert.notEqual(tokenA, tokenB);
});
await step('/me requires token', async () => {
  assert.equal((await call('/me', { method: 'GET' })).status, 401);
  assert.equal((await call('/me', { method: 'GET', token: 'x'.repeat(43) })).status, 401);
  const r = await call('/me', { method: 'GET', token: tokenA });
  assert.equal(r.body.user.email, email);
});
await step('device A pushes history, dictionary, settings', async () => {
  const r = await call('/sync/push', { token: tokenA, body: { items: [
    { kind: 'history', id: 'h1', updated_at: 1000, data: { text: 'Kal meeting hai, main aaunga.' } },
    { kind: 'dictionary', id: 'd1', updated_at: 1000, data: { term: 'Groq' } },
    { kind: 'settings', id: 'settings', updated_at: 1000, data: { outputScript: 'roman' } },
  ] } });
  assert.equal(r.status, 200);
  assert.equal(r.body.applied, 3);
});
let cursorB;
await step('device B pulls everything', async () => {
  const r = await call('/sync/pull', { token: tokenB, body: { cursor: null } });
  assert.equal(r.body.items.length, 3);
  assert.equal(r.body.items.find((i) => i.id === 'h1').data.text, 'Kal meeting hai, main aaunga.');
  cursorB = r.body.cursor;
});
await step('older write loses (last-write-wins)', async () => {
  const r = await call('/sync/push', { token: tokenB, body: { items: [
    { kind: 'settings', id: 'settings', updated_at: 500, data: { outputScript: 'devanagari' } },
  ] } });
  assert.equal(r.body.applied, 0);
});
await step('newer write and delete win, pull after cursor sees only changes', async () => {
  await call('/sync/push', { token: tokenB, body: { items: [
    { kind: 'settings', id: 'settings', updated_at: 2000, data: { outputScript: 'english' } },
    { kind: 'dictionary', id: 'd1', updated_at: 2000, deleted: true, data: {} },
  ] } });
  const r = await call('/sync/pull', { token: tokenA, body: { cursor: cursorB } });
  assert.equal(r.body.items.length, 2);
  assert.equal(r.body.items.find((i) => i.kind === 'settings').data.outputScript, 'english');
  assert.equal(r.body.items.find((i) => i.id === 'd1').deleted, true);
});
await step('pull pages through more than 500 items', async () => {
  for (let b = 0; b < 3; b++) {
    const items = Array.from({ length: 200 }, (_, i) => ({ kind: 'history', id: `bulk-${b}-${i}`, updated_at: 3000, data: { text: 'x' } }));
    const r = await call('/sync/push', { token: tokenA, body: { items } });
    assert.equal(r.status, 200);
  }
  let cursor = null, total = 0, pages = 0, more = true;
  while (more) {
    const r = await call('/sync/pull', { token: tokenB, body: { cursor } });
    total += r.body.items.length; cursor = r.body.cursor; more = r.body.more; pages++;
  }
  assert.equal(total, 603);
  assert.equal(pages, 2);
});
await step('notes push and pull (Android Notes tab)', async () => {
  const note = { title: 'Groceries', body: '- milk\n- eggs', raw: 'milk and eggs', pinned: false, created_ms: 5000 };
  const p = await call('/sync/push', { token: tokenA, body: { items: [{ kind: 'notes', id: 'note-1', updated_at: 5000, data: note }] } });
  assert.equal(p.status, 200);
  let cursor = null, got;
  for (;;) {
    const r = await call('/sync/pull', { token: tokenB, body: { cursor } });
    got = got || r.body.items.find((i) => i.kind === 'notes' && i.id === 'note-1');
    cursor = r.body.cursor;
    if (!r.body.more) break;
  }
  assert.equal(got.data.title, 'Groceries');
  assert.equal(got.data.body, '- milk\n- eggs');
});
await step('diaries sync independently of meetings and accept an edit from the other client', async () => {
  const id = 'diary-1';
  const diary = { title: '21 Nov 2026 · A busy day', summary: 'I met a friend.',
    transcript: 'A diary entry. '.repeat(6000), engine: 'gemini', createdMs: 6000, status: 'done' };
  const push = (token, item) => call('/sync/push', { token, body: { items: [item] } });
  const find = async (token, kind) => {
    let cursor = null;
    for (;;) {
      const page = await call('/sync/pull', { token, body: { cursor } });
      assert.equal(page.status, 200);
      const row = page.body.items.find((item) => item.kind === kind && item.id === id);
      if (row || !page.body.more) return row;
      cursor = page.body.cursor;
    }
  };
  assert.equal((await push(tokenA, { kind: 'diaries', id, updated_at: 6000, data: diary })).status, 200);
  assert.deepEqual((await find(tokenB, 'diaries')).data, diary);
  assert.equal(await find(tokenB, 'meetings'), undefined);
  const edited = { ...diary, title: '21 Nov 2026 · Meeting a friend' };
  assert.equal((await push(tokenB, { kind: 'diaries', id, updated_at: 6001, data: edited })).status, 200);
  assert.equal((await find(tokenA, 'diaries')).data.title, edited.title);
  assert.equal((await push(tokenA, { kind: 'diaries', id, updated_at: 6002, deleted: true })).status, 200);
  assert.equal((await find(tokenB, 'diaries')).deleted, true);
});
await step('other users cannot see this data', async () => {
  const r0 = await call('/auth/register', { body: { email: 'other-' + email, password, signupCode } });
  const r = await call('/sync/pull', { token: r0.body.token, body: { cursor: null } });
  assert.equal(r.body.items.length, 0);
});
await step('invalid items rejected', async () => {
  const r = await call('/sync/push', { token: tokenA, body: { items: [{ kind: 'evil', id: 'x', updated_at: 1 }] } });
  assert.equal(r.status, 400);
});
await step('logout revokes only that token', async () => {
  assert.equal((await call('/auth/logout', { token: tokenB })).status, 200);
  assert.equal((await call('/me', { method: 'GET', token: tokenB })).status, 401);
  assert.equal((await call('/me', { method: 'GET', token: tokenA })).status, 200);
});
await step('lockout after repeated wrong passwords', async () => {
  let last;
  for (let i = 0; i < 9; i++) last = await call('/auth/login', { body: { email, password: 'bad password ' + i } });
  assert.equal(last.status, 429);
});

console.log(`\nAll ${passed} checks passed.`);
