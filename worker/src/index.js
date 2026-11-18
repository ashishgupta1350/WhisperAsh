// WhisperAsh sync API — Cloudflare Worker + D1.
// Auth: email + password (PBKDF2-SHA256 with per-user salt and a server-side pepper),
// opaque session tokens (only their SHA-256 is stored). Sync: last-write-wins per record.
// Gateway: signed-in users dictate through the owner's Groq/Gemini keys (/groq/*, /gemini/*)
// with a per-user word limit; the owner manages users, limits and keys at /admin.
// No key or admin email lives in this file: they are Worker secrets (see wrangler.toml).

import ADMIN_HTML from './admin.html';

const PBKDF2_ITERATIONS = 100000;           // Workers' WebCrypto maximum for PBKDF2
const SESSION_DAYS = 90;
const MAX_FAILED = 8;                       // failed logins before a lockout
const LOCK_MS = 15 * 60 * 1000;
const PULL_LIMIT = 500;
const PUSH_LIMIT = 200;
const MAX_ITEM_BYTES = 64 * 1024;
const KINDS = new Set(['history', 'dictionary', 'settings', 'notes']);

const enc = new TextEncoder();

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (err) {
      if (err instanceof HttpError) return json({ error: err.message }, err.status);
      console.error(err);
      return json({ error: 'server_error' }, 500);
    }
  },
};

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  });
}

async function readJson(request) {
  const len = Number(request.headers.get('content-length') || 0);
  if (len > 2 * 1024 * 1024) throw new HttpError(413, 'body_too_large');
  try { return await request.json(); } catch { throw new HttpError(400, 'invalid_json'); }
}

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, '') || '/';
  const m = request.method;

  if (m === 'GET' && path === '/') return json({ ok: true, service: 'whisperash-sync' });
  if (m === 'GET' && path === '/admin') return adminPage();
  const isGateway = path.startsWith('/groq/') || path.startsWith('/gemini/');
  if (m === 'OPTIONS' && isGateway) return corsPreflight(request);
  if (!env.PEPPER || env.PEPPER.length < 16) throw new HttpError(500, 'server_not_configured');
  await ensureSchema(env);

  if (m === 'POST' && path === '/auth/register') return register(request, env);
  if (m === 'POST' && path === '/auth/login') return login(request, env);
  if (m === 'POST' && path === '/auth/forgot') return forgotPassword(request, env);

  if (isGateway) return gateway(request, env, path);

  const session = await authenticate(request, env);
  if (m === 'POST' && path === '/auth/change-password') return changePassword(request, env, session);
  if (m === 'POST' && path === '/auth/logout') {
    await env.DB.prepare('DELETE FROM sessions WHERE token_hash = ?').bind(session.tokenHash).run();
    return json({ ok: true });
  }
  if (m === 'GET' && path === '/me') {
    const u = await env.DB.prepare(
      'SELECT id, email, created_at, status, word_limit, limit_period FROM users WHERE id = ?'
    ).bind(session.userId).first();
    const now = clock(request, env);
    return json({
      user: { id: u.id, email: u.email, created_at: u.created_at },
      plan: await planFor(env, u, now),
      isAdmin: isAdminEmail(env, u.email),
    });
  }
  if (m === 'POST' && path === '/sync/push') return push(request, env, session);
  if (m === 'POST' && path === '/sync/pull') return pull(request, env, session);
  if (path.startsWith('/admin/api/')) return adminApi(request, env, session, path);
  throw new HttpError(404, 'not_found');
}

// ---------- crypto helpers ----------
function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function fromB64url(str) {
  const s = atob(str.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(s, (c) => c.charCodeAt(0));
}
async function sha256(text) {
  return b64url(new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(text))));
}
async function pepper(password, env) {
  const key = await crypto.subtle.importKey('raw', enc.encode(env.PEPPER), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(password)));
}
async function pbkdf2(passwordBytes, salt, iterations) {
  const key = await crypto.subtle.importKey('raw', passwordBytes, 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, key, 256);
  return new Uint8Array(bits);
}
async function hashPassword(password, env) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await pbkdf2(await pepper(password, env), salt, PBKDF2_ITERATIONS);
  return `pbkdf2-sha256$${PBKDF2_ITERATIONS}$${b64url(salt)}$${b64url(hash)}`;
}
async function verifyPassword(password, stored, env) {
  const [algo, iter, saltB64, hashB64] = stored.split('$');
  if (algo !== 'pbkdf2-sha256') return false;
  const hash = await pbkdf2(await pepper(password, env), fromB64url(saltB64), Number(iter));
  return timingSafeEqual(hash, fromB64url(hashB64));
}
function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}
// A fixed dummy hash so unknown emails take as long as wrong passwords.
let dummyHash;
async function getDummyHash(env) {
  if (!dummyHash) dummyHash = await hashPassword('dummy-password-for-timing', env);
  return dummyHash;
}

