const test = require("node:test");
const assert = require("node:assert/strict");

let a;

test.before(async () => {
  const path = require("node:path");
  const { pathToFileURL } = require("node:url");
  const root = process.env.OPENWHISPR_TEST_SOURCE || path.resolve(__dirname, "../..");
  a = await import(pathToFileURL(path.join(root, "src/utils/audioChunks.js")));
});

const RATE = 16000;

// Speech-like noise with a quiet gap at each of `pauses` (seconds).
function fakeSpeech(seconds, pauses) {
  const pcm = new Float32Array(Math.round(seconds * RATE));
  for (let i = 0; i < pcm.length; i++) pcm[i] = Math.sin(i * 0.07) * 0.4 + (Math.random() - 0.5) * 0.2;
  for (const p of pauses) pcm.fill(0, Math.round((p - 0.25) * RATE), Math.round((p + 0.25) * RATE));
  return pcm;
}

test("short audio stays one piece", () => {
  assert.deepEqual(a.splitAtPauses(new Float32Array(29 * RATE), RATE), [[0, 29 * RATE]]);
});

test("long audio is cut at the pauses, every piece at most 30 s, nothing lost", () => {
  const pcm = fakeSpeech(140, [23, 47, 74, 99, 121]);
  const pieces = a.splitAtPauses(pcm, RATE);
  assert.ok(pieces.length >= 5 && pieces.length <= 7, `got ${pieces.length} pieces`);
  assert.equal(pieces[0][0], 0);
  assert.equal(pieces[pieces.length - 1][1], pcm.length);
  for (let i = 0; i < pieces.length; i++) {
    const [s, e] = pieces[i];
    assert.ok(e - s <= 30 * RATE, `piece ${i} is ${(e - s) / RATE} s`);
    if (i > 0) assert.equal(s, pieces[i - 1][1], "pieces must touch");
  }
  // The first cut lands in the quiet gap at 23 s.
  assert.ok(Math.abs(pieces[0][1] / RATE - 23) < 0.3, `first cut at ${pieces[0][1] / RATE} s`);
});

test("WAV header and length", () => {
  const wav = a.encodeWav(new Float32Array([0, 0.5, -0.5, 1]), RATE);
  assert.equal(wav.length, 44 + 8);
  assert.equal(String.fromCharCode(...wav.slice(0, 4)), "RIFF");
  assert.equal(String.fromCharCode(...wav.slice(8, 12)), "WAVE");
});

test("pieces join without doubling a word at the cut", () => {
  assert.equal(a.joinPieces(["so I can create customer", "customer personas for each project", ""]),
    "so I can create customer personas for each project");
  assert.equal(a.joinPieces(["Hello there.", "How are you?"]), "Hello there. How are you?");
});
