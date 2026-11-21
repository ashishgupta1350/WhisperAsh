const test = require("node:test");
const assert = require("node:assert/strict");

const load = () => import("../../src/utils/whisperashRetranscribe.ts");

function memoryStorage() {
  const data = new Map();
  return { getItem: (k) => (data.has(k) ? data.get(k) : null), setItem: (k, v) => data.set(k, String(v)) };
}

function deps(overrides = {}) {
  const saves = [];
  const cleanups = [];
  return {
    saves,
    cleanups,
    storage: memoryStorage(),
    transcribe: async () => ({ ok: true, result: { rawText: " vercel pe deploy karo ", model: "gemini-3.6-flash" } }),
    cleanup: async (raw, model) => {
      cleanups.push({ raw, model });
      return "Vercel pe deploy karo.";
    },
    save: async (id, text, rawText) => {
      saves.push({ id, text, rawText });
      return { success: true };
    },
    ...overrides,
  };
}

const ENTRY = { id: 7, text: "versal pe deploy karo", raw_text: "versal pe deploy karo" };

test("re-transcribe: Gemini text, cleaned with gpt-oss-120b, saved with the raw text; undo restores", async () => {
  const { retranscribeDictationEntry, canUndoRetranscribe, undoRetranscribe, RETRANSCRIBE_CLEANUP_MODEL } =
    await load();
  const d = deps();
  const outcome = await retranscribeDictationEntry(ENTRY, d);
  assert.equal(RETRANSCRIBE_CLEANUP_MODEL, "openai/gpt-oss-120b");
  assert.deepEqual(d.cleanups, [{ raw: "vercel pe deploy karo", model: "openai/gpt-oss-120b" }]);
  assert.deepEqual(d.saves, [{ id: 7, text: "Vercel pe deploy karo.", rawText: "vercel pe deploy karo" }]);
  assert.deepEqual(outcome, {
    text: "Vercel pe deploy karo.",
    rawText: "vercel pe deploy karo",
    model: "gemini-3.6-flash",
    cleanupError: null,
  });
  assert.equal(canUndoRetranscribe(7, d.storage), true);

  assert.equal(await undoRetranscribe(7, d), true);
  assert.deepEqual(d.saves[1], { id: 7, text: "versal pe deploy karo", rawText: "versal pe deploy karo" });
  assert.equal(canUndoRetranscribe(7, d.storage), false);
  assert.equal(await undoRetranscribe(7, d), false);
});

test("re-transcribe: cleanup failing keeps the Gemini transcript and says so", async () => {
  const { retranscribeDictationEntry } = await load();
  const d = deps({
    cleanup: async () => {
      throw new Error("groq down");
    },
  });
  const outcome = await retranscribeDictationEntry(ENTRY, d);
  assert.equal(outcome.text, "vercel pe deploy karo");
  assert.equal(outcome.cleanupError, "groq down");
  assert.equal(d.saves[0].text, "vercel pe deploy karo");
});

test("re-transcribe: a failed or empty Gemini answer changes nothing", async () => {
  const { retranscribeDictationEntry, canUndoRetranscribe } = await load();
  const failed = deps({
    transcribe: async () => ({ ok: false, error: "The audio for this dictation is no longer saved." }),
  });
  await assert.rejects(retranscribeDictationEntry(ENTRY, failed), /no longer saved/);
  assert.equal(failed.saves.length, 0);
  assert.equal(canUndoRetranscribe(7, failed.storage), false);

  const empty = deps({ transcribe: async () => ({ ok: true, result: { rawText: "  ", model: "m" } }) });
  await assert.rejects(retranscribeDictationEntry(ENTRY, empty), /no text/);
  assert.equal(empty.saves.length, 0);
});
