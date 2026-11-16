const test = require("node:test");
const assert = require("node:assert/strict");

let hs;

test.before(async () => {
  const path = require("node:path");
  const { pathToFileURL } = require("node:url");
  const root = process.env.OPENWHISPR_TEST_SOURCE || path.resolve(__dirname, "../..");
  hs = await import(pathToFileURL(path.join(root, "src/utils/hindiScript.js")));
});

test("spoken email addresses become real ones", () => {
  assert.equal(hs.fixSpokenEmails("ravi.s at the rate gmail.com"), "ravi.s@gmail.com");
  assert.equal(hs.fixSpokenEmails("Ravi dot S at the rate gmail dot com"), "ravi.s@gmail.com");
  assert.equal(
    hs.fixSpokenEmails("Mail me at ravi underscore k at the red yahoo dot co.in please"),
    "Mail me at ravi_k@yahoo.co.in please"
  );
  assert.equal(hs.fixSpokenEmails("send to ravi at outlook dot com"), "send to ravi@outlook.com");
  assert.equal(hs.fixSpokenEmails("ravi@gmail.com"), "ravi@gmail.com");
});

test("ordinary sentences are left alone", () => {
  assert.equal(hs.fixSpokenEmails("Meet me at the station at 5."), "Meet me at the station at 5.");
  assert.equal(hs.fixSpokenEmails("I am at home dot dot dot"), "I am at home dot dot dot");
});

test("addresses always go through cleanup", () => {
  assert.equal(hs.isShortCleanUtterance("ravi at the rate gmail.com", "roman"), false);
  assert.equal(hs.isShortCleanUtterance("Thanks, see you tomorrow.", "roman"), true);
});

test("Roman guard only fires for leftover Devanagari in Roman mode", () => {
  assert.equal(hs.needsRomanFix("Kal meeting hai, ठीक है.", "auto", "roman"), true);
  assert.equal(hs.needsRomanFix("Kal meeting hai, theek hai.", "auto", "roman"), false);
  assert.equal(hs.needsRomanFix("कल meeting है", "auto", "devanagari"), false);
  assert.equal(hs.needsRomanFix("कल meeting है", "en", "roman"), false);
});

test("greetings are kept and the account email is spelled out", () => {
  assert.match(hs.getHindiCleanupInstruction("roman"), /haan bhai, arre, ji/);
  assert.equal(hs.accountEmailInstruction(""), "");
  assert.match(hs.accountEmailInstruction("ravi@gmail.com"), /ravi@gmail\.com/);
});