// ---------- auth ----------
function cleanEmail(email) {
  if (typeof email !== 'string') throw new HttpError(400, 'invalid_email');
  const e = email.trim().toLowerCase();
  if (e.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)) throw new HttpError(400, 'invalid_email');
  return e;
}
function checkPassword(pw) {
  if (typeof pw !== 'string' || pw.length < 8) throw new HttpError(400, 'password_too_short');
  if (pw.length > 256) throw new HttpError(400, 'password_too_long');
}

async function createSession(env, userId, device) {
  const token = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const now = Date.now();
  await env.DB.prepare(
    'INSERT INTO sessions (token_hash, user_id, device, created_at, last_used, expires_at) VALUES (?, ?, ?, ?, ?, ?)'
  ).bind(await sha256(token), userId, String(device || '').slice(0, 100), now, now, now + SESSION_DAYS * 864e5).run();
  return token;
}

async function register(request, env) {
  const body = await readJson(request);
  if (env.SIGNUP_CODE && body.signupCode !== env.SIGNUP_CODE) throw new HttpError(403, 'invalid_signup_code');
  const email = cleanEmail(body.email);
  checkPassword(body.password);
  const exists = await env.DB.prepare('SELECT 1 FROM users WHERE email = ?').bind(email).first();
  if (exists) throw new HttpError(409, 'email_taken');
  // Every account gets free words on the owner's keys, so cap sign-ups per IP per day.
  const ip = request.headers.get('cf-connecting-ip') || 'unknown';
  const day = istDay(Date.now());
  const perIp = Number(env.SIGNUPS_PER_IP || 5);
  const seen = await env.DB.prepare('SELECT count FROM signup_ips WHERE ip = ? AND day = ?').bind(ip, day).first();
  if (seen && seen.count >= perIp) throw new HttpError(429, 'too_many_signups');
  await env.DB.prepare(
    `INSERT INTO signup_ips (ip, day, count) VALUES (?, ?, 1)
     ON CONFLICT(ip, day) DO UPDATE SET count = count + 1`
  ).bind(ip, day).run();
  const id = crypto.randomUUID();
  await env.DB.prepare('INSERT INTO users (id, email, pass_hash, created_at) VALUES (?, ?, ?, ?)')
    .bind(id, email, await hashPassword(body.password, env), Date.now()).run();
  const token = await createSession(env, id, body.device);
  return json({ token, user: { id, email } }, 201);
}

async function login(request, env) {
  const body = await readJson(request);
  const email = cleanEmail(body.email);
  if (typeof body.password !== 'string') throw new HttpError(400, 'invalid_credentials');
  const user = await env.DB.prepare('SELECT id, email, pass_hash, failed_logins, locked_until FROM users WHERE email = ?').bind(email).first();
  const now = Date.now();
  if (!user) {
    await verifyPassword(body.password, await getDummyHash(env), env);
    throw new HttpError(401, 'invalid_credentials');
  }
  if (user.locked_until > now) throw new HttpError(429, 'too_many_attempts');
  const ok = await verifyPassword(body.password, user.pass_hash, env);
  if (!ok) {
    const failed = user.failed_logins + 1;
    const lock = failed >= MAX_FAILED ? now + LOCK_MS : 0;
    await env.DB.prepare('UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?')
      .bind(lock ? 0 : failed, lock, user.id).run();
    throw new HttpError(401, 'invalid_credentials');
  }
  await env.DB.prepare('UPDATE users SET failed_logins = 0, locked_until = 0 WHERE id = ?').bind(user.id).run();
  // Housekeeping: drop this user's expired sessions.
  await env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND expires_at < ?').bind(user.id, now).run();
  const token = await createSession(env, user.id, body.device);
  return json({ token, user: { id: user.id, email: user.email } });
}

