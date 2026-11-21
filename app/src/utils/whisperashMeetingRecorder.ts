// WhisperAsh "Record meeting" recorder (renderer). Records the chosen dictation microphone as
// 15-minute .webm parts (5-second chunks streamed to the main process). Module level, so leaving
// the Meetings page or hiding the window keeps recording; the meeting hotkey reaches it too.
import { useSyncExternalStore } from "react";
import { useSettingsStore } from "../stores/settingsStore";
import {
  meetingHotkeyAction,
  type MeetingRecorderPhase,
  type MeetingRequestMode,
} from "./whisperashMeetingHotkey";

const PART_MS = 15 * 60 * 1000;
const MAX_MS = 3 * 60 * 60 * 1000;
const TIMESLICE_MS = 5000;
const MIME = "audio/webm;codecs=opus";

// ---------------------------------------------------------------------------------------
// Recorder (module level)
// ---------------------------------------------------------------------------------------

type Phase = MeetingRecorderPhase;

interface RecorderState {
  phase: Phase;
  id: string | null;
  elapsedMs: number;
  level: number;
  error: string | null;
}

let recState: RecorderState = { phase: "idle", id: null, elapsedMs: 0, level: 0, error: null };
const listeners = new Set<() => void>();
function setRec(patch: Partial<RecorderState>) {
  const before = recState;
  recState = { ...recState, ...patch };
  listeners.forEach((l) => l());
  // Tell main (and so the pill) when the phase or the meeting changes.
  if (before.phase !== recState.phase || before.id !== recState.id) publishState();
}

function publishState() {
  try {
    window.whisperash?.publishMeetingRecorderState?.({
      phase: recState.phase,
      id: recState.id,
      accumulatedMs,
      runStartedAt: recState.phase === "recording" ? runStartedAt : 0,
    });
  } catch {
    /* the pill's indicator only */
  }
}

let stream: MediaStream | null = null;
let recorder: MediaRecorder | null = null;
let part = 0;
let partStartMs = 0;
let accumulatedMs = 0; // recorded time before the current run (pauses excluded)
let runStartedAt = 0;
let tick: ReturnType<typeof setInterval> | null = null;
let audioCtx: AudioContext | null = null;
let cancelled = false;
let sendQueue: Promise<unknown> = Promise.resolve();
let openRecorders = 0;
let allStopped: (() => void) | null = null;

function elapsedNow() {
  return accumulatedMs + (recState.phase === "recording" && runStartedAt ? Date.now() - runStartedAt : 0);
}

function startRecorder(id: string, index: number, startMs: number) {
  if (!stream) return;
  const r = new MediaRecorder(stream, { mimeType: MIME, audioBitsPerSecond: 32000 });
  openRecorders += 1;
  r.ondataavailable = (e) => {
    if (cancelled || !e.data || !e.data.size) return;
    const blob = e.data;
    sendQueue = sendQueue.then(async () => {
      const data = await blob.arrayBuffer();
      const res = await window.whisperash?.meetingAppend({ id, part: index, data, partStartMs: startMs });
      if (res && !res.ok && !cancelled) setRec({ error: res.error || "Could not save the recording" });
    });
  };
  r.onstop = () => {
    openRecorders -= 1;
    if (openRecorders === 0 && allStopped) allStopped();
  };
  r.start(TIMESLICE_MS);
  return r;
}

// Every 15 minutes a new recorder takes over, so each part is a complete .webm file. The new
// one starts before the old one stops, so no audio falls in between.
function rotatePart() {
  if (!recState.id || !recorder) return;
  const old = recorder;
  part += 1;
  partStartMs = elapsedNow();
  recorder = startRecorder(recState.id, part, partStartMs) || null;
  old.stop();
}

function startLevelMeter(s: MediaStream) {
  try {
    audioCtx = new AudioContext();
    const analyser = audioCtx.createAnalyser();
    analyser.fftSize = 1024;
    audioCtx.createMediaStreamSource(s).connect(analyser);
    const buf = new Float32Array(analyser.fftSize);
    return () => {
      analyser.getFloatTimeDomainData(buf);
      let sum = 0;
      for (let i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
      return Math.min(1, Math.sqrt(sum / buf.length) * 6);
    };
  } catch {
    return () => 0;
  }
}

async function openMic(deviceId: string) {
  const base = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
  if (deviceId) {
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: { ...base, deviceId: { exact: deviceId } } });
    } catch {
      /* the chosen mic is gone: use the default one */
    }
  }
  return navigator.mediaDevices.getUserMedia({ audio: base });
}

