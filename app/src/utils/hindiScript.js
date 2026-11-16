// WhisperAsh: how Hindi / Hinglish dictation is written out.
//   roman      - Hinglish in Roman script, as typed in chats ("kal meeting hai")
//   devanagari - Hindi words in Devanagari, English words stay English
//   english    - translate everything to English
// Stored in localStorage ("hindiScriptPreference") and synced with settings.

export const HINDI_SCRIPT_PREFERENCES = ["roman", "devanagari", "english"];
export const HINDI_SCRIPT_KEY = "hindiScriptPreference";

export function getHindiScriptPreference() {
  try {
    const value = typeof localStorage !== "undefined" ? localStorage.getItem(HINDI_SCRIPT_KEY) : null;
    return HINDI_SCRIPT_PREFERENCES.includes(value) ? value : "roman";
  } catch {
    return "roman";
  }
}

// The Hindi rules apply when the spoken language is Hindi or auto-detected.
export function hindiRulesApply(language) {
  const base = (language || "auto").split("-")[0];
  return base === "auto" || base === "hi";
}

const COMMON =
  "Hindi filler words (matlab, na, haan toh, wo, yaar) are fillers only when they carry no meaning. " +
  "Self-corrections in Hindi (\"nahi nahi\", \"matlab\", \"sorry\", \"I mean\") keep only the corrected version.";

export function getHindiCleanupInstruction(preference = getHindiScriptPreference()) {
  if (preference === "english") {
    return (
      "LANGUAGE: The speaker may use Hindi, English or a mix of both (Hinglish), and the transcript may be in " +
      "Devanagari or Roman script. Write the cleaned result entirely in natural English, translating Hindi " +
      "faithfully without adding or dropping meaning. Keep names and technical terms. " +
      COMMON
    );
  }
  if (preference === "devanagari") {
    return (
      "LANGUAGE AND SCRIPT: The speaker may mix Hindi and English (Hinglish). Write Hindi words in Devanagari " +
      "script and keep English words in English (Latin) spelling, e.g. \"कल meeting है, मैं report भेज दूँगा।\" " +
      "If Hindi words were transcribed in Roman script, write them in Devanagari. Never translate: keep every " +
      "word in the language it was spoken in. If the transcript is only English, keep it English. " +
      COMMON
    );
  }
  return (
    "LANGUAGE AND SCRIPT (most important rule; it overrides everything below): The speaker mixes Hindi and " +
    "English (Hinglish). NEVER TRANSLATE. Every Hindi word stays Hindi, written in Roman (Latin) letters the way " +
    "people type Hinglish in chats; every English word stays English. Hindi output must still be Hindi you can " +
    "read aloud word for word, not an English paraphrase. If the transcript contains Devanagari, transliterate it " +
    "to Roman letters; English words written phonetically in Devanagari (e.g. इंग्लिश, यू कैन यूज़) go back to normal English " +
    "spelling (English, you can use). " +
    COMMON +
    "\nHINGLISH EXAMPLES:\n" +
    "Input: अपना फोन कनेक्ट कर दिया है, तो यू कैन यूज़ द फोन\n" +
    "Output: Apna phone connect kar diya hai, toh you can use the phone.\n" +
    "Input: मैं हिंदी में बोल रहा हूँ, बट ये इंग्लिश में क्यों translate कर रहा है?\n" +
    "Output: Main Hindi mein bol raha hoon, but ye English mein kyon translate kar raha hai?\n" +
    "Input: um bhai kal ki meeting cancel ho gayi hai so let's meet on friday theek hai\n" +
    "Output: Bhai, kal ki meeting cancel ho gayi hai, so let's meet on Friday, theek hai?\n" +
    "WRONG (translated, never do this): \"I am speaking in Hindi, but why is it translating to English?\""
  );
}

// The Hinglish rule goes first in the cleanup prompt for roman/devanagari, so the
// model reads it before the English-centric cleanup rules and examples.
export function hindiRuleGoesFirst(preference = getHindiScriptPreference()) {
  return preference === "roman" || preference === "devanagari";
}

// A short example in the target script steers Whisper's own output script.
export function getHindiWhisperBias(language, preference = getHindiScriptPreference()) {
  if (!hindiRulesApply(language)) return "";
  if (preference === "devanagari") return "हाँ, कल meeting है। मैं report भेज दूँगा।";
  if (preference === "roman") return "Haan, kal meeting hai. Main report bhej dunga, okay? Theek hai, let's check it.";
  return "";
}

export function setHindiScriptPreference(value) {
  if (!HINDI_SCRIPT_PREFERENCES.includes(value)) return;
  try {
    localStorage.setItem(HINDI_SCRIPT_KEY, value);
    window.dispatchEvent(new CustomEvent("whisperash-setting-changed", { detail: { key: HINDI_SCRIPT_KEY } }));
  } catch {
    /* storage unavailable */
  }
}

// Short dictations with nothing to clean (no fillers, no self-corrections, no
// script conversion needed) skip the LLM to save time.
const FILLER_OR_CORRECTION = /\b(um+|uh+|er+|hmm+|ah+|like|you know|i mean|no wait|wait|actually|scratch that|matlab|yaani|nahi nahi)\b/i;
const DEVANAGARI = /[ऀ-ॿ]/;
export function isShortCleanUtterance(text, preference = getHindiScriptPreference()) {
  const t = (text || "").trim();
  if (!t || preference === "english") return false;
  const words = t.split(/\s+/).filter(Boolean);
  if (words.length > 5) return false;
  if (FILLER_OR_CORRECTION.test(t)) return false;
  const hasDevanagari = DEVANAGARI.test(t);
  if (preference === "roman" && hasDevanagari) return false;
  if (preference === "devanagari" && !hasDevanagari && /[a-z]/i.test(t)) {
    // Could be Romanised Hindi that should become Devanagari; let the LLM decide.
    return false;
  }
  return true;
}

// Whisper's language for Hindi/Hinglish speech. Tested on real Hinglish
// recordings (2026-11-15): auto-detect picks Hindi and writes even the English
// words in Devanagari, which the cleanup model then sometimes translates to
// English. Forcing "en" plus the Roman example prompt makes Whisper write Roman
// Hinglish directly (English words kept, no translation, no extra request).
export function getWhisperLanguageFor(language, preference = getHindiScriptPreference()) {
  if (!hindiRulesApply(language)) return language;
  if (preference === "roman") return "en";
  if (preference === "devanagari") return "hi";
  return language === "auto" ? undefined : language;
}
