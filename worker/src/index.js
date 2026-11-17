// WhisperAsh sync API — Cloudflare Worker + D1.
// Auth: email + password (PBKDF2-SHA256 with per-user salt and a server-side pepper),
// opaque session tokens (only their SHA-256 is stored). Sync: last-write-wins per record.

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
  if (!env.PEPPER || env.PEPPER.length < 16) throw new HttpError(500, 'server_not_configured');

  if (m === 'POST' && path === '/auth/register') return register(request, env);
  if (m === 'POST' && path === '/auth/login') return login(request, env);

  const session = await authenticate(request, env);
  if (m === 'POST' && path === '/auth/logout') {
    await env.DB.prepare('DELETE FROM sessions WHERE token_hash = ?').bind(session.tokenHash).run();
    return json({ ok: true });
  }
  if (m === 'GET' && path === '/me') {
    const u = await env.DB.prepare('SELECT id, email, created_at FROM users WHERE id = ?').bind(session.userId).first();
    return json({ user: u });
  }
  if (m === 'POST' && path === '/sync/push') return push(request, env, session);
  if (m === 'POST' && path === '/sync/pull') return pull(request, env, session);
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
