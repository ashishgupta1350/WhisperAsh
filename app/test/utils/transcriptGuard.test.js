const test = require("node:test");
const assert = require("node:assert/strict");

let g;

test.before(async () => {
  const path = require("node:path");
  const { pathToFileURL } = require("node:url");
  const root = process.env.OPENWHISPR_TEST_SOURCE || path.resolve(__dirname, "../..");
  g = await import(pathToFileURL(path.join(root, "src/utils/transcriptGuard.js")));
});

const EXAMPLE = "Haan, kal meeting hai. Main report bhej dunga, okay? Theek hai, let's check it.";

// What Whisper returned for the 2 min 20 s dictation of 2026-11-16 13:33.
const LOOPED =
  "So, I have shared three experts with you. One contains our customer data, second contains our booking data, " +
  "third contains our receipt data. Now, I want you to analyze all these three data and segment each customer into " +
  "various categories so I can create customer hai, hai. hai let check it hai, let's check it. hai, right now before " +
  "you create any analysis, I want you to just organize the data and present";

const manyWords = (n) => Array.from({ length: n }, (_, i) => `w${i}`).join(" ");

test("the 13:33 dictation is caught", () => {
  const r = g.checkTranscript(LOOPED, 139.6, EXAMPLE);
  assert.equal(r.broken, true);
  assert.match(r.reason, /too_few_words/);
});

test("loops, invented repeats and prompt echoes are caught", () => {
  assert.equal(g.checkTranscript("ok hai hai hai hai done", 5, EXAMPLE).broken, true);
  assert.equal(g.checkTranscript("theek hai let's check it and theek hai let's check it", 8, EXAMPLE).broken, true);
  const loop = "So, I have a few questions. So, I have a few questions. So, I have a few questions.";
  assert.match(g.checkTranscript(loop, 28).reason, /repeated_phrase/);
  const twice = "So, this is the address of the person who is living in the area. So, this is the address of the person who is living in the area.";
  assert.match(g.checkTranscript(twice, 20).reason, /repeated_phrase/);
});

test("normal dictations pass", () => {
  assert.equal(g.checkTranscript("Thanks, see you tomorrow.", 2.7, EXAMPLE).broken, false);
  assert.equal(
    g.checkTranscript("Checking ki recording, check check ki recording ho rahi hai ki nahi?", 7, EXAMPLE).broken,
    false
  );
  assert.equal(g.checkTranscript(manyWords(280), 139.5, EXAMPLE).broken, false);
  assert.equal(g.checkTranscript("Okay, theek hai, let's check it.", 3, EXAMPLE).broken, false);
  assert.equal(
    g.checkTranscript("whether we know about their kids, whether we know about their religion, whether we know about their home state", 8).broken,
    false
  );
});

test("length thresholds", () => {
  assert.equal(g.isLongRecording(139.6), true);
  assert.equal(g.isLongRecording(20), false);
  assert.equal(g.isLongRecording(30), true);
  assert.equal(g.isLongRecording(29.9), false);
  assert.equal(g.isLongRecording(null), false);
});

test("a redo only replaces the original when clearly better", () => {
  const full = manyWords(280);
  assert.equal(g.pickMoreComplete(LOOPED, full, 139.6), full);
  assert.equal(g.pickMoreComplete("a complete short sentence here", "", 5), "a complete short sentence here");
  // A Hinglish piece is not swapped for an English translation of the same length.
  const hinglish = "Bhai tum kya kar rahe ho mereko 5 lakh ki payment chahiye jaldi se bhej do";
  const english = "Bro what are you doing I need a payment of 5 lakhs send it quickly please now";
  assert.equal(g.pickMoreComplete(hinglish, english, 6), hinglish);
  // An invented loop is replaced by the redo.
  const loop = "So, I have a few questions. So, I have a few questions. So, I have a few questions.";
  const real = "their demands on time through the receipt data";
  assert.equal(g.pickMoreComplete(loop, real, 28), real);
});