// "Forgot password": records a request the admin sees in /admin. The answer never says whether the
// email has an account, so it can't be used to find out who uses WhisperAsh.
async function forgotPassword(request, env) {
  const body = await readJson(request);
  const email = cleanEmail(body.email);
  const note = typeof body.note === 'string' ? body.note.trim().slice(0, 200) : '';
  const ipKey = 'forgot:' + (request.headers.get('cf-connecting-ip') || 'unknown');
  const day = istDay(Date.now());
  const seen = await env.DB.prepare('SELECT count FROM signup_ips WHERE ip = ? AND day = ?').bind(ipKey, day).first();
  if (seen && seen.count >= 10) throw new HttpError(429, 'too_many_requests');
  await env.DB.prepare(
    `INSERT INTO signup_ips (ip, day, count) VALUES (?, ?, 1) ON CONFLICT(ip, day) DO UPDATE SET count = count + 1`
  ).bind(ipKey, day).run();
  const user = await env.DB.prepare('SELECT id FROM users WHERE email = ?').bind(email).first();
  if (user) {
    await env.DB.prepare(
      `INSERT INTO reset_requests (user_id, requested_at, note) VALUES (?, ?, ?)
       ON CONFLICT(user_id) DO UPDATE SET requested_at = excluded.requested_at, note = excluded.note`
    ).bind(user.id, Date.now(), note || null).run();
  }
  return json({ ok: true });
}

// Signed in: replace the password (after an admin reset, or any time). Other devices are signed out.
async function changePassword(request, env, session) {
  const body = await readJson(request);
  if (typeof body.current !== 'string') throw new HttpError(400, 'invalid_credentials');
  checkPassword(body.password);
  const user = await env.DB.prepare('SELECT id, pass_hash FROM users WHERE id = ?').bind(session.userId).first();
  if (!user || !(await verifyPassword(body.current, user.pass_hash, env))) throw new HttpError(403, 'wrong_current_password');
  await env.DB.batch([
    env.DB.prepare('UPDATE users SET pass_hash = ?, failed_logins = 0, locked_until = 0 WHERE id = ?').bind(await hashPassword(body.password, env), user.id),
    env.DB.prepare('DELETE FROM sessions WHERE user_id = ? AND token_hash != ?').bind(user.id, session.tokenHash),
    env.DB.prepare('DELETE FROM reset_requests WHERE user_id = ?').bind(user.id),
  ]);
  return json({ ok: true });
}

async function authenticate(request, env) {
  const h = request.headers.get('authorization') || '';
  const m = /^Bearer ([A-Za-z0-9_-]{20,100})$/.exec(h);
  if (!m) throw new HttpError(401, 'unauthorized');
  const tokenHash = await sha256(m[1]);
  const now = Date.now();
  const s = await env.DB.prepare('SELECT user_id, expires_at, last_used FROM sessions WHERE token_hash = ?').bind(tokenHash).first();
  if (!s || s.expires_at < now) throw new HttpError(401, 'unauthorized');
  if (now - s.last_used > 864e5) {   // slide expiry at most once a day
    await env.DB.prepare('UPDATE sessions SET last_used = ?, expires_at = ? WHERE token_hash = ?')
      .bind(now, now + SESSION_DAYS * 864e5, tokenHash).run();
  }
  return { userId: s.user_id, tokenHash };
}

// ---------- sync ----------
async function push(request, env, session) {
  const body = await readJson(request);
  const items = Array.isArray(body.items) ? body.items : null;
  if (!items) throw new HttpError(400, 'items_required');
  if (items.length > PUSH_LIMIT) throw new HttpError(400, 'too_many_items');
  const now = Date.now();
  const stmts = [];
  for (const it of items) {
    if (!it || !KINDS.has(it.kind) || typeof it.id !== 'string' || !it.id || it.id.length > 100) throw new HttpError(400, 'invalid_item');
    if (!Number.isFinite(it.updated_at)) throw new HttpError(400, 'invalid_item');
    const data = JSON.stringify(it.data ?? {});
    if (data.length > MAX_ITEM_BYTES) throw new HttpError(400, 'item_too_large');
    // Clamp client clocks that run far ahead so one bad clock cannot win forever.
    const updatedAt = Math.min(Math.trunc(it.updated_at), now + 5 * 60 * 1000);
    stmts.push(env.DB.prepare(
      `INSERT INTO items (user_id, kind, id, data, updated_at, deleted, server_ts) VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(user_id, kind, id) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at,
         deleted = excluded.deleted, server_ts = excluded.server_ts
       WHERE excluded.updated_at > items.updated_at`
    ).bind(session.userId, it.kind, it.id, data, updatedAt, it.deleted ? 1 : 0, now));
  }
  let applied = 0;
  if (stmts.length) {
    const res = await env.DB.batch(stmts);
    applied = res.reduce((n, r) => n + (r.meta?.changes || 0), 0);
  }
  return json({ ok: true, received: items.length, applied, server_ts: now });
}

