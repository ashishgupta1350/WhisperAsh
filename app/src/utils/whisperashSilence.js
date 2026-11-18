// WhisperAsh: Whisper "hears" stock phrases in near-silence ("Thank you.", "Thanks for watching!").
// A hotkey tapped on and straight off records a key click and room noise, which is enough to get
// one of these pasted. A transcript that is only such a phrase is dropped when the recording had
// almost no speech-like sound (gate: 100 ms windows from localSpeechGate.js).

const STOCK_PHRASES = new Set([
  "thank you",
  "thank you so much",
  "thank you very much",
  "thanks",
  "thanks for watching",
  "thank you for watching",
  "thanks for listening",
  "thank you for listening",
  "you",
  "bye",
  "bye bye",
  "subtitles by the amara org community",
  "please subscribe",
]);

// Up to 3 speech-like 100 ms windows (0.3 s): a key click or a breath, not someone saying the words.
const MAX_SPEECH_WINDOWS = 3;

export function normalizeStockPhrase(text) {
  return String(text || "")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();
}

/**
 * True when `text` is only a Whisper stock phrase and the speech gate saw almost no speech.
 * Without gate numbers (unknown recording) nothing is dropped.
 */
export function isSilenceHallucination(text, gate) {
  if (!gate || !gate.windowCount) return false;
  if (!STOCK_PHRASES.has(normalizeStockPhrase(text))) return false;
  return (gate.speechWindowCount || 0) <= MAX_SPEECH_WINDOWS;
}
