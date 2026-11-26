const { DatabaseSync } = require("node:sqlite");
const assert = require("node:assert/strict");
const test = require("node:test");
const WhisperAshNotes = require("../../src/helpers/whisperashNotes");

test("phone notes preserve metadata and Windows edits and deletes become sync records", () => {
  const db = new DatabaseSync(":memory:");
  const store = new WhisperAshNotes(db);
  const owner = "owner-a";
  const fromPhone = { title: "Checklist", body: "- milk", raw: "milk", created_ms: 100,
    pinned: true, folderId: "folder-a", locked: false, binnedAt: null, editedAt: 150 };
  store.applyRemote(owner, { kind: "folders", id: "folder-a", updated_at: 100, data: { name: "Home" } });
  store.applyRemote(owner, { kind: "notes", id: "phone-note", updated_at: 150, data: fromPhone });
  assert.equal(store.list("another-owner").notes.length, 0);
  assert.equal(store.list(owner).folders[0].name, "Home");
  const saved = store.save(owner, { id: "phone-note", updatedAt: 150, title: "Checklist", body: "- milk\n- eggs" });
  const [row] = store.dirty(owner, 10);
  assert.deepEqual(JSON.parse(row.data), { ...fromPhone, body: "- milk\n- eggs", editedAt: saved.updatedAt });
  assert.throws(() => store.save(owner, { id: "phone-note", updatedAt: 150 }), /changed/);
  store.clean(owner, row);
  const next = { ...fromPhone, locked: true };
  store.applyRemote(owner, { kind: "notes", id: "phone-note", updated_at: saved.updatedAt + 1, data: next });
  assert.equal(store.list(owner).notes[0].body, "");
  assert.throws(() => store.remove(owner, "phone-note", saved.updatedAt + 1), /Unlock/);
  const own = store.save(owner, { title: "From Windows", body: "Sync this." });
  store.remove(owner, own.id, own.updatedAt);
  assert.equal(store.dirty(owner, 10)[0].deleted, 1);
  db.close();
});
