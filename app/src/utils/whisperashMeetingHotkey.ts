// WhisperAsh: what a meeting request does to the "Record meeting" recorder.
// Pure, so it is unit-tested (test/helpers/whisperashMeetingHotkey.test.js).

export type MeetingRecorderPhase = "idle" | "starting" | "recording" | "paused" | "stopping";

/**
 * "toggle" = the meeting hotkey; "start" = tray, pill menu or a meeting prompt; "stop" = the
 * pill's Stop meeting; "open" = only show the Meetings page.
 */
export type MeetingRequestMode = "toggle" | "start" | "stop" | "open";

/**
 * Idle -> start. Recording or paused -> stop, for the hotkey and the pill's Stop (a "start"
 * request then just shows the Meetings page). While starting or saving the request is ignored.
 */
export function meetingHotkeyAction(
  phase: MeetingRecorderPhase,
  mode: MeetingRequestMode = "toggle"
): "start" | "stop" | "ignore" {
  const running = phase === "recording" || phase === "paused";
  if (mode === "open") return "ignore";
  if (mode === "stop") return running ? "stop" : "ignore";
  if (phase === "idle") return "start";
  if (mode === "toggle" && running) return "stop";
  return "ignore";
}

export interface MeetingPillState {
  phase: MeetingRecorderPhase;
  accumulatedMs: number;
  runStartedAt: number;
}

/**
 * What the pill shows for the recorder: active while recording or paused, with the elapsed
 * time (pauses excluded), e.g. "Recording meeting · 12:05" or "Meeting paused · 12:05".
 */
export function meetingPillStatus(
  state: MeetingPillState | null | undefined,
  now: number
): { active: boolean; paused: boolean; elapsedMs: number; label: string } {
  const phase = state?.phase;
  const active = phase === "recording" || phase === "paused";
  if (!state || !active) return { active: false, paused: false, elapsedMs: 0, label: "" };
  const running = phase === "recording" && state.runStartedAt > 0 ? Math.max(0, now - state.runStartedAt) : 0;
  const elapsedMs = Math.max(0, state.accumulatedMs || 0) + running;
  const s = Math.floor(elapsedMs / 1000);
  const pad = (n: number) => String(n).padStart(2, "0");
  const clock =
    s >= 3600 ? `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}` : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;
  const paused = phase === "paused";
  return { active, paused, elapsedMs, label: `${paused ? "Meeting paused" : "Recording meeting"} · ${clock}` };
}
