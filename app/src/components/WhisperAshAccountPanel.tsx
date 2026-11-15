// WhisperAsh: account (Cloudflare Worker), sync status, Groq usage and the
// timing of the last dictation.
import { useEffect, useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { SectionHeader, SettingsPanel, SettingsPanelRow, SettingsRow } from "./ui/SettingsSection";
import type { WhisperAshState } from "../utils/whisperashSettingsSync";

function ago(ts: number | null) {
  if (!ts) return "never";
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return new Date(ts).toLocaleString();
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
  const [geminiSaved, setGeminiSaved] = useState(false);
  const [geminiInput, setGeminiInput] = useState("");
  const api = (window as unknown as { electronAPI?: Record<string, (...a: unknown[]) => Promise<unknown>> }).electronAPI;

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
    });
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
      setMessage(res.ok ? okText : `Error: ${res.error}`);
      if (res.ok) setPassword("");
    } finally {
      setBusy(false);
    }
  };

  const usage = state?.usage;
  const timing = state?.lastTiming;

  return (
    <div className="space-y-5">
      <SectionHeader
        title="WhisperAsh account & sync"
        description="Log in on every PC with the same email to share your dictation history, personal dictionary and settings. Everything works offline and syncs later."
      />
      <SettingsPanel>
        <SettingsPanelRow>
          <SettingsRow label="Sync server URL" description="Your Cloudflare Worker, e.g. https://whisperash-sync.<you>.workers.dev">
            <Input
              className="w-80 h-8 text-xs"
              value={serverUrl}
              placeholder="https://whisperash-sync.example.workers.dev"
              onChange={(e) => setServerUrl(e.target.value)}
              disabled={busy || state?.signedIn}
            />
          </SettingsRow>
        </SettingsPanelRow>
        {state?.signedIn ? (
          <>
            <SettingsPanelRow>
              <SettingsRow label="Signed in as" description={state.email}>
                <Button size="sm" variant="outline" disabled={busy} onClick={() => run(() => bridge.logout(), "Logged out")}>
                  Log out
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
        ) : (
          <>
            <SettingsPanelRow>
              <SettingsRow label="Email">
                <Input className="w-80 h-8 text-xs" value={email} onChange={(e) => setEmail(e.target.value)} />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="Password" description="At least 8 characters. Stored only as a hash on your server.">
                <Input
                  className="w-80 h-8 text-xs"
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="Sign-up code" description="Only for creating the account, if you set SIGNUP_CODE on the Worker.">
                <Input
                  className="w-80 h-8 text-xs"
                  type="password"
                  value={signupCode}
                  onChange={(e) => setSignupCode(e.target.value)}
                />
              </SettingsRow>
            </SettingsPanelRow>
            <SettingsPanelRow>
              <SettingsRow label="" description={message || ""}>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    disabled={busy || !email || !password || !serverUrl}
                    onClick={() => run(() => bridge.login({ serverUrl, email, password }), "Logged in. Syncing…")}
                  >
                    Log in
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || !email || !password || !serverUrl}
                    onClick={() =>
                      run(() => bridge.register({ serverUrl, email, password, signupCode }), "Account created. Syncing…")
                    }
                  >
                    Create account
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
      </SettingsPanel>

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

      <SectionHeader
        title="Groq free-tier usage (this PC)"
        description="Counted locally. Each Whisper request is billed as at least 10 seconds of audio. Limits from console.groq.com/docs/rate-limits."
      />
      <SettingsPanel>
        {usage ? (
          <SettingsPanelRow>
            <div className="w-full space-y-3 py-1">
              <Meter label="Whisper requests, last minute" used={usage.stt.minute.requests} limit={usage.limits.stt.rpm} />
              <Meter label="Whisper requests, last 24 h" used={usage.stt.day.requests} limit={usage.limits.stt.rpd} />
              <Meter label="Audio, last hour" used={usage.stt.hour.seconds} limit={usage.limits.stt.audioPerHour} unit=" s" />
              <Meter label="Audio, last 24 h" used={usage.stt.day.seconds} limit={usage.limits.stt.audioPerDay} unit=" s" />
              <Meter label="Cleanup requests, last 24 h" used={usage.llm.day.requests} limit={usage.limits.llm.rpd} />
              <Meter label="Cleanup tokens, last 24 h" used={usage.llm.day.tokens} limit={usage.limits.llm.tpd} />
            </div>
          </SettingsPanelRow>
        ) : null}
      </SettingsPanel>

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