async function pull(request, env, session) {
  const body = await readJson(request);
  const c = body.cursor || {};
  const ts = Number.isFinite(c.ts) ? c.ts : 0;
  const kind = typeof c.kind === 'string' ? c.kind : '';
  const id = typeof c.id === 'string' ? c.id : '';
  const { results } = await env.DB.prepare(
    `SELECT kind, id, data, updated_at, deleted, server_ts FROM items
     WHERE user_id = ? AND (server_ts, kind, id) > (?, ?, ?)
     ORDER BY server_ts, kind, id LIMIT ?`
  ).bind(session.userId, ts, kind, id, PULL_LIMIT + 1).all();
  const more = results.length > PULL_LIMIT;
  const rows = more ? results.slice(0, PULL_LIMIT) : results;
  const last = rows[rows.length - 1];
  return json({
    items: rows.map((r) => ({ kind: r.kind, id: r.id, data: JSON.parse(r.data), updated_at: r.updated_at, deleted: !!r.deleted })),
    cursor: last ? { ts: last.server_ts, kind: last.kind, id: last.id } : { ts, kind, id },
    more,
  });
}

// ---------- schema upgrades (additive; existing rows are never touched) ----------
let schemaReady = false;
async function ensureSchema(env) {
  if (schemaReady) return;
  await env.DB.batch([
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS usage (
      user_id TEXT NOT NULL, day TEXT NOT NULL, words INTEGER NOT NULL DEFAULT 0,
      stt_requests INTEGER NOT NULL DEFAULT 0, chat_requests INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (user_id, day))`),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS user_keys (
      user_id TEXT NOT NULL, provider TEXT NOT NULL, enc TEXT NOT NULL, hint TEXT,
      updated_at INTEGER NOT NULL, PRIMARY KEY (user_id, provider))`),
    env.DB.prepare('CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL)'),
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS signup_ips (
      ip TEXT NOT NULL, day TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (ip, day))`),
    // "Forgot password" requests waiting for the admin (no email service: the admin resets and shares).
    env.DB.prepare(`CREATE TABLE IF NOT EXISTS reset_requests (
      user_id TEXT PRIMARY KEY, requested_at INTEGER NOT NULL, note TEXT)`),
  ]);
  const { results } = await env.DB.prepare('PRAGMA table_info(users)').all();
  const have = new Set(results.map((c) => c.name));
  const add = [
    ['word_limit', 'INTEGER'],                       // NULL = the default limit
    ['limit_period', 'TEXT'],                        // NULL/'day' or 'week'
    ['status', "TEXT NOT NULL DEFAULT 'active'"],    // 'active' | 'blocked'
    ['last_active', 'INTEGER'],
  ].filter(([name]) => !have.has(name));
  for (const [name, type] of add) {
    await env.DB.prepare(`ALTER TABLE users ADD COLUMN ${name} ${type}`).run();
  }
  schemaReady = true;
}

// ---------- time: limits reset at midnight India time (UTC+5:30) ----------
const IST_MS = 330 * 60 * 1000;
function clock(request, env) {
  const t = Number(request.headers.get('x-test-now'));
  return env.ALLOW_TEST_CLOCK === '1' && Number.isFinite(t) && t > 0 ? t : Date.now();
}
function istDay(ms) { return new Date(ms + IST_MS).toISOString().slice(0, 10); }
function periodRange(period, ms) {
  const d = new Date(ms + IST_MS);
  const midnight = Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  if (period === 'week') {
    const sinceMonday = (d.getUTCDay() + 6) % 7;
    const start = midnight - sinceMonday * 864e5;
    return { from: istDay(start - IST_MS), to: istDay(ms), resetsAt: start + 7 * 864e5 - IST_MS };
  }
  return { from: istDay(ms), to: istDay(ms), resetsAt: midnight + 864e5 - IST_MS };
}

// ---------- config, limits, usage ----------
const DEFAULT_WORD_LIMIT = 2000;
const DEFAULT_CHAT_CAP = 300;     // cleanup/notes calls a day; stops use as a free chatbot
async function getConfig(env) {
  const { results } = await env.DB.prepare('SELECT key, value FROM config').all();
  return Object.fromEntries(results.map((r) => [r.key, r.value]));
}
function intOr(v, d) { const n = Number(v); return v !== null && v !== '' && Number.isFinite(n) && n >= 0 ? Math.trunc(n) : d; }

async function planFor(env, user, now, config) {
  const cfg = config || await getConfig(env);
  const period = user.limit_period === 'week' ? 'week' : 'day';
  const limit = user.word_limit ?? intOr(cfg.default_limit, DEFAULT_WORD_LIMIT);
  const r = periodRange(period, now);
  const u = await env.DB.prepare(
    'SELECT COALESCE(SUM(words), 0) AS words FROM usage WHERE user_id = ? AND day >= ? AND day <= ?'
  ).bind(user.id, r.from, r.to).first();
  const keys = await env.DB.prepare('SELECT provider FROM user_keys WHERE user_id = ?').bind(user.id).all();
  const own = new Set(keys.results.map((k) => k.provider));
  return {
    limit, period, used: u.words, left: Math.max(0, limit - u.words), resetsAt: r.resetsAt,
    status: user.status || 'active', ownKeys: { groq: own.has('groq'), gemini: own.has('gemini') },
  };
}

async function recordUsage(env, userId, now, { words = 0, stt = 0, chat = 0 }) {
  await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO usage (user_id, day, words, stt_requests, chat_requests) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(user_id, day) DO UPDATE SET words = words + excluded.words,
         stt_requests = stt_requests + excluded.stt_requests, chat_requests = chat_requests + excluded.chat_requests`
    ).bind(userId, istDay(now), words, stt, chat),
    env.DB.prepare('UPDATE users SET last_active = ? WHERE id = ?').bind(Date.now(), userId),
  ]);
}
function countWords(text) { return String(text || '').split(/\s+/).filter(Boolean).length; }

