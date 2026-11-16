// WhisperAsh: the signed-in sync account's email, cached for the cleanup prompt.
// Read through the preload bridge (works in every window); refreshed at most once a minute.
let email = "";
let fetchedAt = 0;

export function getAccountEmail() {
  return email;
}

export async function refreshAccountEmail() {
  if (Date.now() - fetchedAt < 60_000) return email;
  fetchedAt = Date.now();
  try {
    const state = await window.whisperash?.getState?.();
    email = state?.signedIn && typeof state.email === "string" ? state.email : "";
  } catch {
    /* bridge unavailable: keep the last value */
  }
  return email;
}
