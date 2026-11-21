export const MODIFIER_ONLY_HOLD_THRESHOLD_MS = 200;

export function hasMetModifierOnlyHoldThreshold(holdDurationMs: number): boolean {
  return holdDurationMs >= MODIFIER_ONLY_HOLD_THRESHOLD_MS;
}

export function shouldAcceptModifierOnlyCapture(hotkey: string, holdDurationMs: number): boolean {
  return hotkey.startsWith("Right") || hasMetModifierOnlyHoldThreshold(holdDurationMs);
}

export function shouldRestoreCaptureFocus(
  activeElement: Element | null,
  body: HTMLElement | null
): boolean {
  return activeElement === null || activeElement === body;
}

/**
 * On AltGr layouts (Windows "English (India)", AZERTY, German…) Right Alt arrives as a synthetic
 * Left Ctrl plus Right Alt, so a lone Right Alt read as "Ctrl+Alt" and could never be set. While
 * AltGraph is down, that Left Ctrl is not a Ctrl press (windows-key-listener.c ignores it the
 * same way, see IsAltGrCtrl), so it is dropped from the held modifiers.
 */
export function withoutAltGraphCtrl<K extends string>(
  held: Record<K | "ctrl" | "alt", boolean>,
  codes: Partial<Record<K | "ctrl" | "alt", string>>,
  altGraph: boolean
): {
  held: Record<K | "ctrl" | "alt", boolean>;
  codes: Partial<Record<K | "ctrl" | "alt", string>>;
} {
  if (!altGraph || !held.alt || codes.alt !== "AltRight" || codes.ctrl === "ControlRight") {
    return { held, codes };
  }
  const nextCodes = { ...codes };
  delete nextCodes.ctrl;
  return { held: { ...held, ctrl: false }, codes: nextCodes };
}
