// WhisperAsh: protection against Whisper silently dropping parts of a dictation.
//
// On 2026-11-16 a 2 min 20 s dictation came back from Whisper as 73 words: it
// looped on its own prompt ("hai, hai. hai let check it hai, let's check it")
// and skipped about two minutes. Re-runs showed Whisper also leaves holes in long
// recordings without looping. So, with only a Groq key needed:
//   - recordings of 30 s or more are cut at pauses into ~25 s pieces (audioChunks.js),
//     transcribed in parallel, and joined: Whisper never sees long audio;
//   - every transcript (or piece) is checked, and a broken one is re-done with
//     Whisper Turbo and no prompt; Gemini is tried only after that, if a key is saved.
// The Android app has the same rules (android/.../TranscriptGuard.kt); keep them in step.

export const LONG_AUDIO_SECONDS = 30; // at or above: split into pieces
const MIN_CHECK_SECONDS = 12; // words-per-second check only on clips this long
const MIN_WORDS_PER_SECOND = 1.2; // normal speech is ~2-3; pieces are cut at pauses, so little silence

export function isLongRecording(seconds) {
  return Number.isFinite(seconds) && seconds >= LONG_AUDIO_SECONDS;
}

function words(text) {
  return (text || "")
    .toLowerCase()
    .split(/[^\p{L}\p{N}']+/u)
    .filter(Boolean);
}

export function wordCount(text) {
  return words(text).length;
}

// Returns { broken, reason } for a Whisper transcript of a clip `seconds` long.
// `example` is the script example sent in the prompt (its phrases must not repeat).
export function checkTranscript(text, seconds, example = "") {
  const w = words(text);
  // The same word 4+ times in a row ("hai hai hai hai") is a decoding loop, not speech.
  let run = 1;
  for (let i = 1; i < w.length; i++) {
    run = w[i] === w[i - 1] ? run + 1 : 1;
    if (run >= 4) return { broken: true, reason: `repeated_word (${w[i]})` };
  }
  // The same phrase over and over ("So, I have a few questions." x3) is invented text, not speech.
  const grams = (n) => {
    const seen = new Map();
    for (let i = 0; i + n <= w.length; i++) {
      const key = w.slice(i, i + n).join(" ");
      seen.set(key, (seen.get(key) || 0) + 1);
    }
    return seen;
  };
  // (Real speech repeats short phrases: "whether we know about their kids, whether we know about their religion".)
  for (const [phrase, count] of grams(6)) if (count >= 3) return { broken: true, reason: `repeated_phrase (${phrase})` };
  for (const [phrase, count] of grams(8)) if (count >= 2) return { broken: true, reason: `repeated_phrase (${phrase})` };
  // A three-word phrase of the prompt's example appearing twice means Whisper is reciting the prompt.
  const ex = words(example);
  if (ex.length >= 3) {
    const joined = ` ${w.join(" ")} `;
    for (let i = 0; i + 3 <= ex.length; i++) {
      const phrase = ` ${ex.slice(i, i + 3).join(" ")} `;
      const first = joined.indexOf(phrase);
      if (first >= 0 && joined.indexOf(phrase, first + 1) >= 0) {
        return { broken: true, reason: `prompt_echo (${phrase.trim()})` };
      }
    }
  }
  // Far too few words for the length: Whisper skipped part of the audio.
  if (Number.isFinite(seconds) && seconds >= MIN_CHECK_SECONDS && w.length / seconds < MIN_WORDS_PER_SECOND) {
    return { broken: true, reason: `too_few_words (${w.length} words in ${Math.round(seconds)} s)` };
  }
  return { broken: false, reason: "" };
}

// Decides between Whisper's first transcript and a redo made without the script example.
// A redo can turn Hinglish into English, so it only wins when clearly more complete, or when
// the original is invented text (a loop or an echo) rather than just short.
export function pickMoreComplete(original, retry, seconds) {
  if (!retry || !retry.trim()) return original;
  const invented = (r) => r.broken && !r.reason.startsWith("too_few_words");
  const first = checkTranscript(original, seconds);
  const second = checkTranscript(retry, seconds);
  // Invented text (a loop or an echo) loses to any redo that isn't invented itself.
  if (invented(first)) return invented(second) ? original : retry;
  if (second.broken) return original;
  return wordCount(retry) >= wordCount(original) * 1.25 ? retry : original;
}
