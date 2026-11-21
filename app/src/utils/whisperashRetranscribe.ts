// WhisperAsh "Re-transcribe (better model)" for a dictation history entry.
// Main transcribes the saved audio with Gemini 3.6 Flash (minimal thinking); this cleans the
// result up exactly like a normal dictation (Groq openai/gpt-oss-120b, the dictation cleanup
// prompt and Hinglish rules) and saves it, keeping the previous text so it can be undone.
// Never the OpenWhispr cloud. Dependencies are injected so it is unit-tested
// (test/helpers/whisperashRetranscribe.test.js).

export const RETRANSCRIBE_CLEANUP_MODEL = "openai/gpt-oss-120b";
const UNDO_KEY = "whisperashRetranscribeUndo";

export interface RetranscribeEntry {
  id: number;
  text: string;
  raw_text: string | null;
}

export interface RetranscribeDeps {
  /** IPC: Gemini 3.6 Flash on the saved audio. */
  transcribe: (id: number) => Promise<{ ok: boolean; result?: { rawText: string; model: string }; error?: string }>;
  /** Normal dictation cleanup with the given model on Groq; throws on failure. */
  cleanup: (rawText: string, model: string) => Promise<string>;
  /** IPC: update-transcription-text. */
  save: (id: number, text: string, rawText: string | null) => Promise<{ success: boolean }>;
  storage?: Pick<Storage, "getItem" | "setItem"> | null;
}

export interface RetranscribeOutcome {
  text: string;
  rawText: string;
  model: string;
  /** Cleanup failed: the entry holds Gemini's transcript as is. */
  cleanupError: string | null;
}

type UndoMap = Record<string, { text: string; raw_text: string | null; at: number }>;

function readUndo(storage: RetranscribeDeps["storage"]): UndoMap {
  try {
    return JSON.parse(storage?.getItem(UNDO_KEY) || "{}") || {};
  } catch {
    return {};
  }
}

function writeUndo(storage: RetranscribeDeps["storage"], map: UndoMap) {
  try {
    // Keep the 50 most recent.
    const entries = Object.entries(map)
      .sort((a, b) => b[1].at - a[1].at)
      .slice(0, 50);
    storage?.setItem(UNDO_KEY, JSON.stringify(Object.fromEntries(entries)));
  } catch {
    /* Undo is a convenience; the new text is saved either way */
  }
}

const hasText = (s: string | null | undefined) => !!s && s.trim().length > 0;

export async function retranscribeDictationEntry(
  entry: RetranscribeEntry,
  deps: RetranscribeDeps
): Promise<RetranscribeOutcome> {
  const res = await deps.transcribe(entry.id);
  if (!res.ok || !res.result) throw new Error(res.error || "Re-transcribe failed.");
  const rawText = res.result.rawText.trim();
  if (!hasText(rawText)) throw new Error("Gemini returned no text for this recording.");

  let text = rawText;
  let cleanupError: string | null = null;
  try {
    const cleaned = await deps.cleanup(rawText, RETRANSCRIBE_CLEANUP_MODEL);
    if (hasText(cleaned)) text = cleaned.trim();
  } catch (err) {
    cleanupError = (err as Error)?.message || "Cleanup failed";
  }

  // Remember the text from before, then replace it.
  const undo = readUndo(deps.storage);
  undo[String(entry.id)] = { text: entry.text, raw_text: entry.raw_text, at: Date.now() };
  writeUndo(deps.storage, undo);
  const saved = await deps.save(entry.id, text, rawText);
  if (!saved.success) throw new Error("Couldn't save the new text.");
  return { text, rawText, model: res.result.model, cleanupError };
}

export function canUndoRetranscribe(id: number, storage: RetranscribeDeps["storage"]): boolean {
  return !!readUndo(storage)[String(id)];
}

export async function undoRetranscribe(
  id: number,
  deps: Pick<RetranscribeDeps, "save" | "storage">
): Promise<boolean> {
  const undo = readUndo(deps.storage);
  const previous = undo[String(id)];
  if (!previous) return false;
  const saved = await deps.save(id, previous.text, previous.raw_text);
  if (!saved.success) return false;
  delete undo[String(id)];
  writeUndo(deps.storage, undo);
  return true;
}
