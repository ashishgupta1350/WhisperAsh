import { useEffect, useRef } from "react";
import type React from "react";
import { useTranslation } from "react-i18next";

interface PillCommandMenuProps {
  buttonRef: React.RefObject<HTMLDivElement | null>;
  align: "left" | "right" | "center";
  isRecording: boolean;
  agentAllowed: boolean;
  meetingAllowed: boolean;
  /** WhisperAsh: "Recording meeting · 12:05" while Record meeting runs, else null. */
  meetingStatus?: string | null;
  onStopMeeting?: () => void;
  onOpenMeetings?: () => void;
  isHovered: boolean;
  setWindowInteractivity: (capture: boolean) => void;
  onToggleListening: () => void;
  onAskAssistant: () => void;
  onStartMeeting: () => void;
  onHide: () => void;
  onClose: () => void;
}

/**
 * The pill's right-click command menu. Mounted only while open; clicking
 * outside it (and outside the pill button that anchors it) closes it.
 */
export function PillCommandMenu({
  buttonRef,
  align,
  isRecording,
  agentAllowed,
  meetingAllowed,
  meetingStatus = null,
  onStopMeeting,
  onOpenMeetings,
  isHovered,
  setWindowInteractivity,
  onToggleListening,
  onAskAssistant,
  onStartMeeting,
  onHide,
  onClose,
}: PillCommandMenuProps): React.JSX.Element {
  const { t } = useTranslation();
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent): void => {
      const target = event.target as Node | null;
      if (
        menuRef.current &&
        !menuRef.current.contains(target) &&
        buttonRef.current &&
        !buttonRef.current.contains(target)
      ) {
        onClose();
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [buttonRef, onClose]);

  // The pill docks against a physical window edge and the window clips anything past it (#2064),
  // so the menu anchors on that same side, never on a logical start/end.
  const alignClass =
    align === "right" ? "right-0" : align === "left" ? "left-0" : "left-1/2 -translate-x-1/2";

  return (
    <div
      ref={menuRef}
      className={`absolute bottom-full ${alignClass} mb-3 w-48 overflow-hidden rounded-lg border border-border bg-popover text-popover-foreground shadow-lg backdrop-blur-sm`}
      onMouseEnter={() => {
        setWindowInteractivity(true);
      }}
      onMouseLeave={() => {
        if (!isHovered) {
          setWindowInteractivity(false);
        }
      }}
    >
      {meetingStatus && (
        <>
          <div className="flex items-center gap-2 px-3 pt-2 pb-1 text-xs text-muted-foreground tabular-nums">
            <span className="h-2 w-2 shrink-0 rounded-full bg-red-500" aria-hidden="true" />
            {meetingStatus}
          </div>
          <button
            className="w-full px-3 py-2 text-start text-sm font-medium text-red-600 hover:bg-muted focus:bg-muted focus:outline-none dark:text-red-400"
            onClick={onStopMeeting}
          >
            Stop meeting
          </button>
          <button
            className="w-full px-3 py-2 text-start text-sm hover:bg-muted focus:bg-muted focus:outline-none"
            onClick={onOpenMeetings}
          >
            Open Meetings
          </button>
          <div className="h-px bg-border" />
        </>
      )}
      <button
        className="w-full px-3 py-2 text-start text-sm font-medium hover:bg-muted focus:bg-muted focus:outline-none"
        onClick={onToggleListening}
      >
        {isRecording ? t("app.commandMenu.stopListening") : t("app.commandMenu.startListening")}
      </button>
      {/* Opening the Agent panel mid-recording would strand the capture with no
          surface (a translation recording becomes invisible AND un-stoppable:
          its hotkey is blocked while the panel is open and Escape belongs to the
          panel). Stop or finish the recording first. */}
      {agentAllowed && !isRecording && !meetingStatus && (
        <>
          <div className="h-px bg-border" />
          <button
            className="w-full px-3 py-2 text-start text-sm hover:bg-muted focus:bg-muted focus:outline-none"
            onClick={onAskAssistant}
          >
            {t("app.commandMenu.askAssistant")}
          </button>
        </>
      )}
      {meetingAllowed && !isRecording && !meetingStatus && (
        <>
          <div className="h-px bg-border" />
          <button
            className="w-full px-3 py-2 text-start text-sm hover:bg-muted focus:bg-muted focus:outline-none"
            onClick={onStartMeeting}
          >
            {t("app.commandMenu.startMeetingRecording")}
          </button>
        </>
      )}
      <div className="h-px bg-border" />
      <button
        className="w-full px-3 py-2 text-start text-sm hover:bg-muted focus:bg-muted focus:outline-none"
        onClick={onHide}
      >
        {t("app.commandMenu.hideForNow")}
      </button>
    </div>
  );
}