// ---------- keys: dashboard-set keys are stored AES-GCM encrypted with KEY_SECRET ----------
async function aesKey(env) {
  if (!env.KEY_SECRET || env.KEY_SECRET.length < 16) throw new HttpError(500, 'key_secret_missing');
  const raw = await crypto.subtle.digest('SHA-256', enc.encode('whisperash-keys:' + env.KEY_SECRET));
  return crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);
}
async function sealKey(env, plain) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aesKey(env), enc.encode(plain)));
  return `${b64url(iv)}.${b64url(ct)}`;
}
async function openKey(env, sealed) {
  const [iv, ct] = sealed.split('.');
  const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: fromB64url(iv) }, await aesKey(env), fromB64url(ct));
  return new TextDecoder().decode(pt);
}
function keyHint(k) { return k ? `…${k.slice(-4)}` : null; }
const PROVIDERS = new Set(['groq', 'gemini']);
const SECRET_FOR = { groq: 'GROQ_API_KEY', gemini: 'GEMINI_API_KEY' };

// Which key serves this user: their own (set by the admin) > dashboard default > Worker secret.
async function keyFor(env, userId, provider, cfg) {
  const own = await env.DB.prepare('SELECT enc FROM user_keys WHERE user_id = ? AND provider = ?').bind(userId, provider).first();
  if (own) return { key: await openKey(env, own.enc), own: true };
  if (cfg[`${provider}_key`]) return { key: await openKey(env, cfg[`${provider}_key`]), own: false };
  return { key: env[SECRET_FOR[provider]] || null, own: false };
}

// ---------- gateway: same paths and error shape as Groq / Gemini ----------
const GROQ_MODELS_CHAT = new Set(['qwen/qwen3.8-27b', 'openai/gpt-oss-120b', 'openai/gpt-oss-20b']);
const GEMINI_MODELS = new Set(['gemini-3.5-transcribe']);
const MAX_AUDIO_BYTES = 25 * 1024 * 1024;
const MAX_CHAT_TOKENS = 4096;

function cors(request) {
  return {
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'POST, OPTIONS',
    'access-control-allow-headers': request.headers.get('access-control-request-headers') || 'authorization, content-type',
    'access-control-expose-headers': 'x-whisperash-words-left, x-whisperash-limit, x-whisperash-resets-at',
    'access-control-max-age': '86400',
  };
}
function corsPreflight(request) { return new Response(null, { status: 204, headers: cors(request) }); }
function oaiError(request, status, code, message, extra = {}) {
  return new Response(JSON.stringify({ error: { message, code, type: code, ...extra } }), {
    status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...cors(request) },
  });
}

