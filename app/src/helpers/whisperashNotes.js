const { randomUUID } = require("node:crypto");

const KINDS = new Set(["notes", "folders"]);
const NOTE_LIMIT_BYTES = 60_000;

class WhisperAshNotes {
  constructor(db) {
    this.db = db;
    db.exec(`
      CREATE TABLE IF NOT EXISTS whisperash_notes (
        owner TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, updated_at INTEGER NOT NULL,
        deleted INTEGER NOT NULL DEFAULT 0, dirty INTEGER NOT NULL DEFAULT 0, data TEXT,
        PRIMARY KEY (owner, kind, id)
      )
    `);
  }

  claimLocal(owner) {
    this.db.prepare("UPDATE whisperash_notes SET owner = ? WHERE owner = ''").run(owner);
  }

  list(owner) {
    const notes = [];
    const folders = [];
    const rows = this.db.prepare(
      "SELECT * FROM whisperash_notes WHERE owner = ? AND deleted = 0 ORDER BY updated_at DESC"
    ).all(owner);
    for (const row of rows) {
      const data = JSON.parse(row.data);
      if (row.kind === "folders") {
        folders.push({ id: row.id, name: data.name || "" });
      } else if (data.binnedAt == null) {
        notes.push({
          id: row.id, updatedAt: row.updated_at, title: data.title || "",
          body: data.locked ? "" : data.body || "", locked: !!data.locked,
          pinned: !!data.pinned, folderId: data.folderId || null
        });
      }
    }
    notes.sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
    return { notes, folders };
  }

  save(owner, input) {
    const id = input.id || randomUUID();
    const old = this._get(owner, "notes", id);
    if (input.id && (!old || old.deleted || old.updated_at !== input.updatedAt)) {
      throw new Error("This note changed on another device. Your edits are still here. Reopen the note before saving.");
    }
    const existing = old ? JSON.parse(old.data) : {};
    if (existing.locked || existing.binnedAt != null) throw new Error("Unlock or restore this note on your phone before editing.");
    const now = Math.max(Date.now(), (old?.updated_at || 0) + 1);
    const title = String(input.title || "").trim().slice(0, 200);
    const body = String(input.body || "");
    if (!title && !body.trim()) throw new Error("Write something before saving.");
    const data = {
      ...existing, title, body, raw: existing.raw || "", pinned: !!existing.pinned,
      created_ms: existing.created_ms || now, folderId: existing.folderId || null,
      locked: false, binnedAt: null, editedAt: now
    };
    // Existing long notes may carry a second copy in raw; match the phone's size policy.
    if (Buffer.byteLength(JSON.stringify(data)) > NOTE_LIMIT_BYTES) data.raw = "";
    if (Buffer.byteLength(JSON.stringify(data)) > NOTE_LIMIT_BYTES) {
      throw new Error("This note is too long to sync. Split it into smaller notes.");
    }
    this._set(owner, "notes", id, now, false, data, true);
    return { id, updatedAt: now };
  }

  remove(owner, id, updatedAt) {
    const old = this._get(owner, "notes", id);
    if (!old || old.deleted || old.updated_at !== updatedAt) throw new Error("This note changed. Reopen it before deleting.");
    if (JSON.parse(old.data).locked) throw new Error("Unlock this note on your phone first.");
    this._set(owner, "notes", id, Math.max(Date.now(), old.updated_at + 1), true, null, true);
  }

  pending(owner) {
    return this.db.prepare("SELECT COUNT(*) AS count FROM whisperash_notes WHERE owner = ? AND dirty = 1").get(owner).count;
  }

  dirty(owner, limit) {
    return this.db.prepare(
      "SELECT kind, id, updated_at, deleted, data FROM whisperash_notes WHERE owner = ? AND dirty = 1 ORDER BY updated_at, id LIMIT ?"
    ).all(owner, limit);
  }

  clean(owner, row) {
    this.db.prepare("UPDATE whisperash_notes SET dirty = 0 WHERE owner = ? AND kind = ? AND id = ? AND updated_at = ?")
      .run(owner, row.kind, row.id, row.updated_at);
  }

  applyRemote(owner, item) {
    if (!KINDS.has(item.kind)) return false;
    const local = this._get(owner, item.kind, item.id);
    if (local && (local.updated_at > item.updated_at || (!local.dirty && local.updated_at === item.updated_at))) return false;
    this._set(owner, item.kind, item.id, item.updated_at, item.deleted, item.data, false);
    return true;
  }

  _get(owner, kind, id) {
    return this.db.prepare("SELECT updated_at, dirty, deleted, data FROM whisperash_notes WHERE owner = ? AND kind = ? AND id = ?")
      .get(owner, kind, id);
  }

  _set(owner, kind, id, updatedAt, deleted, data, dirty) {
    this.db.prepare(`
      INSERT INTO whisperash_notes (owner, kind, id, updated_at, deleted, data, dirty) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(owner, kind, id) DO UPDATE SET updated_at = excluded.updated_at,
        deleted = excluded.deleted, data = excluded.data, dirty = excluded.dirty
    `).run(owner, kind, id, updatedAt, deleted ? 1 : 0, deleted ? null : JSON.stringify(data), dirty ? 1 : 0);
  }
}

module.exports = WhisperAshNotes;
