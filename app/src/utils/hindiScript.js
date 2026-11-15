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
    "LANGUAGE AND SCRIPT: The speaker may mix Hindi and English (Hinglish). Write Hindi words in Roman (Latin) " +
    "script the way people type Hinglish in chats, e.g. \"Kal meeting hai, main report bhej dunga.\" Keep English " +
    "words in normal English spelling. If the transcript contains Devanagari, transliterate it to Roman script. " +
    "Never translate Hindi to English or English to Hindi: keep every word in the language it was spoken in. " +
    COMMON
  );
}

// A short example in the target script steers Whisper's own output script.
export function getHindiWhisperBias(language, preference = getHindiScriptPreference()) {
  if (!hindiRulesApply(language)) return "";
  if (preference === "devanagari") return "हाँ, कल meeting है। मैं report भेज दूँगा।";
  if (preference === "roman") return "Haan, kal meeting hai. Main report bhej dunga, okay?";
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