async function gateway(request, env, path) {
  const routes = {
    '/groq/openai/v1/audio/transcriptions': 'stt',
    '/groq/openai/v1/chat/completions': 'chat',
    '/gemini/v1beta/interactions': 'gemini',
  };
  const kind = routes[path];
  if (request.method !== 'POST' || !kind) return oaiError(request, 404, 'not_found', 'Unknown WhisperAsh endpoint.');
  let session;
  try { session = await authenticate(request, env); } catch {
    return oaiError(request, 401, 'session_expired', 'Please sign in to WhisperAsh again.');
  }
  const now = clock(request, env);
  const user = await env.DB.prepare('SELECT id, email, status, word_limit, limit_period FROM users WHERE id = ?').bind(session.userId).first();
  if (!user) return oaiError(request, 401, 'session_expired', 'Please sign in to WhisperAsh again.');
  if (user.status === 'blocked') return oaiError(request, 403, 'account_blocked', 'This WhisperAsh account is paused. Contact the admin.');

  const cfg = await getConfig(env);
  const provider = kind === 'gemini' ? 'gemini' : 'groq';
  const { key, own } = await keyFor(env, user.id, provider, cfg);
  if (!key) return oaiError(request, 503, 'no_key_configured', `WhisperAsh has no ${provider} key set up yet. Contact the admin.`);

  const plan = await planFor(env, user, now, cfg);
  const limitHeaders = {
    'x-whisperash-words-left': String(plan.left), 'x-whisperash-limit': String(plan.limit),
    'x-whisperash-resets-at': String(plan.resetsAt),
  };
  if (!own && plan.used >= plan.limit) {
    const when = plan.period === 'week' ? 'next Monday' : 'at midnight (India time)';
    return oaiError(request, 429, 'word_limit',
      `You've used your ${plan.limit.toLocaleString('en-IN')} words for ${plan.period === 'week' ? 'this week' : 'today'}. It resets ${when}.`,
      { limit: plan.limit, used: plan.used, resetsAt: plan.resetsAt });
  }

  if (kind === 'chat') return gatewayChat(request, env, user, key, own, cfg, now, limitHeaders);
  if (kind === 'stt') return gatewayStt(request, env, user, key, now, limitHeaders);
  return gatewayGemini(request, env, user, key, now, limitHeaders);
}

function upstreamFailure(request, status, bodyText, provider) {
  if (status === 401 || status === 403) {
    return oaiError(request, 502, 'upstream_key', `The WhisperAsh ${provider} key was refused. Tell the admin.`);
  }
  if (status === 429) {
    return oaiError(request, 429, 'upstream_busy', 'WhisperAsh is busy right now (shared limit reached). Try again in a minute.');
  }
  return new Response(bodyText, { status, headers: { 'content-type': 'application/json', ...cors(request) } });
}

async function gatewayStt(request, env, user, key, now, limitHeaders) {
  if (Number(request.headers.get('content-length') || 0) > MAX_AUDIO_BYTES) {
    return oaiError(request, 413, 'audio_too_large', 'Recording is too long.');
  }
  const base = env.GROQ_UPSTREAM || 'https://api.groq.com';
  // The multipart body is streamed through untouched, so a long recording costs no Worker CPU.
  const res = await fetch(`${base}/openai/v1/audio/transcriptions`, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': request.headers.get('content-type') || '' },
    body: request.body,
  });
  const text = await res.text();
  if (!res.ok) return upstreamFailure(request, res.status, text, 'Groq');
  let words = 0;
  try { words = countWords(JSON.parse(text).text); } catch { /* non-JSON response format: count nothing */ }
  await recordUsage(env, user.id, now, { words, stt: 1 });
  return new Response(text, { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...limitHeaders, ...cors(request) } });
}

async function gatewayChat(request, env, user, key, own, cfg, now, limitHeaders) {
  const body = await readJson(request).catch(() => null);
  if (!body || !GROQ_MODELS_CHAT.has(body.model)) {
    return oaiError(request, 400, 'model_not_allowed', 'This model is not available through WhisperAsh.');
  }
  if (!own) {
    const cap = intOr(cfg.chat_daily_cap, DEFAULT_CHAT_CAP);
    const t = await env.DB.prepare('SELECT chat_requests FROM usage WHERE user_id = ? AND day = ?').bind(user.id, istDay(now)).first();
    if (t && t.chat_requests >= cap) return oaiError(request, 429, 'chat_limit', 'Daily cleanup limit reached. It resets at midnight (India time).');
  }
  for (const f of ['max_tokens', 'max_completion_tokens']) {
    if (body[f] != null) body[f] = Math.min(intOr(body[f], MAX_CHAT_TOKENS), MAX_CHAT_TOKENS);
  }
  if (body.max_tokens == null && body.max_completion_tokens == null) body.max_completion_tokens = MAX_CHAT_TOKENS;
  const base = env.GROQ_UPSTREAM || 'https://api.groq.com';
  const res = await fetch(`${base}/openai/v1/chat/completions`, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) return upstreamFailure(request, res.status, await res.text(), 'Groq');
  await recordUsage(env, user.id, now, { chat: 1 });
  // Streamed answers pass straight through.
  return new Response(res.body, {
    status: 200,
    headers: { 'content-type': res.headers.get('content-type') || 'application/json', 'cache-control': 'no-store', ...limitHeaders, ...cors(request) },
  });
}

