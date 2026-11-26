// WhisperAsh: the Meetings page for "Record meeting" (microphone only). The recorder is in
// utils/whisperashMeetingRecorder.ts; the main process transcribes and summarises
// (src/helpers/whisperashMeetings.js).
import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Copy, Loader2, Mic, RotateCcw, Sparkles, Trash2, Undo2 } from "./icons";
import { useSettingsStore } from "../stores/settingsStore";
import type {
  WhisperAshMeetingDetail,
  WhisperAshMeetingProgress,
  WhisperAshMeetingSummary,
  WhisperAshPlan,
  WhisperAshRecordingKind,
} from "../utils/whisperashSettingsSync";
import {
  cancelMeeting,
  pauseMeeting,
  resumeMeeting,
  startMeeting,
  stopMeeting,
  takeLastStoppedId,
  useRecorder,
} from "../utils/whisperashMeetingRecorder";


// ---------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------

function clock(ms: number) {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const pad = (n: number) => String(n).padStart(2, "0");
  return h ? `${h}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}` : `${pad(Math.floor(s / 60))}:${pad(s % 60)}`;
}

function duration(ms: number) {
  const min = Math.round(ms / 60000);
  if (ms < 60000) return `${Math.round(ms / 1000)} s`;
  if (min < 60) return `${min} min`;
  return `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, "0")} min`;
}

const ENGINE_LABEL: Record<string, string> = {
  gemini: "Gemini",
  deepgram: "Deepgram",
  groq: "Groq (backup engine)",
  mixed: "Mixed engines",
};

function infoLine(m: WhisperAshMeetingSummary) {
  const date = new Date(m.createdMs).toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
  const engine = ENGINE_LABEL[m.engine] || "";
  return [date, m.durationMs ? duration(m.durationMs) : "", engine].filter(Boolean).join(" · ");
}

function progressText(p: WhisperAshMeetingProgress | null | undefined) {
  if (!p) return "Transcribing…";
  if (p.stage === "summary") return "Writing the summary…";
  const verb = p.better ? "Re-transcribing" : "Transcribing";
  const model = p.better ? " with Gemini 3.6 Flash" : "";
  if (p.partsTotal) return `${verb} part ${Math.min((p.partsDone || 0) + 1, p.partsTotal)} of ${p.partsTotal}${model}…`;
  return "Transcribing…";
}

// ---------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------

