// WhisperAsh: account (Cloudflare Worker), speech engine (WhisperAsh account or own keys), sync
// status, Groq usage and the timing of the last dictation.
import { useEffect, useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { SectionHeader, SettingsPanel, SettingsPanelRow, SettingsRow } from "./ui/SettingsSection";
import type { WhisperAshState } from "../utils/whisperashSettingsSync";
import { useSettingsStore } from "../stores/settingsStore";

// Cleanup mode, the same two choices as the Android app (synced through `cleanupModel`).
const PRECISE_MODEL = "openai/gpt-oss-120b";
const FASTER_MODEL = "qwen/qwen3.8-27b";
const DEFAULT_SERVER = "https://whisperash-sync.whisperash-ashish.workers.dev";

function ago(ts: number | null) {
  if (!ts) return "never";
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return new Date(ts).toLocaleString();
}

// Server error codes in plain words (same wording as the Android app).
function friendly(code?: string) {
  const map: Record<string, string> = {
    invalid_credentials: "Wrong email or password.",
    too_many_attempts: "Too many tries. Wait 15 minutes.",
    email_taken: "That email already has an account. Sign in instead.",
    invalid_signup_code: "Wrong sign-up code.",
    password_too_short: "Password must be at least 8 characters.",
    invalid_email: "That email doesn't look right.",
    too_many_signups: "Too many new accounts from this network today. Try again tomorrow.",
    too_many_requests: "Too many requests from this network today. Try again tomorrow.",
    wrong_current_password: "The current password is wrong.",
  };
  return (code && map[code]) || code || "Something went wrong.";
}

function Meter({ label, used, limit, unit = "" }: { label: string; used: number; limit: number; unit?: string }) {
  const pct = Math.min(100, Math.round((used / limit) * 100));
  return (
    <div className="space-y-1">
      <div className="flex justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span>
          {Math.round(used).toLocaleString()}
          {unit} / {limit.toLocaleString()}
          {unit}
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-muted overflow-hidden">
        <div
          className={`h-full ${pct > 85 ? "bg-red-500" : pct > 60 ? "bg-amber-500" : "bg-emerald-500"}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

const TIMING_LABELS: Array<[string, string]> = [
  ["stopMs", "Stop recording"],
  ["uploadAndWhisperMs", "Upload + Whisper"],
  ["cleanupMs", "Cleanup (LLM)"],
  ["pasteMs", "Paste"],
  ["totalAfterReleaseMs", "Key release → text"],
];

export default function WhisperAshAccountPanel() {
  const bridge = window.whisperash;
  const [state, setState] = useState<WhisperAshState | null>(null);
  const [serverUrl, setServerUrl] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [signupCode, setSignupCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [forgotMode, setForgotMode] = useState(false);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [geminiSaved, setGeminiSaved] = useState(false);
  const [geminiInput, setGeminiInput] = useState("");
  const api = (window as unknown as { electronAPI?: Record<string, (...a: unknown[]) => Promise<unknown>> }).electronAPI;
  const cleanupModel = useSettingsStore((s) => s.cleanupModel);
  const setCleanupModel = useSettingsStore((s) => s.setCleanupModel);

  useEffect(() => {
    api?.getGeminiKey?.().then((k) => setGeminiSaved(!!k));
  }, [api]);

  const saveGemini = async (value: string) => {
    await api?.saveGeminiKey?.(value.trim());
    const k = await api?.getGeminiKey?.();
    setGeminiSaved(!!k);
    setGeminiInput("");
  };

  useEffect(() => {
    if (!bridge) return;
    bridge.getState().then((s) => {
      setState(s);
      setServerUrl(s.serverUrl);
      setEmail(s.email);
      setShowAdvanced(!!s.serverUrl && s.serverUrl !== DEFAULT_SERVER);
    });
    bridge.refreshPlan?.().then((r) => r?.state && setState(r.state));
    const off = bridge.onState(setState);
    const tick = setInterval(() => bridge.getState().then(setState), 15000);
    return () => {
      off();
      clearInterval(tick);
    };
  }, [bridge]);

  if (!bridge) return <div className="text-sm">WhisperAsh bridge not available.</div>;

  const run = async (fn: () => Promise<{ ok: boolean; error?: string; state: WhisperAshState }>, okText: string) => {
    setBusy(true);
    setMessage(null);
    try {
      const res = await fn();
      setState(res.state);
      setMessage(res.ok ? okText : friendly(res.error));
      if (res.ok) {
        setPassword("");
        setCurrentPassword("");
        setNewPassword("");
      }
    } finally {
      setBusy(false);
    }
  };

  const usage = state?.usage;
  const timing = state?.lastTiming;
  const account = state?.engine?.account ?? true;
  const plan = state?.plan;

  return (
    <div className="space-y-5">
      <SectionHeader
        title="WhisperAsh account"
        description="Sign in with your email and password: dictation then works with no API keys (a daily word allowance), and your history, personal dictionary and settings sync across your PCs and phone."
      />
      <SettingsPanel>
        {state?.signedIn ? (
          <>
            <SettingsPanelRow>
              <SettingsRow label="Signed in as" description={state.email}>
                <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => bridge.logout(), "Signed out")}>
                  Sign out
                </Button>
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow
                label="Sync"
                description={`Status: ${state.state}. Last sync ${ago(state.lastSyncAt)}. Waiting to upload: ${state.pending}.${
                  state.lastError ? ` ${state.lastError}` : ""
                }`}
              >
                <Button size="sm" disabled={busy} onClick={() => run(() => bridge.syncNow(), "Synced")}>
                  Sync now
                </Button>
              </SettingsRow>
            </SettingsPanelRow>
          </>
        ) : forgotMode ? (
          <>
            <SettingsPanelRow>
              <SettingsRow
                label="Forgot password"
                description="Send a reset request. The WhisperAsh admin will reset your password and send you a temporary one; sign in with it, then change it here."
              >
                <Input className="w-80 h-8 text-xs" value={email} placeholder="Email" onChange={(e) => setEmail(e.target.value)} />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="" description={message || ""}>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    disabled={busy || !email.includes("@")}
                    onClick={() =>
                      run(
                        () => bridge.forgotPassword({ serverUrl: showAdvanced ? serverUrl : "", email }),
                        "Request sent. The admin will send you a temporary password."
                      )
                    }
                  >
                    Send reset request
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => { setForgotMode(false); setMessage(null); }}>
                    Back to sign in
                  </Button>
                </div>
              </SettingsRow>
            </SettingsPanelRow>
          </>
        ) : (
          <>
            <SettingsPanelRow>
              <SettingsRow label="Email">
                <Input className="w-80 h-8 text-xs" value={email} onChange={(e) => setEmail(e.target.value)} />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="Password" description="At least 8 characters. Stored only as a hash on the server.">
                <Input
                  className="w-80 h-8 text-xs"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="" description={message || ""}>
                <div className="flex gap-2 items-center">
                  <Button
                    size="sm"
                    disabled={busy || !email || password.length < 8}
                    onClick={() =>
                      run(() => bridge.login({ serverUrl: showAdvanced ? serverUrl : "", email, password }), "Signed in. Syncing…")
                    }
                  >
                    Sign in
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || !email || password.length < 8}
                    onClick={() =>
                      run(
                        () => bridge.register({ serverUrl: showAdvanced ? serverUrl : "", email, password, signupCode }),
                        "Account created. Syncing…"
                      )
                    }
                  >
                    Create account
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => { setForgotMode(true); setMessage(null); }}>
                    Forgot password?
                  </Button>
                </div>
              </SettingsRow>
            </SettingsPanelRow>
          </>
        )}
        {state?.signedIn && message ? (
          <SettingsPanelRow>
            <div className="text-xs text-muted-foreground">{message}</div>
          </SettingsPanelRow>
        ) : null}
        {!state?.signedIn ? (
          <SettingsPanelRow>
            <div className="w-full space-y-2 py-1">
              <Button size="sm" variant="ghost" onClick={() => setShowAdvanced(!showAdvanced)}>
                {showAdvanced ? "Hide advanced" : "Advanced (own server)"}
              </Button>
              {showAdvanced ? (
                <div className="space-y-2">
                  <SettingsRow label="Server URL" description="Only change this if you run your own WhisperAsh server (it is open source).">
                    <Input
                      className="w-80 h-8 text-xs"
                      value={serverUrl}
                      placeholder={DEFAULT_SERVER}
                      onChange={(e) => setServerUrl(e.target.value)}
                      disabled={busy}
                    />
                  </SettingsRow>
                  <SettingsRow label="Sign-up code" description="Only if your server asks for one.">
                    <Input
                      className="w-80 h-8 text-xs"
                      type="password"
                      value={signupCode}
                      onChange={(e) => setSignupCode(e.target.value)}
                    />
                  </SettingsRow>
                </div>
              ) : null}
            </div>
          </SettingsPanelRow>
        ) : null}
      </SettingsPanel>

      <SectionHeader
        title="Speech engine"
        description="WhisperAsh account: no keys needed, a daily word allowance. My own keys: this PC calls Groq (and Gemini) directly with your keys, no WhisperAsh limit."
      />
      <SettingsPanel>
        <SettingsPanelRow>
          <SettingsRow
            label="Use"
            description={
              account
                ? state?.signedIn
                  ? "WhisperAsh account"
                  : "WhisperAsh account: sign in above to start dictating"
                : "My own keys (Groq key under AI models)"
            }
          >
            <div className="flex gap-2">
              <Button
                size="sm"
                variant={account ? "default" : "outline"}
                disabled={busy}
                onClick={() => run(() => bridge.setEngine("account"), "Using your WhisperAsh account.")}
              >
                WhisperAsh account
              </Button>
              <Button
                size="sm"
                variant={!account ? "default" : "outline"}
                disabled={busy}
                onClick={() => run(() => bridge.setEngine("own"), "Using your own keys.")}
              >
                My own keys
              </Button>
            </div>
          </SettingsRow>
        </SettingsPanelRow>
        {account && state?.signedIn ? (
          <SettingsPanelRow>
            <div className="w-full py-1">
              {plan ? (
                plan.status === "blocked" ? (
                  <div className="text-xs">Your account is paused. Contact the WhisperAsh admin.</div>
                ) : plan.ownKeys?.groq ? (
                  <div className="text-xs">No word limit: your account uses a key the admin set for you.</div>
                ) : (
                  <div className="rounded-lg bg-muted/50 p-3">
                    <div className="text-xs text-muted-foreground">
                      Words left {plan.period === "week" ? "this week" : "today"}
                    </div>
                    <div className="text-2xl font-semibold">
                      {plan.left.toLocaleString()}{" "}
                      <span className="text-sm font-normal text-muted-foreground">of {plan.limit.toLocaleString()}</span>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Resets {new Date(plan.resetsAt).toLocaleString([], { dateStyle: "medium", timeStyle: "short" })}.
                      Need more? Ask the WhisperAsh admin for a bigger package.
                    </div>
                  </div>
                )
              ) : (
                <div className="text-xs text-muted-foreground">Checking your allowance…</div>
              )}
            </div>
          </SettingsPanelRow>
        ) : null}
      </SettingsPanel>

      {state?.signedIn ? (
        <>
          <SectionHeader title="Change password" description="After an admin reset, sign in with the temporary password and set your own here. Your other devices are signed out." />
          <SettingsPanel>
            <SettingsPanelRow>
              <SettingsRow label="Current (or temporary) password">
                <Input
                  className="w-80 h-8 text-xs"
                  type="password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="New password" description="At least 8 characters.">
                <div className="flex gap-2">
                  <Input
                    className="w-64 h-8 text-xs"
                    type="password"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                  />
                  <Button
                    size="sm"
                    disabled={busy || !currentPassword || newPassword.length < 8}
                    onClick={() =>
                      run(
                        () => bridge.changePassword({ current: currentPassword, password: newPassword }),
                        "Password changed. Your other devices were signed out."
                      )
                    }
                  >
                    Change
                  </Button>
                </div>
              </SettingsRow>
            </SettingsPanelRow>
          </SettingsPanel>
        </>
      ) : null}

      {!account ? (
        <>
          <SectionHeader
            title="Backup engine (Gemini)"
            description="If Groq fails (daily limit, bad key, outage), WhisperAsh sends the same recording to Google Gemini automatically. Free key: aistudio.google.com/apikey. Stored only on this PC, encrypted."
          />
          <SettingsPanel>
            <SettingsPanelRow>
              <SettingsRow label="Gemini API key" description={geminiSaved ? "Saved: backup is on." : "Not set: backup is off."}>
                <div className="flex gap-2">
                  <Input
                    className="w-64 h-8 text-xs"
                    type="password"
                    placeholder={geminiSaved ? "•••••••• (saved)" : "Paste Gemini API key"}
                    value={geminiInput}
                    onChange={(e) => setGeminiInput(e.target.value)}
                  />
                  <Button size="sm" disabled={!geminiInput.trim()} onClick={() => saveGemini(geminiInput)}>
                    Save
                  </Button>
                  {geminiSaved ? (
                    <Button size="sm" variant="outline" onClick={() => saveGemini("")}>
                      Remove
                    </Button>
                  ) : null}
                </div>
              </SettingsRow>
            </SettingsPanelRow>
          </SettingsPanel>
        </>
      ) : null}

      <SectionHeader
        title="Cleanup mode"
        description="Faster (default) uses Qwen 3.8 27B: keeps your wording, about 0.3–0.5 s quicker. Precise uses GPT-OSS 120B: stronger on Hindi and Hinglish, but can shorten long English dictations. Each has its own free Groq allowance. Synced with your other devices."
      />
      <SettingsPanel>
        <SettingsPanelRow>
          <SettingsRow
            label="Mode"
            description={
              cleanupModel === FASTER_MODEL
                ? "Faster (Qwen 3.8 27B), the default"
                : cleanupModel === PRECISE_MODEL
                  ? "Precise (GPT-OSS 120B)"
                  : `Other model: ${cleanupModel || "default"}`
            }
          >
            <div className="flex gap-2">
              <Button
                size="sm"
                variant={cleanupModel === FASTER_MODEL ? "default" : "outline"}
                onClick={() => setCleanupModel(FASTER_MODEL)}
              >
                Faster (default)
              </Button>
              <Button
                size="sm"
                variant={cleanupModel === PRECISE_MODEL ? "default" : "outline"}
                onClick={() => setCleanupModel(PRECISE_MODEL)}
              >
                Precise
              </Button>
            </div>
          </SettingsRow>
        </SettingsPanelRow>
      </SettingsPanel>

      {!account ? (
        <>
          <SectionHeader
            title="Groq free-tier usage (this PC)"
            description="Counted locally. Each Whisper request is billed as at least 10 seconds of audio. Limits from console.groq.com/docs/rate-limits."
          />
          <SettingsPanel>
            {usage?.words ? (
              <SettingsPanelRow>
                <div className="w-full py-1">
                  <div className="grid grid-cols-2 gap-3">
                    <div className="rounded-lg bg-muted/50 p-3">
                      <div className="text-xs text-muted-foreground">Free words left today</div>
                      <div className="text-2xl font-semibold">≈ {usage.words.leftToday.toLocaleString()}</div>
                      <div className="text-xs text-muted-foreground">
                        You've dictated {usage.words.dictatedToday.toLocaleString()} today
                      </div>
                    </div>
                    <div className="rounded-lg bg-muted/50 p-3">
                      <div className="text-xs text-muted-foreground">Free words left this month</div>
                      <div className="text-2xl font-semibold">≈ {usage.words.leftMonth.toLocaleString()}</div>
                      <div className="text-xs text-muted-foreground">
                        You've dictated {usage.words.dictatedMonth.toLocaleString()} this month
                      </div>
                    </div>
                  </div>
                  <div className="text-[11px] text-muted-foreground mt-2">
                    Estimate: Groq's free limits reset on a rolling 24 hours (no monthly cap), about{" "}
                    {usage.words.fullDay.toLocaleString()} words a day at your pace ({usage.words.perSecond} words per
                    billed second; short clips are billed as 10 s).
                  </div>
                </div>
              </SettingsPanelRow>
            ) : null}
            {usage ? (
              <SettingsPanelRow>
                <div className="w-full space-y-3 py-1">
                  <Meter label="Whisper requests, last minute" used={usage.stt.minute.requests} limit={usage.limits.stt.rpm} />
                  <Meter label="Whisper requests, last 24 h" used={usage.stt.day.requests} limit={usage.limits.stt.rpd} />
                  <Meter label="Audio, last hour" used={usage.stt.hour.seconds} limit={usage.limits.stt.audioPerHour} unit=" s" />
                  <Meter label="Audio, last 24 h" used={usage.stt.day.seconds} limit={usage.limits.stt.audioPerDay} unit=" s" />
                  {usage.stt.groq ? (
                    <div className="text-xs text-muted-foreground">
                      Groq reports {usage.stt.groq.remaining.toLocaleString()}
                      {usage.stt.groq.lim ? ` of ${usage.stt.groq.lim.toLocaleString()}` : ""} transcription requests left
                      today ({usage.stt.model})
                      {usage.stt.groqAudioHour
                        ? `, and ${usage.stt.groqAudioHour.remaining.toLocaleString()} audio seconds left this hour`
                        : ""}
                      .
                    </div>
                  ) : null}
                  {(usage.llm.models || []).length === 0 ? (
                    <div className="text-xs text-muted-foreground">No cleanups in the last 24 hours.</div>
                  ) : null}
                  {(usage.llm.models || []).map((m) => {
                    // Real tokens from Groq's responses; each model has its own daily allowance.
                    const perCleanup = m.requests > 0 ? Math.max(1, Math.round(m.tokens / m.requests)) : 0;
                    const byTokens = perCleanup ? Math.floor((usage.limits.llm.tpd - m.tokens) / perCleanup) : 0;
                    const byRequests = m.groq ? m.groq.remaining : usage.limits.llm.rpd - m.requests;
                    const left = Math.max(0, Math.min(byTokens, byRequests));
                    return (
                      <div key={m.model} className="space-y-1">
                        <Meter label={`Cleanup tokens, last 24 h (${m.model})`} used={m.tokens} limit={usage.limits.llm.tpd} />
                        <div className="text-xs text-muted-foreground">
                          {m.requests.toLocaleString()} cleanups, ~{perCleanup.toLocaleString()} tokens each. About{" "}
                          {left.toLocaleString()} more cleanups left today on this model
                          {m.groq ? ` (Groq reports ${m.groq.remaining.toLocaleString()} requests left)` : ""}.
                        </div>
                      </div>
                    );
                  })}
                  {usage.llm.since && Date.now() - usage.llm.since < 864e5 ? (
                    <div className="text-[11px] text-muted-foreground">
                      Cleanup totals count real Groq numbers since{" "}
                      {new Date(usage.llm.since).toLocaleString([], { dateStyle: "medium", timeStyle: "short" })} (earlier
                      figures were estimates), so they cover less than 24 hours until then.
                    </div>
                  ) : null}
                  <div className="text-[11px] text-muted-foreground">
                    Token counts are Groq's own, recorded on this PC; Groq's "requests left" also counts other devices using
                    the same key. Limits refill gradually over a rolling 24 hours. If a cleanup model runs out, GPT-OSS 20B and
                    then the raw transcript take over.
                  </div>
                </div>
              </SettingsPanelRow>
            ) : null}
          </SettingsPanel>
        </>
      ) : null}

      <SectionHeader title="Last dictation timing" description="Time per stage after you release the hotkey." />
      <SettingsPanel>
        <SettingsPanelRow>
          {timing ? (
            <div className="w-full grid grid-cols-2 gap-y-1 text-xs py-1">
              {TIMING_LABELS.filter(([k]) => typeof timing[k] === "number").map(([k, label]) => (
                <div key={k} className="contents">
                  <span className="text-muted-foreground">{label}</span>
                  <span className="text-right font-mono">{timing[k]} ms</span>
                </div>
              ))}
              <span className="text-muted-foreground">Audio length</span>
              <span className="text-right font-mono">{timing.audioSeconds ?? "?"} s</span>
              <span className="text-muted-foreground">Models</span>
              <span className="text-right font-mono">
                {String(timing.sttModel ?? "")} {timing.cleanupModel ? `+ ${timing.cleanupModel}` : ""}
              </span>
            </div>
          ) : (
            <div className="text-xs text-muted-foreground">No dictation yet.</div>
          )}
        </SettingsPanelRow>
      </SettingsPanel>
    </div>
  );
}
