-- D1 schema for WhisperAsh sync. Safe to re-run.
CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE COLLATE NOCASE,
  pass_hash     TEXT NOT NULL,           -- pbkdf2-sha256$iterations$salt$hash (password is HMAC'd with PEPPER first)
  created_at    INTEGER NOT NULL,
  failed_logins INTEGER NOT NULL DEFAULT 0,
  locked_until  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,           -- SHA-256 of the session token; the token itself is never stored
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device     TEXT,
  created_at INTEGER NOT NULL,
  last_used  INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);

-- One table for every synced record: kind = 'history' | 'dictionary' | 'settings'
CREATE TABLE IF NOT EXISTS items (
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       TEXT NOT NULL,
  id         TEXT NOT NULL,
  data       TEXT NOT NULL,              -- JSON
  updated_at INTEGER NOT NULL,           -- client clock (ms), used for last-write-wins
  deleted    INTEGER NOT NULL DEFAULT 0,
  server_ts  INTEGER NOT NULL,           -- server clock (ms), used for the pull cursor
  PRIMARY KEY (user_id, kind, id)
);
CREATE INDEX IF NOT EXISTS items_pull ON items(user_id, server_ts, kind, id);