async function gatewayGemini(request, env, user, key, now, limitHeaders) {
  if (Number(request.headers.get('content-length') || 0) > MAX_AUDIO_BYTES * 1.4) {
    return oaiError(request, 413, 'audio_too_large', 'Recording is too long.');
  }
  let body;
  try { body = await request.json(); } catch { return oaiError(request, 400, 'invalid_json', 'Invalid request.'); }
  if (!GEMINI_MODELS.has(body.model)) return oaiError(request, 400, 'model_not_allowed', 'This model is not available through WhisperAsh.');
  const base = env.GEMINI_UPSTREAM || 'https://generativelanguage.googleapis.com';
  const res = await fetch(`${base}/v1beta/interactions`, {
    method: 'POST',
    headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) return upstreamFailure(request, res.status, text, 'Gemini');
  let words = 0;
  try {
    const j = JSON.parse(text);
    const out = j.output_text || (j.steps || []).flatMap((s) => s.content || []).filter((p) => p.type === 'text').map((p) => p.text).join(' ');
    words = countWords(out);
  } catch { /* count nothing */ }
  await recordUsage(env, user.id, now, { words, stt: 1 });
  return new Response(text, { status: 200, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...limitHeaders, ...cors(request) } });
}

// ---------- admin ----------
function isAdminEmail(env, email) {
  const list = String(env.ADMIN_EMAILS || '').split(',').map((e) => e.trim().toLowerCase()).filter(Boolean);
  return !!email && list.includes(String(email).toLowerCase());
}

function adminPage() {
  return new Response(ADMIN_HTML, {
    headers: {
      'content-type': 'text/html; charset=utf-8',
      'cache-control': 'no-store',
      'content-security-policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      'x-content-type-options': 'nosniff',
      'referrer-policy': 'no-referrer',
    },
  });
}

const UPSERT_CONFIG = 'INSERT INTO config (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value';

