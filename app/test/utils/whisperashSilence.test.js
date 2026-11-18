const test = require("node:test");
const assert = require("node:assert/strict");

test("a stock phrase from a key-click recording is dropped; real speech is kept", async () => {
  const { isSilenceHallucination } = await import("../../src/utils/whisperashSilence.js");
  const tap = { windowCount: 6, speechWindowCount: 1 }; // hotkey on and straight off
  assert.equal(isSilenceHallucination("Thank you.", tap), true);
  assert.equal(isSilenceHallucination("Thanks for watching!", tap), true);
  assert.equal(isSilenceHallucination(" you ", tap), true);
  // The same words actually said (several speech windows) stay.
  assert.equal(isSilenceHallucination("Thank you.", { windowCount: 15, speechWindowCount: 7 }), false);
  // Anything else in a near-silent recording is left to the other checks.
  assert.equal(isSilenceHallucination("Send the report.", tap), false);
  assert.equal(isSilenceHallucination("Thank you for the update.", tap), false);
  // No gate numbers: nothing is dropped.
  assert.equal(isSilenceHallucination("Thank you.", null), false);
});
