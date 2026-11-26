// WhisperAsh account + Cloudflare sync (main process).
//
// - Login/register against the WhisperAsh Worker (see ../../../worker).
// - The session token is encrypted with Electron safeStorage (Windows DPAPI).
// - History, the personal dictionary and settings sync in the background,
//   never in the dictation hot path: the app always saves locally first.
// - Offline-first: changes are detected by comparing the local database with a
//   small tracking database (whisperash-sync.db), so anything changed while
//   offline is pushed on the next successful sync.
// - Also keeps the local Groq usage counter (requests and billed audio seconds).

const { app, safeStorage, BrowserWindow } = require("electron");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const Database = require("better-sqlite3");
const debugLogger = require("./debugLogger");

// Default WhisperAsh sync server (the owner's Cloudflare Worker). Users can
// change it in Settings > Profile to point at their own Worker.
const DEFAULT_SERVER_URL = "https://whisperash-sync.whisperash-ashish.workers.dev";
const SYNC_INTERVAL_MS = 3 * 60 * 1000;
const DEBOUNCE_MS = 4000;
const PUSH_BATCH = 200;
const PULL_OVERLAP_MS = 10 * 1000;
const PULL_VERSION = 1;
const REQUEST_TIMEOUT_MS = 20000;
const HISTORY_SCAN_LIMIT = 5000;

// Groq free-tier limits (console.groq.com/docs/rate-limits, checked 2026-11-15).
const GROQ_LIMITS = {
  stt: { rpm: 20, rpd: 2000, audioPerHour: 7200, audioPerDay: 28800, minBilledSeconds: 10 },
  llm: { rpm: 30, rpd: 1000, tpd: 200000 },
};

function sha(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function broadcast(channel, payload) {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send(channel, payload);
  }
}

const WhisperAshNotes = require("./whisperashNotes");

