const test = require("node:test");
const assert = require("node:assert/strict");
const { PendingHotkeyPress } = require("../../src/helpers/whisperashMeetings");

const loadAction = () => import("../../src/utils/whisperashMeetingHotkey.ts");
const loadValidation = () => import("../../src/utils/hotkeyValidation.ts");

test("meeting hotkey: idle starts, recording or paused stops, starting/saving ignores", async () => {
  const { meetingHotkeyAction } = await loadAction();
  assert.equal(meetingHotkeyAction("idle"), "start");
  assert.equal(meetingHotkeyAction("recording"), "stop");
  assert.equal(meetingHotkeyAction("paused"), "stop");
  assert.equal(meetingHotkeyAction("starting"), "ignore");
  assert.equal(meetingHotkeyAction("stopping"), "ignore");
});

test("a 'start' request (tray, pill menu, meeting prompt) never stops a running recording", async () => {
  const { meetingHotkeyAction } = await loadAction();
  assert.equal(meetingHotkeyAction("idle", "start"), "start");
  assert.equal(meetingHotkeyAction("recording", "start"), "ignore");
  assert.equal(meetingHotkeyAction("paused", "start"), "ignore");
});

test("a meeting request is taken once with its mode, and a stale one is dropped", () => {
  let now = 1000;
  const gate = new PendingHotkeyPress({ ttlMs: 30000, now: () => now });
  assert.equal(gate.take(), null);
  gate.press();
  assert.equal(gate.take(), "toggle");
  assert.equal(gate.take(), null); // event + mount check never act twice
  gate.press("start");
  assert.equal(gate.take(), "start");
  gate.press();
  now += 30001;
  assert.equal(gate.take(), null);
});

// Right Ctrl is the WhisperAsh dictation hotkey on Windows. Capturing it for meetings used to
// fail with a bare "already used" note that read as the field ignoring the key.
test("Right Ctrl for meetings while it is the dictation hotkey: the clash names the key and the slot", async () => {
  const { validateHotkeyForSlot } = await loadValidation();
  const calls = [];
  const t = (key, params) => {
    calls.push({ key, params });
    if (key === "settingsPage.general.hotkey.title") return "Dictation Hotkey";
    if (key === "hotkey.errors.slotConflict") return `${params.hotkey} is already your ${params.slot}.`;
    return key;
  };
  const message = validateHotkeyForSlot("RightControl", { "settingsPage.general.hotkey.title": "RightControl" }, t);
  assert.ok(message, "a clash must be reported, not silently ignored");
  const conflict = calls.find((c) => c.key === "hotkey.errors.slotConflict");
  assert.equal(conflict.params.slot, "Dictation Hotkey");
  assert.match(conflict.params.hotkey, /Right/);
  assert.match(conflict.params.hotkey, /Ctrl|Control/);
});

test("Right Ctrl for meetings is accepted when dictation uses another key", async () => {
  const { validateHotkeyForSlot } = await loadValidation();
  const t = (key) => key;
  assert.equal(
    validateHotkeyForSlot("RightControl", { "settingsPage.general.hotkey.title": "RightAlt" }, t),
    null
  );
  assert.equal(
    validateHotkeyForSlot("RightControl", { "settingsPage.general.hotkey.title": "Control+Shift+Space" }, t),
    null
  );
});

test("the English clash message carries both the key and the slot", () => {
  const en = require("../../src/locales/en/translation.json");
  const text = en.hotkey.errors.slotConflict;
  assert.match(text, /\{\{hotkey\}\}/);
  assert.match(text, /\{\{slot\}\}/);
});

test("the pill's Stop / Open requests: stop only stops, open never touches the recorder", async () => {
  const { meetingHotkeyAction } = await loadAction();
  assert.equal(meetingHotkeyAction("recording", "stop"), "stop");
  assert.equal(meetingHotkeyAction("paused", "stop"), "stop");
  assert.equal(meetingHotkeyAction("idle", "stop"), "ignore");
  assert.equal(meetingHotkeyAction("idle", "open"), "ignore");
  assert.equal(meetingHotkeyAction("recording", "open"), "ignore");
  const gate = new PendingHotkeyPress();
  gate.press("stop");
  assert.equal(gate.take(), "stop");
  gate.press("open");
  assert.equal(gate.take(), "open");
  gate.press("bogus");
  assert.equal(gate.take(), "toggle");
});

test("pill status: recording / paused label with the meeting clock (pauses excluded)", async () => {
  const { meetingPillStatus } = await loadAction();
  assert.deepEqual(meetingPillStatus(null, 0), { active: false, paused: false, elapsedMs: 0, label: "" });
  assert.equal(meetingPillStatus({ phase: "idle", accumulatedMs: 0, runStartedAt: 0 }, 0).active, false);
  const rec = meetingPillStatus({ phase: "recording", accumulatedMs: 60000, runStartedAt: 1000 }, 6000);
  assert.deepEqual(rec, { active: true, paused: false, elapsedMs: 65000, label: "Recording meeting · 01:05" });
  const paused = meetingPillStatus({ phase: "paused", accumulatedMs: 3723000, runStartedAt: 0 }, 99999999);
  assert.equal(paused.label, "Meeting paused · 1:02:03");
  assert.equal(paused.paused, true);
});