async function adminApi(request, env, session, path) {
  const me = await env.DB.prepare('SELECT email FROM users WHERE id = ?').bind(session.userId).first();
  if (!me || !isAdminEmail(env, me.email)) throw new HttpError(403, 'not_admin');
  const m = request.method;
  const now = clock(request, env);

  if (m === 'GET' && path === '/admin/api/overview') {
    const cfg = await getConfig(env);
    const today = istDay(now);
    const week = periodRange('week', now).from;
    const { results } = await env.DB.prepare(
      `SELECT u.id, u.email, u.created_at, u.last_active, u.status, u.word_limit, u.limit_period,
         (SELECT COALESCE(SUM(words), 0) FROM usage WHERE user_id = u.id AND day = ?) AS words_today,
         (SELECT COALESCE(SUM(words), 0) FROM usage WHERE user_id = u.id AND day >= ?) AS words_week,
         (SELECT GROUP_CONCAT(provider || ':' || COALESCE(hint, '')) FROM user_keys WHERE user_id = u.id) AS keys
       FROM users u ORDER BY COALESCE(u.last_active, u.created_at) DESC`
    ).bind(today, week).all();
    const keys = {};
    for (const p of PROVIDERS) {
      if (cfg[`${p}_key`]) keys[p] = { source: 'dashboard', hint: cfg[`${p}_hint`] || null };
      else keys[p] = { source: env[SECRET_FOR[p]] ? 'worker_secret' : 'none', hint: keyHint(env[SECRET_FOR[p]]) };
    }
    return json({
      users: results.map((u) => ({
        ...u,
        keys: Object.fromEntries((u.keys || '').split(',').filter(Boolean).map((s) => s.split(':'))),
        is_admin: isAdminEmail(env, u.email),
      })),
      config: { default_limit: intOr(cfg.default_limit, DEFAULT_WORD_LIMIT), chat_daily_cap: intOr(cfg.chat_daily_cap, DEFAULT_CHAT_CAP) },
      keys,
      today,
      resets: (await env.DB.prepare(
        `SELECT r.user_id, u.email, r.requested_at, r.note FROM reset_requests r JOIN users u ON u.id = r.user_id
         ORDER BY r.requested_at DESC`
      ).all()).results,
      canStoreKeys: !!(env.KEY_SECRET && env.KEY_SECRET.length >= 16),
    });
  }

  if (m === 'PUT' && path === '/admin/api/config') {
    const body = await readJson(request);
    const stmts = [];
    for (const k of ['default_limit', 'chat_daily_cap']) {
      if (body[k] === undefined) continue;
      const n = Number(body[k]);
      if (!Number.isInteger(n) || n < 0 || n > 10_000_000) throw new HttpError(400, `invalid_${k}`);
      stmts.push(env.DB.prepare(UPSERT_CONFIG).bind(k, String(n)));
    }
    if (stmts.length) await env.DB.batch(stmts);
    return json({ ok: true });
  }

  if (m === 'PUT' && path === '/admin/api/keys') {
    const body = await readJson(request);
    if (!PROVIDERS.has(body.provider)) throw new HttpError(400, 'invalid_provider');
    const p = body.provider;
    if (body.key == null || body.key === '') {
      await env.DB.batch([
        env.DB.prepare('DELETE FROM config WHERE key = ?').bind(`${p}_key`),
        env.DB.prepare('DELETE FROM config WHERE key = ?').bind(`${p}_hint`),
      ]);
    } else {
      const k = cleanKey(body.key);
      await env.DB.batch([
        env.DB.prepare(UPSERT_CONFIG).bind(`${p}_key`, await sealKey(env, k)),
        env.DB.prepare(UPSERT_CONFIG).bind(`${p}_hint`, keyHint(k)),
      ]);
    }
    return json({ ok: true });
  }

  const um = /^\/admin\/api\/users\/([0-9a-f-]{36})(\/keys|\/reset-password|\/reset-request)?$/.exec(path);
  if (um) {
    const target = await env.DB.prepare('SELECT id, email FROM users WHERE id = ?').bind(um[1]).first();
    if (!target) throw new HttpError(404, 'user_not_found');
    if (m === 'PATCH' && !um[2]) {
      const body = await readJson(request);
      const sets = [], vals = [];
      if (body.word_limit !== undefined) {
        const n = body.word_limit === null ? null : Number(body.word_limit);
        if (n !== null && (!Number.isInteger(n) || n < 0 || n > 10_000_000)) throw new HttpError(400, 'invalid_word_limit');
        sets.push('word_limit = ?'); vals.push(n);
      }
      if (body.limit_period !== undefined) {
        if (!['day', 'week', null].includes(body.limit_period)) throw new HttpError(400, 'invalid_limit_period');
        sets.push('limit_period = ?'); vals.push(body.limit_period);
      }
      if (body.status !== undefined) {
        if (!['active', 'blocked'].includes(body.status)) throw new HttpError(400, 'invalid_status');
        sets.push('status = ?'); vals.push(body.status);
      }
      if (sets.length) await env.DB.prepare(`UPDATE users SET ${sets.join(', ')} WHERE id = ?`).bind(...vals, target.id).run();
      return json({ ok: true });
    }
    if (m === 'PUT' && um[2] === '/keys') {
      const body = await readJson(request);
      if (!PROVIDERS.has(body.provider)) throw new HttpError(400, 'invalid_provider');
      if (body.key == null || body.key === '') {
        await env.DB.prepare('DELETE FROM user_keys WHERE user_id = ? AND provider = ?').bind(target.id, body.provider).run();
      } else {
        const k = cleanKey(body.key);
        await env.DB.prepare(
          `INSERT INTO user_keys (user_id, provider, enc, hint, updated_at) VALUES (?, ?, ?, ?, ?)
           ON CONFLICT(user_id, provider) DO UPDATE SET enc = excluded.enc, hint = excluded.hint, updated_at = excluded.updated_at`
        ).bind(target.id, body.provider, await sealKey(env, k), keyHint(k), Date.now()).run();
      }
      return json({ ok: true });
    }
    if (m === 'POST' && um[2] === '/reset-password') {
      const temp = b64url(crypto.getRandomValues(new Uint8Array(9)));
      await env.DB.batch([
        env.DB.prepare('UPDATE users SET pass_hash = ?, failed_logins = 0, locked_until = 0 WHERE id = ?').bind(await hashPassword(temp, env), target.id),
        env.DB.prepare('DELETE FROM sessions WHERE user_id = ?').bind(target.id),
        env.DB.prepare('DELETE FROM reset_requests WHERE user_id = ?').bind(target.id),
      ]);
      return json({ ok: true, password: temp });
    }
    if (m === 'DELETE' && um[2] === '/reset-request') {
      await env.DB.prepare('DELETE FROM reset_requests WHERE user_id = ?').bind(target.id).run();
      return json({ ok: true });
    }
  }
  throw new HttpError(404, 'not_found');
}

function cleanKey(k) {
  if (typeof k !== 'string') throw new HttpError(400, 'invalid_key');
  const t = k.trim();
  if (t.length < 20 || t.length > 300 || /\s/.test(t)) throw new HttpError(400, 'invalid_key');
  return t;
}