class WhisperAshSync {
  constructor({ databaseManager }) {
    this.databaseManager = databaseManager;
    this.dir = app.getPath("userData");
    this.configPath = path.join(this.dir, "whisperash-account.json");
    this.tokenPath = path.join(this.dir, "whisperash-token.bin");
    this.db = new Database(path.join(this.dir, "whisperash-sync.db"));
    this.db.pragma("journal_mode = WAL");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS sync_map (
        kind TEXT NOT NULL, id TEXT NOT NULL, hash TEXT NOT NULL,
        updated_at INTEGER NOT NULL, dirty INTEGER NOT NULL DEFAULT 0,
        deleted INTEGER NOT NULL DEFAULT 0, data TEXT,
        PRIMARY KEY (kind, id)
      );
      CREATE TABLE IF NOT EXISTS usage_events (
        ts INTEGER NOT NULL, kind TEXT NOT NULL, billed_seconds REAL NOT NULL DEFAULT 0,
        tokens INTEGER NOT NULL DEFAULT 0, model TEXT
      );
      CREATE INDEX IF NOT EXISTS usage_ts ON usage_events(ts);
      CREATE TABLE IF NOT EXISTS timings (
        ts INTEGER NOT NULL, data TEXT NOT NULL
      );
    `);
    this.notes = new WhisperAshNotes(this.db);
    this.config = this._readConfig();
    if (!this.config.serverUrl) this.config.serverUrl = DEFAULT_SERVER_URL;
    this.timer = null;
    this.debounce = null;
    this.running = null;
    this.rerun = false;
    this.status = { state: "idle", lastSyncAt: this.config.lastSyncAt || null, lastError: null };
  }

  // ---------- config + token ----------
  _readConfig() {
    try {
      return JSON.parse(fs.readFileSync(this.configPath, "utf8"));
    } catch {
      return {};
    }
  }
  _writeConfig() {
    const tmp = this.configPath + ".tmp";
    fs.writeFileSync(tmp, JSON.stringify(this.config, null, 2));
    fs.renameSync(tmp, this.configPath);
  }
  _readToken() {
    try {
      if (!fs.existsSync(this.tokenPath)) return null;
      return safeStorage.decryptString(fs.readFileSync(this.tokenPath));
    } catch (err) {
      debugLogger.warn("WhisperAsh token unreadable", { error: err.message });
      return null;
    }
  }
  _writeToken(token) {
    if (!safeStorage.isEncryptionAvailable()) throw new Error("Windows encryption (DPAPI) unavailable");
    fs.writeFileSync(this.tokenPath, safeStorage.encryptString(token));
  }
  _clearToken() {
    try {
      fs.unlinkSync(this.tokenPath);
    } catch {
      /* already gone */
    }
  }

  getState() {
    const pending = this.db.prepare("SELECT COUNT(*) AS n FROM sync_map WHERE dirty = 1").get().n +
      this.notes.pending(this.config.userId || "");
    return {
      serverUrl: this.config.serverUrl || "",
      email: this.config.email || "",
      signedIn: !!(this.config.email && fs.existsSync(this.tokenPath)),
      pending,
      ...this.status,
      usage: this.getUsage(),
      lastTiming: this.getLastTiming(),
      engine: { account: this.useAccount(), source: this.config.engineSource || null },
      plan: this.plan || null,
    };
  }

  // ---------- account mode (dictation through the WhisperAsh server on the owner's keys) ----------
  // "account" | "own" | unset. Unset = account mode unless this PC already has its own Groq key,
  // so existing installs keep working exactly as before until the user switches.
  useAccount() {
    if (this.config.engineSource === "own") return false;
    if (this.config.engineSource === "account") return true;
    return !String(process.env.GROQ_API_KEY || "").trim();
  }

  /** For the renderer's whisperashEngine: where Groq/Gemini requests go and with which token. */
  engineInfo() {
    const account = this.useAccount();
    const signedIn = !!(this.config.email && fs.existsSync(this.tokenPath));
    return {
      account,
      base: (this.config.serverUrl || "").replace(/\/+$/, "") || null,
      token: account && signedIn ? this._readToken() : null,
    };
  }

  /** Meetings: the account gateway is used whenever signed in (a provider's own key still wins). */
  meetingAccount() {
    const signedIn = !!(this.config.email && fs.existsSync(this.tokenPath));
    const base = (this.config.serverUrl || "").replace(/\/+$/, "");
    if (!signedIn || !/^https?:\/\//.test(base)) return null;
    const token = this._readToken();
    return token ? { base, token } : null;
  }

  setEngineSource(source) {
    this.config.engineSource = source === "own" || source === "account" ? source : null;
    this._writeConfig();
    this._emitState();
    if (this.useAccount()) this.refreshPlan();
    return this.getState();
  }

  /** The account's word allowance (GET /me), shown in Settings > Profile. */
  async refreshPlan() {
    if (!this.config.email || !fs.existsSync(this.tokenPath)) return null;
    try {
      const data = await this._request("/me", { method: "GET" });
      this.plan = data.plan || null;
      this._emitState();
    } catch (err) {
      debugLogger.warn("WhisperAsh plan refresh failed", { error: err.message });
    }
    return this.plan || null;
  }

  /** "Forgot password": the admin sees the request in the dashboard and sends a temporary password. */
  async requestPasswordReset({ serverUrl, email }) {
    if (serverUrl) this.config.serverUrl = String(serverUrl).trim();
    await this._request("/auth/forgot", { auth: false, body: { email } });
    return this.getState();
  }

  /** Signed in: replace the password; the server signs out the account's other devices. */
  async changePassword({ current, password }) {
    await this._request("/auth/change-password", { body: { current, password } });
    return this.getState();
  }

  _emitState() {
    broadcast("whisperash-sync-state", this.getState());
  }

  // ---------- HTTP ----------
  async _request(pathname, { method = "POST", body, auth = true } = {}) {
    const base = (this.config.serverUrl || "").replace(/\/+$/, "");
    if (!/^https?:\/\//.test(base)) throw new Error("Set the sync server URL first");
    const headers = { "content-type": "application/json" };
    if (auth) {
      const token = this._readToken();
      if (!token) throw new Error("Not signed in");
      headers.authorization = `Bearer ${token}`;
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const res = await fetch(base + pathname, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        const err = new Error(data.error || `HTTP ${res.status}`);
        err.status = res.status;
        throw err;
      }
      return data;
    } finally {
      clearTimeout(timeout);
    }
  }

  // ---------- account ----------
  // The server box is under "Advanced" now: a blank value keeps the current (default) server.
  _useServer(serverUrl) {
    const url = String(serverUrl || "").trim();
    this.config.serverUrl = url || this.config.serverUrl || DEFAULT_SERVER_URL;
  }

  async register({ serverUrl, email, password, signupCode }) {
    if (this.running) await this.running;
    this._useServer(serverUrl);
    const data = await this._request("/auth/register", {
      auth: false,
      body: { email, password, signupCode: signupCode || undefined, device: os.hostname() },
    });
    return this._afterLogin(data);
  }

  async login({ serverUrl, email, password }) {
    if (this.running) await this.running;
    this._useServer(serverUrl);
    const data = await this._request("/auth/login", {
      auth: false,
      body: { email, password, device: os.hostname() },
    });
    return this._afterLogin(data);
  }

  async _afterLogin(data) {
    // A different account than last time: forget the old cursor and map so
    // everything local is offered to the new account and its data is pulled in full.
    if (this.running) await this.running;
    if (!this.config.userId) {
      this.notes.claimLocal(data.user.id);
      this.meetingsSource?.claimLocalDiaries(data.user.id);
    }
    if (this.config.userId && this.config.userId !== data.user.id) {
      this.db.exec("DELETE FROM sync_map");
    }
    this._writeToken(data.token);
    this.config.email = data.user.email;
    this.config.userId = data.user.id;
    this.config.cursor = null;
    this._writeConfig();
    this.start();
    this.requestSync("login");
    this._emitState();
    this.refreshPlan();
    return this.getState();
  }

  async logout() {
    if (this.running) await this.running;
    try {
      await this._request("/auth/logout");
    } catch (err) {
      debugLogger.warn("WhisperAsh logout request failed (token removed locally anyway)", {
        error: err.message,
      });
    }
    this._clearToken();
    this.config.email = "";
    this._writeConfig();
    this.stop();
    this._emitState();
    return this.getState();
  }

  setServerUrl(serverUrl) {
    this.config.serverUrl = String(serverUrl || "").trim();
    this._writeConfig();
    this._emitState();
  }

  listNotes() {
    return this.notes.list(this.config.userId || "");
  }

  saveNote(input) {
    const result = this.notes.save(this.config.userId || "", input);
    this.requestSync("change");
    return result;
  }

  deleteNote({ id, updatedAt }) {
    this.notes.remove(this.config.userId || "", id, updatedAt);
    this.requestSync("change");
  }

  // ---------- scheduling ----------
  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.requestSync("interval"), SYNC_INTERVAL_MS);
  }
  stop() {
    clearInterval(this.timer);
    this.timer = null;
    clearTimeout(this.debounce);
  }
  init() {
    if (this.getState().signedIn) {
      this.start();
      setTimeout(() => this.requestSync("startup"), 5000);
    }
  }
  // Called after local changes; waits a few seconds so a dictation never shares
  // the network with a sync.
  requestSync(reason = "change") {
    if (!this.getState().signedIn) return;
    clearTimeout(this.debounce);
    const delay = reason === "change" ? DEBOUNCE_MS : 0;
    this.debounce = setTimeout(() => {
      this.syncNow(reason).catch(() => {});
    }, delay);
  }

  async syncNow(reason = "manual") {
    if (this.running) {
      this.rerun = true;
      return this.running;
    }
    this.running = (async () => {
      this.status = { ...this.status, state: "syncing", lastError: null };
      this._emitState();
      try {
        this._scanLocalChanges();
        await this._push();
        await this._pull();
        this.status = { state: "idle", lastSyncAt: Date.now(), lastError: null };
        this.config.lastSyncAt = this.status.lastSyncAt;
        if (this.useAccount()) this.refreshPlan();
        this._writeConfig();
        debugLogger.info("WhisperAsh sync done", { reason });
      } catch (err) {
        const offline = err.name === "AbortError" || /fetch failed|ENOTFOUND|ECONNREFUSED/i.test(err.message);
        this.status = {
          ...this.status,
          state: offline ? "offline" : "error",
          lastError: offline ? "Offline: changes are saved and will sync later" : err.message,
        };
        if (err.status === 401) {
          this._clearToken();
          this.status.lastError = "Session expired: please log in again";
        }
        debugLogger.warn("WhisperAsh sync failed", { reason, error: err.message });
      } finally {
        this._emitState();
      }
    })();
    try {
      await this.running;
    } finally {
      this.running = null;
      if (this.rerun) {
        this.rerun = false;
        this.requestSync("rerun");
      }
    }
  }

  // ---------- local change detection ----------
  _mark(kind, id, data, deleted = false) {
    const hash = sha(JSON.stringify(deleted ? { deleted: true } : data));
    const row = this.db.prepare("SELECT hash, deleted FROM sync_map WHERE kind = ? AND id = ?").get(kind, id);
    if (row && row.hash === hash) return;
    if (!row && deleted) return; // never synced, nothing to delete remotely
    this.db
      .prepare(
        `INSERT INTO sync_map (kind, id, hash, updated_at, dirty, deleted, data) VALUES (?, ?, ?, ?, 1, ?, ?)
         ON CONFLICT(kind, id) DO UPDATE SET hash = excluded.hash, updated_at = excluded.updated_at,
           dirty = 1, deleted = excluded.deleted, data = excluded.data`
      )
      .run(kind, id, hash, Date.now(), deleted ? 1 : 0, deleted ? null : JSON.stringify(data));
  }

  _historyRows() {
    return this.databaseManager.db
      .prepare(
        `SELECT client_transcription_id AS cid, text, raw_text, timestamp, provider, model, audio_duration_ms
         FROM transcriptions
         WHERE deleted_at IS NULL AND status = 'completed' AND client_transcription_id IS NOT NULL
         ORDER BY id DESC LIMIT ?`
      )
      .all(HISTORY_SCAN_LIMIT);
  }

  _scanLocalChanges() {
    const scan = this.db.transaction(() => {
      // History
      const seen = new Set();
      for (const r of this._historyRows()) {
        seen.add(r.cid);
        this._mark("history", r.cid, {
          text: r.text,
          raw_text: r.raw_text,
          timestamp: r.timestamp,
          provider: r.provider,
          model: r.model,
          audio_duration_ms: r.audio_duration_ms,
        });
      }
      for (const { id } of this.db
        .prepare("SELECT id FROM sync_map WHERE kind = 'history' AND deleted = 0")
        .all()) {
        if (seen.has(id)) continue;
        const stillThere = this.databaseManager.db
          .prepare(
            "SELECT 1 FROM transcriptions WHERE client_transcription_id = ? AND deleted_at IS NULL"
          )
          .get(id);
        if (!stillThere) this._mark("history", id, null, true);
      }

      // Dictionary (id = lowercased word, so the same word on two PCs is one record)
      const words = this.databaseManager.getDictionary();
      const current = new Set();
      for (const word of words) {
        const id = "w:" + word.toLowerCase();
        current.add(id);
        this._mark("dictionary", id, { word });
      }
      for (const { id } of this.db
        .prepare("SELECT id FROM sync_map WHERE kind = 'dictionary' AND deleted = 0")
        .all()) {
        if (!current.has(id)) this._mark("dictionary", id, null, true);
      }

      if (this.meetingsSource) {
        for (const kind of ["meetings", "diaries"]) {
          const ids = new Set();
          for (const { id, data } of this.meetingsSource.syncItems(kind)) {
            ids.add(id);
            this._mark(kind, id, data);
          }
          for (const { id } of this.db
            .prepare("SELECT id FROM sync_map WHERE kind = ? AND deleted = 0").all(kind)) {
            if (!ids.has(id) && !this.meetingsSource.isPending(id)) this._mark(kind, id, null, true);
          }
        }
      }
    });
    scan();
  }

  // Settings come from the renderer (localStorage lives there).
  recordLocalSettings(values) {
    if (!values || typeof values !== "object") return;
    // Until this account's settings have been pulled once, local values are only
    // a baseline (updated_at 0), so a fresh PC never overwrites the cloud copy.
    if (!this.config.userId || this.config.settingsPulledFor !== this.config.userId) {
      this.db
        .prepare(
          `INSERT INTO sync_map (kind, id, hash, updated_at, dirty, deleted, data) VALUES ('settings', 'settings', ?, 0, 0, 0, ?)
           ON CONFLICT(kind, id) DO UPDATE SET hash = excluded.hash, data = excluded.data WHERE sync_map.updated_at = 0`
        )
        .run(sha(JSON.stringify(values)), JSON.stringify(values));
      return;
    }
    this._mark("settings", "settings", values);
    this.requestSync("change");
  }

  // ---------- push / pull ----------
  _pushBody(rows) {
    return {
      items: rows.map((r) => ({
        kind: r.kind,
        id: r.id,
        updated_at: r.updated_at,
        deleted: !!r.deleted,
        data: r.data ? JSON.parse(r.data) : {},
      })),
    };
  }

  _clearDirty(rows) {
    const clear = this.db.prepare("UPDATE sync_map SET dirty = 0 WHERE kind = ? AND id = ? AND updated_at = ?");
    this.db.transaction(() => rows.forEach((r) => clear.run(r.kind, r.id, r.updated_at)))();
  }

  async _push() {
    for (;;) {
      const rows = this.db
        .prepare("SELECT kind, id, updated_at, deleted, data FROM sync_map WHERE dirty = 1 AND kind NOT IN ('meetings', 'diaries') LIMIT ?")
        .all(PUSH_BATCH);
      if (!rows.length) break;
      await this._request("/sync/push", { body: this._pushBody(rows) });
      this._clearDirty(rows);
    }
    await this._pushRecordings("meetings");
    await this._pushRecordings("diaries");
    await this._pushNotes();
  }

  // Meetings go one per request (each can be up to 512 KB), apart from the other kinds, so a
  // server that doesn't take them yet (400 invalid_item, before the Worker update) never holds
  // up history and dictionary sync: they stay pending and are tried again on the next pass.
  async _pushRecordings(kind) {
    const rows = this.db
      .prepare("SELECT kind, id, updated_at, deleted, data FROM sync_map WHERE dirty = 1 AND kind = ?")
      .all(kind);
    for (const row of rows) {
      try {
        await this._request("/sync/push", { body: this._pushBody([row]) });
        this._clearDirty([row]);
      } catch (err) {
        if (err.status === 400 && err.message === "invalid_item") {
          debugLogger.info("WhisperAsh server does not support recording sync yet", { kind });
          return;
        }
        if (err.status === 400 && err.message === "item_too_large") {
          debugLogger.warn("WhisperAsh meeting too large to sync", { id: row.id });
          this._clearDirty([row]);
          continue;
        }
        throw err;
      }
    }
  }

  async _pushNotes() {
    const owner = this.config.userId;
    if (!owner) return;
    for (;;) {
      const rows = this.notes.dirty(owner, 20);
      if (!rows.length) return;
      try {
        await this._request("/sync/push", { body: this._pushBody(rows) });
        for (const row of rows) this.notes.clean(owner, row);
      } catch (err) {
        if (err.status === 400 && err.message === "invalid_item") return;
        throw err;
      }
    }
  }

  async _pull() {
    // Older Windows builds advanced the cursor before they could store notes and all meeting records.
    let cursor = this.config.pullVersion === PULL_VERSION ? this.config.cursor || null : null;
    let changedHistory = false;
    let changedDictionary = false;
    let changedNotes = false;
    let settings = null;
    for (;;) {
      const page = await this._request("/sync/pull", { body: { cursor } });
      for (const item of page.items) {
        const applied = this._applyRemote(item);
        if (applied === "history") changedHistory = true;
        if (applied === "dictionary") changedDictionary = true;
        if (applied === "settings") settings = item.data;
        if (applied === "notes" || applied === "folders") changedNotes = true;
      }
      cursor = page.cursor;
      if (!page.more) break;
    }
    // Re-read the last few seconds next time, in case a write landed mid-pull.
    this.config.cursor = cursor ? { ts: Math.max(0, cursor.ts - PULL_OVERLAP_MS), kind: "", id: "" } : null;
    this.config.pullVersion = PULL_VERSION;
    const firstPull = this.config.settingsPulledFor !== this.config.userId;
    this.config.settingsPulledFor = this.config.userId;
    this._writeConfig();
    // First sync of this account on this PC and the cloud had no settings yet:
    // offer this PC's settings as the starting point.
    if (firstPull && !settings) {
      this.db
        .prepare("UPDATE sync_map SET dirty = 1, updated_at = ? WHERE kind = 'settings' AND id = 'settings' AND data IS NOT NULL")
        .run(Date.now());
      this.rerun = true;
    }
    if (changedHistory) broadcast("whisperash-history-changed", {});
    if (changedDictionary) broadcast("dictionary-updated", this.databaseManager.getDictionary());
    if (settings) broadcast("whisperash-apply-settings", settings);
    if (changedNotes) broadcast("whisperash-notes-changed", {});
  }

  _applyRemote(item) {
    if (item.kind === "notes" || item.kind === "folders") {
      const owner = this.config.userId;
      return owner && this.notes.applyRemote(owner, item) ? item.kind : null;
    }
    const local = this.db.prepare("SELECT updated_at, dirty FROM sync_map WHERE kind = ? AND id = ?").get(item.kind, item.id);
    // A newer local edit that has not been pushed yet wins.
    if (local && local.dirty && local.updated_at > item.updated_at) return null;
    if (local && !local.dirty && local.updated_at >= item.updated_at) return null;
    const dbm = this.databaseManager;

    if (item.kind === "history") {
      const row = dbm.db
        .prepare("SELECT id, text, raw_text, deleted_at FROM transcriptions WHERE client_transcription_id = ?")
        .get(item.id);
      // History list updates use the app's own events (sent directly, so they
      // don't trigger another sync).
      if (item.deleted) {
        if (row && !row.deleted_at) {
          dbm.deleteTranscription(row.id);
          broadcast("transcription-deleted", { id: row.id });
        }
      } else if (row && !row.deleted_at) {
        if (row.text !== item.data.text || row.raw_text !== (item.data.raw_text ?? null)) {
          dbm.updateTranscriptionText(row.id, item.data.text, item.data.raw_text ?? null);
          const updated = dbm.getTranscriptionById(row.id);
          if (updated) broadcast("transcription-updated", updated);
        }
      } else if (!row) {
        const saved = dbm.saveTranscription(item.data.text || "", item.data.raw_text ?? null, {
          clientTranscriptionId: item.id,
          analyticsOccurredAt: typeof item.data.timestamp === "string" ? item.data.timestamp : null,
        });
        if (saved?.transcription) broadcast("transcription-added", saved.transcription);
      }
    } else if (item.kind === "dictionary") {
      const word = item.data?.word || item.id.replace(/^w:/, "");
      if (item.deleted) dbm.applyDictionaryChanges({ remove: [word] }, "sync");
      else dbm.applyDictionaryChanges({ add: [word] }, "sync");
    } else if (item.kind === "meetings" || item.kind === "diaries") {
      if (!this.meetingsSource || !this.meetingsSource.applyRemote(item)) return null;
    } else if (item.kind !== "settings") {
      return null;
    }

    // A pulled meeting is hashed as this PC will read it back, so the next scan doesn't push it again.
    const hashData =
      (item.kind === "meetings" || item.kind === "diaries") && !item.deleted
        ? this.meetingsSource.syncData(item.id) || item.data
        : item.data;
    const hash = sha(JSON.stringify(item.deleted ? { deleted: true } : hashData));
    this.db
      .prepare(
        `INSERT INTO sync_map (kind, id, hash, updated_at, dirty, deleted, data) VALUES (?, ?, ?, ?, 0, ?, ?)
         ON CONFLICT(kind, id) DO UPDATE SET hash = excluded.hash, updated_at = excluded.updated_at,
           dirty = 0, deleted = excluded.deleted, data = excluded.data`
      )
      .run(item.kind, item.id, hash, item.updated_at, item.deleted ? 1 : 0, item.deleted ? null : JSON.stringify(item.data));
    return item.kind;
  }

  // ---------- Groq usage counter ----------
  // Cleanup rows carry Groq's real token count (from the API response) and,
  // for both kinds, Groq's own x-ratelimit-*-requests headers when present.
  _ensureUsageColumns() {
    if (this._usageColumnsReady) return;
    for (const col of ["remaining_requests INTEGER", "limit_requests INTEGER"]) {
      try {
        this.db.exec(`ALTER TABLE usage_events ADD COLUMN ${col}`);
      } catch {
        /* already there */
      }
    }
    this._usageColumnsReady = true;
  }

  // Called by main.js for every completed Groq chat/transcription request.
  noteGroqHeaders(details) {
    try {
      if (details.statusCode !== 200) return;
      const h = {};
      for (const [k, v] of Object.entries(details.responseHeaders || {})) {
        h[k.toLowerCase()] = Array.isArray(v) ? v[0] : v;
      }
      const num = (k) => (h[k] != null && Number.isFinite(Number(h[k])) ? Number(h[k]) : null);
      const kind = String(details.url).includes("/audio/transcriptions") ? "stt" : "llm";
      this._pendingGroq = this._pendingGroq || {};
      this._pendingGroq[kind] = {
        at: Date.now(),
        remainingRequests: num("x-ratelimit-remaining-requests"),
        limitRequests: num("x-ratelimit-limit-requests"),
        remainingAudioSeconds: num("x-ratelimit-remaining-audio-seconds"),
        limitAudioSeconds: num("x-ratelimit-limit-audio-seconds"),
      };
      if (kind === "stt") this._latestSttAudio = this._pendingGroq.stt;
    } catch {
      /* best effort */
    }
  }

  recordUsage({ kind, audioSeconds = 0, tokens = 0, model = null, remainingRequests = null, limitRequests = null }) {
    if (kind !== "stt" && kind !== "llm") return;
    this._ensureUsageColumns();
    // Headers captured by the main process for the same request (within 30 s).
    const pending = this._pendingGroq?.[kind];
    if (pending && Date.now() - pending.at < 30000) {
      if (remainingRequests == null) remainingRequests = pending.remainingRequests;
      if (limitRequests == null) limitRequests = pending.limitRequests;
      this._pendingGroq[kind] = null;
    }
    const billed = kind === "stt" ? Math.max(GROQ_LIMITS.stt.minBilledSeconds, Math.ceil(audioSeconds)) : 0;
    const int = (v) => (Number.isFinite(Number(v)) && v !== null ? Math.round(Number(v)) : null);
    this.db
      .prepare(
        "INSERT INTO usage_events (ts, kind, billed_seconds, tokens, model, remaining_requests, limit_requests) VALUES (?, ?, ?, ?, ?, ?, ?)"
      )
      .run(Date.now(), kind, billed, Math.round(Number(tokens) || 0), model, int(remainingRequests), int(limitRequests));
    this.db.prepare("DELETE FROM usage_events WHERE ts < ?").run(Date.now() - 2 * 864e5);
  }

  getUsage() {
    this._ensureUsageColumns();
    const now = Date.now();
    const dayAgo = now - 864e5;
    const q = this.db.prepare(
      "SELECT COUNT(*) AS requests, COALESCE(SUM(billed_seconds),0) AS seconds, COALESCE(SUM(tokens),0) AS tokens FROM usage_events WHERE kind = ? AND ts >= ?"
    );
    const stt = { minute: q.get("stt", now - 60e3), hour: q.get("stt", now - 3600e3), day: q.get("stt", dayAgo) };
    // Cleanup rows from before real Groq token counts were estimates (and also
    // counted skipped cleanups), so cleanup totals start at usageRealSince.
    if (!this.config.usageRealSince) {
      this.config.usageRealSince = now;
      this._writeConfig();
    }
    const llmSince = Math.max(dayAgo, this.config.usageRealSince);
    const llm = { minute: q.get("llm", Math.max(now - 60e3, llmSince)), day: q.get("llm", llmSince), since: llmSince };

    // Each Groq model has its own daily allowance, so cleanup usage is per model.
    // "groq" holds Groq's own latest remaining/limit headers for that model, which
    // also include any other device using the same Groq key.
    const latest = this.db.prepare(
      `SELECT remaining_requests AS remaining, limit_requests AS lim, ts FROM usage_events
       WHERE kind = ? AND model = ? AND remaining_requests IS NOT NULL AND ts >= ? ORDER BY ts DESC LIMIT 1`
    );
    const llmModels = this.db
      .prepare(
        `SELECT model, COUNT(*) AS requests, COALESCE(SUM(tokens),0) AS tokens FROM usage_events
         WHERE kind = 'llm' AND ts >= ? AND model IS NOT NULL GROUP BY model ORDER BY tokens DESC`
      )
      .all(llmSince)
      .map((m) => ({ ...m, groq: latest.get("llm", m.model, llmSince) || null }));
    const sttModel = this.db
      .prepare("SELECT model FROM usage_events WHERE kind = 'stt' AND ts >= ? AND model IS NOT NULL ORDER BY ts DESC LIMIT 1")
      .get(dayAgo)?.model;
    const sttGroq = sttModel ? latest.get("stt", sttModel, dayAgo) || null : null;
    return {
      stt: {
        ...stt,
        groq: sttGroq,
        model: sttModel || null,
        groqAudioHour:
          this._latestSttAudio && now - this._latestSttAudio.at < 3600e3 && this._latestSttAudio.remainingAudioSeconds != null
            ? { remaining: this._latestSttAudio.remainingAudioSeconds, lim: this._latestSttAudio.limitAudioSeconds }
            : null,
      },
      llm: { ...llm, models: llmModels },
      limits: GROQ_LIMITS,
      words: this._wordAllowance(stt),
    };
  }

  // "Free words left": Groq's free limits are per rolling 24 h (audio seconds
  // and requests) with no monthly cap, so the month figure is today's remainder
  // plus a full day's allowance for every day left in the month.
  _wordAllowance(stt) {
    const countWords = (sinceSql) => {
      try {
        const rows = this.databaseManager.db
          .prepare(
            `SELECT text FROM transcriptions WHERE deleted_at IS NULL AND status = 'completed' AND timestamp >= ?`
          )
          .all(sinceSql);
        let words = 0;
        for (const r of rows) words += (r.text || "").trim().split(/\s+/).filter(Boolean).length;
        return { words, dictations: rows.length };
      } catch {
        return { words: 0, dictations: 0 };
      }
    };
    const toDb = (d) => d.toISOString().replace("T", " ");
    const now = new Date();
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    const today = countWords(toDb(startOfDay));
    const month = countWords(toDb(startOfMonth));
    const last24h = countWords(toDb(new Date(now.getTime() - 864e5)));

    // Words per billed second, learned from this PC's own last 24 h when there is
    // enough data; otherwise ~2 words/s (normal speech, with short-clip padding).
    const perSecond =
      stt.day.seconds >= 60 && last24h.words > 0 ? last24h.words / stt.day.seconds : 2.0;
    const wordsPerRequest =
      stt.day.requests >= 3 && last24h.dictations > 0 ? last24h.words / last24h.dictations : 25;
    const limits = GROQ_LIMITS.stt;
    const secondsLeft = Math.max(0, limits.audioPerDay - stt.day.seconds);
    const requestsLeft = Math.max(0, limits.rpd - stt.day.requests);
    const leftToday = Math.round(Math.min(secondsLeft * perSecond, requestsLeft * wordsPerRequest));
    const fullDay = Math.round(Math.min(limits.audioPerDay * perSecond, limits.rpd * wordsPerRequest));
    const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
    const daysAfterToday = daysInMonth - now.getDate();
    return {
      dictatedToday: today.words,
      dictatedMonth: month.words,
      leftToday,
      leftMonth: leftToday + daysAfterToday * fullDay,
      fullDay,
      perSecond: Math.round(perSecond * 100) / 100,
      wordsPerRequest: Math.round(wordsPerRequest),
    };
  }

  // ---------- stage timings ----------
  recordTiming(data) {
    this.db.prepare("INSERT INTO timings (ts, data) VALUES (?, ?)").run(Date.now(), JSON.stringify(data));
    this.db.prepare("DELETE FROM timings WHERE ts < ?").run(Date.now() - 7 * 864e5);
    try {
      fs.appendFileSync(
        path.join(this.dir, "whisperash-timing.log"),
        `${new Date().toISOString()} ${JSON.stringify(data)}\n`
      );
    } catch {
      /* best effort */
    }
    this._emitState();
  }

  getLastTiming() {
    const row = this.db.prepare("SELECT data FROM timings ORDER BY ts DESC LIMIT 1").get();
    return row ? JSON.parse(row.data) : null;
  }
}

module.exports = WhisperAshSync;
