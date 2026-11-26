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

test("Windows backfills phone notes and meetings before reusing an old sync cursor", async (t) => {
  const Module = require("node:module");
  const load = Module._load;
  const mocked = t.mock.method(Module, "_load", function (name, ...args) {
    if (name === "electron") return { BrowserWindow: { getAllWindows: () => [] } };
    return load.call(this, name, ...args);
  });
  let Sync;
  try {
    Sync = require("../../src/helpers/whisperashSync");
  } finally {
    mocked.mock.restore();
  }

  const received = [];
  const cursors = [];
  let savedConfig;
  const last = { ts: 120_000, kind: "meetings", id: "phone-meeting" };
  const notes = [{ kind: "notes" }];
  const meetings = [{ kind: "meetings" }];
  const sync = Object.assign(Object.create(Sync.prototype), {
    config: { userId: "owner-a", settingsPulledFor: "owner-a", cursor: { ts: 90_000 } },
    _request: async (_, { body }) => {
      cursors.push(body.cursor);
      if (cursors.length === 1) return { items: notes, more: true, cursor: { ts: 50_000 } };
      if (cursors.length === 2) return { items: meetings, more: false, cursor: last };
      return { items: [], more: false, cursor: last };
    },
    _applyRemote: (item) => { received.push(item.kind); return item.kind; },
    _writeConfig() { savedConfig = { ...this.config }; },
  });

  await sync._pull();
  assert.deepEqual(cursors, [null, { ts: 50_000 }]);
  assert.deepEqual(received, ["notes", "meetings"]);
  assert.deepEqual(savedConfig.cursor, { ts: 110_000, kind: "", id: "" });

  // Reopening the app after a successful backfill should resume incremental sync.
  sync.config = savedConfig;
  await sync._pull();
  assert.deepEqual(cursors[2], { ts: 110_000, kind: "", id: "" });
});
