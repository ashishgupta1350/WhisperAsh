// WhisperAsh: which settings follow you to other PCs, and applying settings
// pulled from the cloud. Runs in the control panel window only.
import { useSettingsStore } from "../stores/settingsStore";

export interface WhisperAshUsageWindow {
  requests: number;
  seconds: number;
  tokens: number;
}

export interface WhisperAshState {
  serverUrl: string;
  email: string;
  signedIn: boolean;
  pending: number;
  state: "idle" | "syncing" | "offline" | "error";
  lastSyncAt: number | null;
  lastError: string | null;
  usage: {
    stt: { minute: WhisperAshUsageWindow; hour: WhisperAshUsageWindow; day: WhisperAshUsageWindow };
    llm: { minute: WhisperAshUsageWindow; day: WhisperAshUsageWindow };
    limits: {
      stt: { rpm: number; rpd: number; audioPerHour: number; audioPerDay: number };
      llm: { rpm: number; rpd: number; tpd: number };
    };
    words?: {
      dictatedToday: number;
      dictatedMonth: number;
      leftToday: number;
      leftMonth: number;
      fullDay: number;
      perSecond: number;
      wordsPerRequest: number;
    };
  };
  lastTiming: Record<string, number | string> | null;
}

export interface WhisperAshResult {
  ok: boolean;
  error?: string;
  state: WhisperAshState;
}

export interface WhisperAshBridge {
  getState: () => Promise<WhisperAshState>;
  register: (p: { serverUrl: string; email: string; password: string; signupCode?: string }) => Promise<WhisperAshResult>;
  login: (p: { serverUrl: string; email: string; password: string }) => Promise<WhisperAshResult>;
  logout: () => Promise<WhisperAshResult>;
  syncNow: () => Promise<WhisperAshResult>;
  setServerUrl: (serverUrl: string) => Promise<WhisperAshResult>;
  recordSettings: (values: Record<string, string | null>) => void;
  recordUsage: (usage: { kind: "stt" | "llm"; audioSeconds?: number; tokens?: number; model?: string }) => void;
  recordTiming: (timing: Record<string, number | string>) => void;
  onState: (cb: (state: WhisperAshState) => void) => () => void;
  onApplySettings: (cb: (values: Record<string, string | null>) => void) => () => void;
  onHistoryChanged: (cb: () => void) => () => void;
}

declare global {
  interface Window {
    whisperash?: WhisperAshBridge;
  }
}

// Device-specific things (microphone, hotkey, window position) stay per PC.
export const SYNCED_SETTING_KEYS = [
  "preferredLanguage",
  "hindiScriptPreference",
  "cloudTranscriptionModel",
  "cleanupModel",
  "cleanupReasoningEffort",
  "useCleanupModel",
  "toneStyleEnabled",
  "toneStyles",
  "audioCuesEnabled",
  "agentName",
  "snippets",
  "customPrompts",
] as const;

function collect(): Record<string, string | null> {
  const values: Record<string, string | null> = {};
  for (const key of SYNCED_SETTING_KEYS) values[key] = localStorage.getItem(key);
  return values;
}

let started = false;

export function startWhisperAshSettingsSync(): void {
  const bridge = window.whisperash;
  if (started || !bridge) return;
  started = true;

  let last = JSON.stringify(collect());
  bridge.recordSettings(JSON.parse(last));

  let timer: ReturnType<typeof setTimeout> | null = null;
  const check = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      const now = JSON.stringify(collect());
      if (now !== last) {
        last = now;
        bridge.recordSettings(JSON.parse(now));
      }
    }, 1500);
  };
  useSettingsStore.subscribe(check);
  window.addEventListener("whisperash-setting-changed", check);

  bridge.onApplySettings((values) => {
    let changed = false;
    for (const key of SYNCED_SETTING_KEYS) {
      if (!(key in values)) continue;
      const value = values[key];
      if (localStorage.getItem(key) === value) continue;
      if (value === null) localStorage.removeItem(key);
      else localStorage.setItem(key, value);
      changed = true;
    }
    if (changed) {
      last = JSON.stringify(collect());
      // The store reads localStorage at start-up; reload to pick up the new values.
      window.location.reload();
    }
  });

  bridge.onHistoryChanged(() => {
    window.dispatchEvent(new CustomEvent("whisperash-history-changed"));
  });
}