/** openMeeting: a meeting to select (a new object each time main asks, so the same id reopens). */
export default function WhisperAshMeetings({ openMeeting, kind = "meetings", onOpenRecorder }: {
  openMeeting?: { id: string } | null;
  kind?: WhisperAshRecordingKind;
  onOpenRecorder: (kind: WhisperAshRecordingKind) => void;
}) {
  const diary = kind === "diaries";
  const bridge = window.whisperash;
  const rec = useRecorder();
  const micId = useSettingsStore((s) => s.selectedMicDeviceId);
  const [meetings, setMeetings] = useState<WhisperAshMeetingSummary[]>([]);
  const [progress, setProgress] = useState<Record<string, WhisperAshMeetingProgress>>({});
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<WhisperAshMeetingDetail | null>(null);
  const [plan, setPlan] = useState<WhisperAshPlan | null>(null);
  const [signedIn, setSignedIn] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [titleDraft, setTitleDraft] = useState("");
  const selectedRef = useRef<string | null>(null);
  selectedRef.current = selectedId;

  const refreshList = useCallback(async () => {
    const res = await bridge?.meetingList(kind);
    if (res?.ok) setMeetings(res.result);
  }, [bridge, kind]);

  const refreshDetail = useCallback(
    async (id: string | null) => {
      if (!id) return setDetail(null);
      const res = await bridge?.meetingGet(id);
      if (res?.ok) {
        setDetail(res.result);
        setTitleDraft(res.result?.title || "");
      }
    },
    [bridge]
  );

  useEffect(() => {
    if (!bridge) return;
    void refreshList();
    bridge.getState().then((s) => {
      setPlan(s.plan || null);
      setSignedIn(s.signedIn);
    });
    void bridge.refreshPlan?.();
    const offState = bridge.onState((s) => {
      setPlan(s.plan || null);
      setSignedIn(s.signedIn);
    });
    const offProgress = bridge.onMeetingProgress((p) => {
      if ((p.kind || "meetings") !== kind) return;
      if (p.id) setProgress((prev) => ({ ...prev, [p.id as string]: p }));
      void refreshList();
      if (p.id && p.id === selectedRef.current) {
        if (p.status === "deleted") setSelectedId(null);
        else if (p.done || !p.stage) void refreshDetail(p.id);
      }
      if (p.done) void bridge.refreshPlan?.();
    });
    return () => {
      offState();
      offProgress();
    };
  }, [bridge, refreshList, refreshDetail, kind]);

  useEffect(() => {
    void refreshDetail(selectedId);
  }, [selectedId, refreshDetail]);

  useEffect(() => {
    if (openMeeting?.id) setSelectedId(openMeeting.id);
  }, [openMeeting]);

  // Open the meeting that was just stopped.
  useEffect(() => {
    const stoppedId = rec.phase === "idle" ? takeLastStoppedId(kind) : null;
    if (stoppedId) {
      setSelectedId(stoppedId);
      void refreshList();
    }
  }, [rec.phase, refreshList, kind]);

  if (!bridge) return <div className="p-6 text-sm">WhisperAsh bridge not available.</div>;

  const active = rec.phase === "recording" || rec.phase === "paused";
  const otherRecorder = rec.phase !== "idle" && rec.kind !== kind;
  const showMinutes = signedIn && plan && typeof plan.meetingLeftMin === "number" && typeof plan.meetingLimitMin === "number";

  const act = async (fn: () => Promise<{ ok: boolean; error?: string }>, ok?: string) => {
    setMessage(null);
    const res = await fn();
    if (!res.ok) setMessage(res.error || "Something went wrong.");
    else if (ok) setMessage(ok);
    void refreshList();
    return res;
  };

  const saveTitle = async () => {
    if (!detail || titleDraft.trim() === detail.title) return;
    const res = await bridge.meetingRename(detail.id, titleDraft.trim());
    if (res.ok && res.result) setDetail(res.result);
    void refreshList();
  };

  const copyNote = async () => {
    if (!detail) return;
    const blocks = [`${detail.title}\n${infoLine(detail)}`];
    if (detail.summary) blocks.push(detail.summary);
    blocks.push(`Transcript\n${detail.transcript}`);
    await navigator.clipboard.writeText(blocks.join("\n\n"));
    setMessage("Copied.");
  };

  const detailProgress = detail ? progress[detail.id] || detail.progress : null;

  return (
    <div className="h-full overflow-y-auto px-6 py-5 space-y-5">
      {/* Recorder */}
      <div className="rounded-xl border border-border bg-card p-5 space-y-3">
        {otherRecorder ? (
          <Button variant="outline" onClick={() => onOpenRecorder(rec.kind)}>Open current recording</Button>
        ) : !active && rec.phase !== "stopping" ? (
          <div className="flex flex-col items-center gap-2 py-2">
            <Button
              size="lg"
              className="h-14 px-8 text-base"
              disabled={rec.phase === "starting"}
              onClick={() => void startMeeting(micId || "", kind)}
            >
              <span className="text-red-500 mr-1">●</span> {diary ? "Record diary entry" : "Record meeting"}
            </Button>
            <div className="text-xs text-muted-foreground text-center">
              {diary
                ? "Record in Hindi, English or Hinglish. Gemini writes a transcript, summary and dated title."
                : "Records your microphone (up to 3 hours). Hindi, English and Hinglish are written in Roman script, with speakers, a summary and action items."}
            </div>
          </div>
        ) : (
          <div className="flex flex-col items-center gap-3 py-2">
            <div className="flex items-center gap-2 text-sm">
              <Mic size={16} className={rec.phase === "recording" ? "text-red-500" : "text-muted-foreground"} />
              <span>
                {rec.phase === "paused" ? "Paused" : rec.phase === "stopping" ? "Saving…" : "Recording"}
              </span>
            </div>
            <div className="text-4xl font-semibold tabular-nums">{clock(rec.elapsedMs)}</div>
            <div className="h-1.5 w-56 rounded-full bg-muted overflow-hidden">
              <div className="h-full bg-emerald-500 transition-[width] duration-200" style={{ width: `${Math.round(rec.level * 100)}%` }} />
            </div>
            <div className="flex gap-2">
              {rec.phase === "paused" ? (
                <Button size="sm" variant="outline" onClick={resumeMeeting}>
                  Resume
                </Button>
              ) : (
                <Button size="sm" variant="outline" disabled={rec.phase !== "recording"} onClick={pauseMeeting}>
                  Pause
                </Button>
              )}
              <Button size="sm" disabled={rec.phase === "stopping"} onClick={() => void stopMeeting()}>
                Stop
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={rec.phase === "stopping"}
                onClick={() => {
                  if (window.confirm("Cancel this recording? The audio is deleted.")) void cancelMeeting();
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}
        {rec.error && !otherRecorder ? <div className="text-xs text-red-500 text-center">{rec.error}</div> : null}
        {showMinutes && !diary ? (
          <div className="text-xs text-muted-foreground text-center">
            {plan!.meetingLeftMin!.toLocaleString()} of {plan!.meetingLimitMin!.toLocaleString()} meeting minutes left
            today
          </div>
        ) : null}
      </div>

      {message ? <div className="text-xs text-muted-foreground">{message}</div> : null}

      <div className="grid gap-4 md:grid-cols-[minmax(220px,1fr)_2fr]">
        {/* List */}
        <div className="space-y-1">
          {meetings.length === 0 ? (
            <div className="text-sm text-muted-foreground">{diary ? "No diary entries yet." : "No meetings yet."}</div>
          ) : (
            meetings.map((m) => {
              const p = progress[m.id] || m.progress;
              return (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => setSelectedId(m.id)}
                  className={`w-full text-left rounded-lg px-3 py-2 border ${
                    selectedId === m.id ? "border-primary/50 bg-muted/60" : "border-transparent hover:bg-muted/40"
                  }`}
                >
                  <div className="text-sm font-medium truncate">{m.title}</div>
                  <div className="text-xs text-muted-foreground truncate">
                    {m.status === "transcribing" ? (
                      <span className="inline-flex items-center gap-1">
                        <Loader2 size={12} className="animate-spin" /> {progressText(p)}
                      </span>
                    ) : m.status === "failed" ? (
                      <span className="text-red-500">Failed · {infoLine(m)}</span>
                    ) : (
                      infoLine(m)
                    )}
                  </div>
                </button>
              );
            })
          )}
        </div>

        {/* Detail */}
        {detail ? (
          <div className="rounded-xl border border-border bg-card p-4 space-y-3 min-w-0">
            <Input
              className="h-9 text-base font-semibold"
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              onBlur={() => void saveTitle()}
              onKeyDown={(e) => {
                if (e.key === "Enter") (e.target as HTMLInputElement).blur();
              }}
            />
            <div className="text-xs text-muted-foreground">{infoLine(detail)}</div>
            {detail.status === "transcribing" ? (
              <div className="flex items-center gap-2 text-sm">
                <Loader2 size={14} className="animate-spin" /> {progressText(detailProgress)}
              </div>
            ) : null}
            {detail.error ? <div className="text-xs text-red-500">{detail.error}</div> : null}
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" disabled={!detail.transcript} onClick={() => void copyNote()}>
                <Copy size={14} /> Copy
              </Button>
              {detail.status === "failed" && detail.hasAudio ? (
                <Button size="sm" variant="outline" onClick={() => void act(() => bridge.meetingRetry(detail.id))}>
                  <RotateCcw size={14} /> Retry
                </Button>
              ) : null}
              {detail.status === "done" && detail.hasAudio ? (
                <Button
                  size="sm"
                  variant="outline"
                  title="Transcribe every part again with Gemini 3.6 Flash, then generate a new summary. You can undo it."
                  onClick={() => void act(() => bridge.meetingRetranscribe(detail.id))}
                >
                  <Sparkles size={14} /> {diary ? "Re-transcribe (better model)" : "Re-transcribe with Gemini 3.6 Flash"}
                </Button>
              ) : null}
              {detail.canUndoRetranscribe && detail.status !== "transcribing" ? (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    void act(() => bridge.meetingUndoRetranscribe(detail.id), "Earlier transcript restored.").then(() =>
                      refreshDetail(detail.id)
                    )
                  }
                >
                  <Undo2 size={14} /> Undo re-transcribe
                </Button>
              ) : null}
              <Button
                size="sm"
                variant="ghost"
                disabled={detail.status === "transcribing"}
                onClick={() => {
                  if (window.confirm("Delete this meeting? This can't be undone.")) {
                    void act(() => bridge.meetingDelete(detail.id)).then((r) => r.ok && setSelectedId(null));
                  }
                }}
              >
                <Trash2 size={14} /> Delete
              </Button>
            </div>
            {detail.summary ? (
              <div className="space-y-1">
                <div className="text-sm font-semibold">Summary</div>
                <div className="text-sm whitespace-pre-wrap">{detail.summary}</div>
              </div>
            ) : null}
            {detail.transcript ? (
              <div className="space-y-1">
                <div className="text-sm font-semibold">Transcript</div>
                <div className="text-sm whitespace-pre-wrap text-muted-foreground select-text">{detail.transcript}</div>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
