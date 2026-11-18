// WhisperAsh: first-run setup without an API key. Shown on the "bring your own key" steps:
// signing in to a WhisperAsh account makes dictation run through the WhisperAsh server (the
// owner's Groq/Gemini keys, with a daily word allowance), so the key step can be skipped.
import { useEffect, useState } from "react";
import type { WhisperAshState } from "../../utils/whisperashSettingsSync";

const ERRORS: Record<string, string> = {
  invalid_credentials: "Wrong email or password.",
  too_many_attempts: "Too many tries. Wait 15 minutes.",
  email_taken: "That email already has an account. Sign in instead.",
  password_too_short: "Password must be at least 8 characters.",
  invalid_email: "That email doesn't look right.",
  too_many_signups: "Too many new accounts from this network today. Try again tomorrow.",
  too_many_requests: "Too many requests from this network today. Try again tomorrow.",
};

export default function WhisperAshSignInCard({ onContinue }: { onContinue: () => void }) {
  const bridge = window.whisperash;
  const [state, setState] = useState<WhisperAshState | null>(null);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!bridge) return;
    bridge.getState().then((s) => {
      setState(s);
      setEmail(s.email || "");
    });
    return bridge.onState(setState);
  }, [bridge]);

  if (!bridge) return null;

  const run = async (fn: () => Promise<{ ok: boolean; error?: string; state: WhisperAshState }>, ok: string) => {
    setBusy(true);
    setMessage("");
    try {
      const res = await fn();
      setState(res.state);
      setMessage(res.ok ? ok : ERRORS[res.error || ""] || res.error || "Something went wrong.");
      if (res.ok) setPassword("");
    } finally {
      setBusy(false);
    }
  };

  const continueWithAccount = async () => {
    setBusy(true);
    try {
      await bridge.setEngine("account");
      onContinue();
    } finally {
      setBusy(false);
    }
  };

  const field =
    "h-8 w-full rounded-md border border-[var(--onboarding-control-border)] bg-[var(--onboarding-surface)] px-2 text-xs text-[var(--onboarding-text-primary)]";
  const button =
    "h-8 rounded-md px-3 text-xs font-medium disabled:opacity-50 bg-[var(--onboarding-accent)] text-[var(--onboarding-accent-foreground)]";
  const link = "text-xs underline text-[var(--onboarding-text-primary)] disabled:opacity-50";

  return (
    <div className="rounded-lg border border-[var(--onboarding-control-border)] p-3 space-y-2">
      <div className="text-xs font-semibold text-[var(--onboarding-text-primary)]">
        No API key? Use your WhisperAsh account
      </div>
      {state?.signedIn ? (
        <>
          <div className="text-xs text-[var(--onboarding-text-primary)]">
            Signed in as {state.email}. Dictation will use your WhisperAsh account (free words every day).
          </div>
          <button type="button" className={button} disabled={busy} onClick={continueWithAccount}>
            Continue with WhisperAsh account
          </button>
        </>
      ) : (
        <>
          <input className={field} placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <input
            className={field}
            type="password"
            placeholder="Password (8+ characters)"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <div className="flex flex-wrap items-center gap-3">
            <button
              type="button"
              className={button}
              disabled={busy || !email || password.length < 8}
              onClick={() => run(() => bridge.login({ serverUrl: "", email, password }), "Signed in.")}
            >
              Sign in
            </button>
            <button
              type="button"
              className={link}
              disabled={busy || !email || password.length < 8}
              onClick={() => run(() => bridge.register({ serverUrl: "", email, password }), "Account created.")}
            >
              Create account
            </button>
            <button
              type="button"
              className={link}
              disabled={busy || !email.includes("@")}
              onClick={() =>
                run(
                  () => bridge.forgotPassword({ email }),
                  "Reset request sent. The admin will send you a temporary password."
                )
              }
            >
              Forgot password?
            </button>
          </div>
        </>
      )}
      {message ? <div className="text-xs text-[var(--onboarding-text-primary)]">{message}</div> : null}
    </div>
  );
}
