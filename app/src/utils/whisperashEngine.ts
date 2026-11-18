// WhisperAsh account mode (renderer side).
//
// "WhisperAsh account": dictation goes through the WhisperAsh server (the Cloudflare Worker), which
// calls Groq/Gemini with the owner's keys and counts words against the account's allowance. The
// server speaks Groq's own API at <server>/groq/openai/v1, so only the base URL and the Bearer token
// change; everything else (pieces, guard, cleanup prompts) stays the same.
// "My own keys": the app calls api.groq.com directly with the user's key, as before.
//
// The main process owns the session token and the choice (whisperashSync.js); this module keeps a
// copy for the synchronous URL/key lookups and refreshes it whenever the account state changes.
// Explicit extension and erasable TypeScript only: transcriptionRoute.ts imports this module and
// is also loaded by the main process (where there is no window and account mode is always off).
import { API_ENDPOINTS } from "../config/constants.ts";

type Engine = { account: boolean; base: string | null; token: string | null };

let engine: Engine = { account: false, base: null, token: null };

type WhisperAshEngineBridge = {
  getEngine?: () => Promise<Engine>;
  onState?: (callback: (state: unknown) => void) => () => void;
};

function bridge(): WhisperAshEngineBridge | undefined {
  if (typeof window === "undefined") return undefined;
  return (window as unknown as { whisperash?: WhisperAshEngineBridge }).whisperash;
}

export async function refreshWhisperAshEngine(): Promise<void> {
  try {
    const next = await bridge()?.getEngine?.();
    if (next) engine = next;
  } catch {
    /* keep the last known engine */
  }
}

/** True when dictation should go through the signed-in WhisperAsh account. */
export function whisperAshAccountActive(): boolean {
  return !!(engine.account && engine.token && engine.base);
}

/** Groq's OpenAI-compatible base: the WhisperAsh gateway in account mode, else api.groq.com. */
export function getGroqBase(): string {
  return whisperAshAccountActive() ? `${engine.base}/groq/openai/v1` : API_ENDPOINTS.GROQ_BASE;
}

/** The Bearer value for Groq requests in account mode (the session token), else null. */
export function getAccountToken(): string | null {
  return whisperAshAccountActive() ? engine.token : null;
}

if (typeof window !== "undefined") {
  void refreshWhisperAshEngine();
  bridge()?.onState?.(() => void refreshWhisperAshEngine());
}
