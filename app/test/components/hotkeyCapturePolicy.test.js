const assert = require("node:assert/strict");
const test = require("node:test");

const load = () => import("../../src/components/ui/hotkeyCapturePolicy.ts");

test("right-side modifiers are captured on a quick release", async () => {
  const { shouldAcceptModifierOnlyCapture } = await load();

  assert.equal(shouldAcceptModifierOnlyCapture("RightOption", 0), true);
  assert.equal(shouldAcceptModifierOnlyCapture("RightControl", 50), true);
});

test("other modifier-only shortcuts retain the hold threshold", async () => {
  const { MODIFIER_ONLY_HOLD_THRESHOLD_MS, shouldAcceptModifierOnlyCapture } = await load();

  assert.equal(
    shouldAcceptModifierOnlyCapture("Control+Alt", MODIFIER_ONLY_HOLD_THRESHOLD_MS - 1),
    false
  );
  assert.equal(
    shouldAcceptModifierOnlyCapture("Control+Alt", MODIFIER_ONLY_HOLD_THRESHOLD_MS),
    true
  );
});

test("capture focus is restored only when no control owns page focus", async () => {
  const { shouldRestoreCaptureFocus } = await load();
  const body = {};
  const button = {};

  assert.equal(shouldRestoreCaptureFocus(null, body), true);
  assert.equal(shouldRestoreCaptureFocus(body, body), true);
  assert.equal(shouldRestoreCaptureFocus(button, body), false);
});

// Windows AltGr layouts (English India, AZERTY…) send a synthetic Left Ctrl with Right Alt, so a
// lone Right Alt was captured as "Ctrl+Alt" and could not be set (the WhisperAsh default).
test("AltGr: the synthetic Left Ctrl is dropped so Right Alt captures alone", async () => {
  const { withoutAltGraphCtrl } = await load();
  const held = { ctrl: true, meta: false, alt: true, shift: false };
  const codes = { ctrl: "ControlLeft", alt: "AltRight" };

  const altGr = withoutAltGraphCtrl(held, codes, true);
  assert.deepEqual(altGr.held, { ctrl: false, meta: false, alt: true, shift: false });
  assert.deepEqual(altGr.codes, { alt: "AltRight" });

  // Not AltGr (plain Right Alt on a US layout, or a real Ctrl+Alt chord): unchanged.
  assert.deepEqual(withoutAltGraphCtrl(held, codes, false), { held, codes });
  assert.deepEqual(withoutAltGraphCtrl(held, { ctrl: "ControlLeft", alt: "AltLeft" }, true).held.ctrl, true);
  // A real Right Ctrl held with AltGr stays.
  assert.equal(withoutAltGraphCtrl(held, { ctrl: "ControlRight", alt: "AltRight" }, true).held.ctrl, true);
});
