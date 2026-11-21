// WhisperAsh: which settings follow you to other PCs, and applying settings
// pulled from the cloud. Runs in the control panel window only.
import { useSettingsStore } from "../stores/settingsStore";

export interface WhisperAshUsageWindow {
  requests: number;
  seconds: number;
  tokens: number;
}

// Groq's own x-ratelimit-*-requests headers from the latest call (per model).
export interface WhisperAshGroqLimit {
  remaining: number;
  lim: number | null;
  ts: number;
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
    stt: {
      minute: WhisperAshUsageWindow;
      hour: WhisperAshUsageWindow;
      day: WhisperAshUsageWindow;
      model?: string | null;
      groq?: WhisperAshGroqLimit | null;
      groqAudioHour?: { remaining: number; lim: number | null } | null;
    };
    llm: {
      minute: WhisperAshUsageWindow;
      day: WhisperAshUsageWindow;
      models?: Array<{ model: string; requests: number; tokens: number; groq: WhisperAshGroqLimit | null }>;
      since?: number;
    };
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
  /** Account mode: dictation through the WhisperAsh server (owner's keys) or this PC's own keys. */
  engine?: { account: boolean; source: "account" | "own" | null };
  /** The account's word allowance from the server's /me (account mode, signed in). */
  plan?: WhisperAshPlan | null;
}

export interface WhisperAshPlan {
  limit: number;
  period: "day" | "week";
  used: number;
  left: number;
  resetsAt: number;
  status: "active" | "blocked";
  ownKeys: { groq: boolean; gemini: boolean };
  /** Meeting minutes a day (IST), from the server's /me once the Worker has meetings. */
  meetingLimitMin?: number;
  meetingUsedMin?: number;
  meetingLeftMin?: number;
}

/** The "Record meeting" recorder, relayed by main to the pill. Elapsed = accumulatedMs (+ now - runStartedAt while recording). */
export interface WhisperAshRecorderState {
  phase: "idle" | "starting" | "recording" | "paused" | "stopping";
  id: string | null;
  accumulatedMs: number;
  runStartedAt: number;
}

export type WhisperAshMeetingStatus = "recording" | "transcribing" | "done" | "failed";

export interface WhisperAshMeetingProgress {
  id: string | null;
  status?: WhisperAshMeetingStatus | "deleted";
  error?: string | null;
  stage?: "transcribing" | "summary";
  partsDone?: number;
  partsTotal?: number;
  currentPart?: number;
  /** Re-transcribe (Gemini 3.6 Flash) rather than the first run. */
  better?: boolean;
  done?: boolean;
}

export interface WhisperAshMeetingSummary {
  id: string;
  title: string;
  createdMs: number;
  durationMs: number;
  status: WhisperAshMeetingStatus;
  engine: "gemini" | "deepgram" | "groq" | "mixed" | "";
  error: string | null;
  hasAudio: boolean;
  /** True after Re-transcribe, until Undo. */
  canUndoRetranscribe?: boolean;
  progress: WhisperAshMeetingProgress | null;
}

export interface WhisperAshMeetingDetail extends WhisperAshMeetingSummary {
  summary: string;
  transcript: string;
  parts: Array<{ index: number; status: string; engine: string; error: string | null }>;
}

export type WhisperAshMeetingResult<T> =
  | { ok: true; result: T; error?: undefined }
  | { ok: false; error: string; result?: undefined };

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
  getEngine: () => Promise<{ account: boolean; base: string | null; token: string | null }>;
  setEngine: (source: "account" | "own") => Promise<WhisperAshResult>;
  refreshPlan: () => Promise<WhisperAshResult>;
  forgotPassword: (p: { serverUrl?: string; email: string }) => Promise<WhisperAshResult>;
  changePassword: (p: { current: string; password: string }) => Promise<WhisperAshResult>;
  recordSettings: (values: Record<string, string | null>) => void;
  recordUsage: (usage: {
    kind: "stt" | "llm";
    audioSeconds?: number;
    tokens?: number;
    model?: string;
    remainingRequests?: number | null;
    limitRequests?: number | null;
  }) => void;
  recordTiming: (timing: Record<string, number | string>) => void;
  onState: (cb: (state: WhisperAshState) => void) => () => void;
  onApplySettings: (cb: (values: Record<string, string | null>) => void) => () => void;
  onHistoryChanged: (cb: () => void) => () => void;
  meetingStart: () => Promise<WhisperAshMeetingResult<{ id: string }>>;
  meetingAppend: (p: {
    id: string;
    part: number;
    data: ArrayBuffer;
    partStartMs: number;
  }) => Promise<{ ok: boolean; error?: string }>;
  meetingStop: (p: { id: string; durationMs: number }) => Promise<WhisperAshMeetingResult<WhisperAshMeetingSummary>>;
  meetingCancel: (id: string) => Promise<WhisperAshMeetingResult<{ ok: boolean }>>;
  meetingList: () => Promise<WhisperAshMeetingResult<WhisperAshMeetingSummary[]>>;
  meetingGet: (id: string) => Promise<WhisperAshMeetingResult<WhisperAshMeetingDetail | null>>;
  meetingDelete: (id: string) => Promise<WhisperAshMeetingResult<{ ok: boolean }>>;
  meetingRetry: (id: string) => Promise<WhisperAshMeetingResult<WhisperAshMeetingSummary>>;
  meetingRename: (id: string, title: string) => Promise<WhisperAshMeetingResult<WhisperAshMeetingDetail | null>>;
  onMeetingProgress: (cb: (p: WhisperAshMeetingProgress) => void) => () => void;
  /** The pending meeting request ("toggle" = hotkey, "start"), once; null when none. */
  meetingRetranscribe: (id: string) => Promise<WhisperAshMeetingResult<WhisperAshMeetingSummary>>;
  meetingUndoRetranscribe: (id: string) => Promise<WhisperAshMeetingResult<WhisperAshMeetingDetail | null>>;
  /** Gemini 3.6 Flash on a dictation's saved audio; the caller cleans it up and saves it. */
  retranscribeDictation: (id: number) => Promise<WhisperAshMeetingResult<{ rawText: string; model: string }>>;
  /** The pending meeting request ("toggle" = hotkey, "start", "stop", "open"), once; null when none. */
  meetingTakeHotkey: () => Promise<"toggle" | "start" | "stop" | "open" | null>;
  meetingRequest: (mode: "stop" | "open") => Promise<{ ok: boolean }>;
  publishMeetingRecorderState: (state: WhisperAshRecorderState) => void;
  getMeetingRecorderState: () => Promise<WhisperAshRecorderState>;
  onMeetingRecorderState: (cb: (state: WhisperAshRecorderState) => void) => () => void;
  /** Main: open the Meetings page on this meeting (sent after Stop). */
  onMeetingOpen: (cb: (payload: { id: string }) => void) => () => void;
  onMeetingHotkey: (cb: () => void) => () => void;
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