export async function startMeeting(deviceId: string) {
  const bridge = window.whisperash;
  if (!bridge || recState.phase !== "idle") return;
  setRec({ phase: "starting", error: null, elapsedMs: 0, level: 0 });
  try {
    stream = await openMic(deviceId);
  } catch (err) {
    setRec({ phase: "idle", error: `Microphone not available: ${(err as Error).message}` });
    return;
  }
  const res = await bridge.meetingStart();
  if (!res.ok) {
    stream.getTracks().forEach((t) => t.stop());
    stream = null;
    setRec({ phase: "idle", error: res.error });
    return;
  }
  const id = res.result.id;
  cancelled = false;
  part = 0;
  partStartMs = 0;
  accumulatedMs = 0;
  runStartedAt = Date.now();
  sendQueue = Promise.resolve();
  recorder = startRecorder(id, 0, 0) || null;
  const readLevel = startLevelMeter(stream);
  setRec({ phase: "recording", id });
  tick = setInterval(() => {
    const elapsed = elapsedNow();
    setRec({ elapsedMs: elapsed, level: recState.phase === "recording" ? readLevel() : 0 });
    if (recState.phase !== "recording") return;
    if (elapsed >= MAX_MS) void stopMeeting();
    else if (elapsed - partStartMs >= PART_MS) rotatePart();
  }, 250);
}

export function pauseMeeting() {
  if (recState.phase !== "recording" || !recorder) return;
  accumulatedMs = elapsedNow();
  runStartedAt = 0;
  recorder.pause();
  setRec({ phase: "paused", level: 0, elapsedMs: accumulatedMs });
}

export function resumeMeeting() {
  if (recState.phase !== "paused" || !recorder) return;
  runStartedAt = Date.now();
  recorder.resume();
  setRec({ phase: "recording" });
}

async function finishRecorders() {
  if (tick) clearInterval(tick);
  tick = null;
  const done = new Promise<void>((resolve) => {
    allStopped = resolve;
    if (openRecorders === 0) resolve();
  });
  if (recorder && recorder.state !== "inactive") recorder.stop();
  await done;
  allStopped = null;
  await sendQueue.catch(() => {});
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  recorder = null;
  audioCtx?.close().catch(() => {});
  audioCtx = null;
}

export async function stopMeeting() {
  if (recState.phase !== "recording" && recState.phase !== "paused") return;
  const id = recState.id;
  const durationMs = Math.min(MAX_MS, elapsedNow());
  accumulatedMs = durationMs;
  setRec({ phase: "stopping", level: 0, elapsedMs: durationMs });
  await finishRecorders();
  const res = id ? await window.whisperash?.meetingStop({ id, durationMs }) : null;
  lastStoppedId = id;
  setRec({
    phase: "idle",
    id: null,
    elapsedMs: 0,
    error: res && !res.ok ? res.error : null,
  });
}

export async function cancelMeeting() {
  if (recState.phase !== "recording" && recState.phase !== "paused") return;
  const id = recState.id;
  cancelled = true;
  setRec({ phase: "stopping", level: 0 });
  await finishRecorders();
  if (id) await window.whisperash?.meetingCancel(id);
  setRec({ phase: "idle", id: null, elapsedMs: 0, error: null });
}

let lastStoppedId: string | null = null;

/** The meeting that was just stopped (once), so the page can open it. */
export function takeLastStoppedId() {
  const id = lastStoppedId;
  lastStoppedId = null;
  return id;
}

/** A meeting request from main: the hotkey ("toggle") starts or stops; "start" only starts. */
export function handleMeetingRequest(mode: MeetingRequestMode) {
  const action = meetingHotkeyAction(recState.phase, mode);
  if (action === "start") void startMeeting(useSettingsStore.getState().selectedMicDeviceId || "");
  else if (action === "stop") void stopMeeting();
}

export function useRecorder() {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => recState
  );
}
