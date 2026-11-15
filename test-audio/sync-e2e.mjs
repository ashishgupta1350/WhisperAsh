// End-to-end sync test between two WhisperAsh instances ("PC-A" on port 9223,
// "PC-B" on 9224) and a Worker running under `wrangler dev` on 127.0.0.1:8787.
//   node sync-e2e.mjs
import assert from "node:assert/strict";
import path from "node:path";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const WebSocket = require(path.join(import.meta.dirname, "..", "app", "node_modules", "ws"));

const SERVER = "http://127.0.0.1:8787";
const email = `sync-${Date.now()}@example.com`;
const password = "test password 123";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let passed = 0;

async function evalIn(port, expression) {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const target = targets.find((t) => t.type === "page" && t.url.includes("panel=true"));
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => ws.on("open", r));
  return new Promise((resolve, reject) => {
    ws.on("message", (raw) => {
      const msg = JSON.parse(raw);
      if (msg.id !== 1) return;
      ws.close();
      if (msg.result.exceptionDetails) reject(new Error(JSON.stringify(msg.result.exceptionDetails.exception?.description)));
      else resolve(msg.result.result.value);
    });
    ws.send(JSON.stringify({ id: 1, method: "Runtime.evaluate", params: { expression, awaitPromise: true, returnByValue: true } }));
  });
}
const A = (e) => evalIn(9223, e);
const B = (e) => evalIn(9224, e);
async function step(name, fn) {
  await fn();
  passed++;
  console.log(`  ok  ${name}`);
}
async function waitFor(fn, ms = 15000) {
  const end = Date.now() + ms;
  let last;
  while (Date.now() < end) {
    last = await fn();
    if (last) return last;
    await sleep(500);
  }
  throw new Error("timed out waiting");
}

const marker = `Kal meeting hai, main report bhej dunga. [${Date.now()}]`;

await step("PC-A: local history, dictionary and a setting before login (offline)", async () => {
  await A(`window.electronAPI.saveTranscription(${JSON.stringify(marker)}, "kal meeting hai main report bhej dunga", { status: "completed" })`);
  await A(`window.electronAPI.applyDictionaryChanges({ add: ["WhisperAsh", "Groq"] })`);
  await A(`localStorage.setItem("hindiScriptPreference", "devanagari"); window.dispatchEvent(new CustomEvent("whisperash-setting-changed")); true`);
  await sleep(2000);
});

await step("PC-A: create account (Worker hashes the password)", async () => {
  const r = await A(`window.whisperash.register({ serverUrl: "${SERVER}", email: "${email}", password: "${password}", signupCode: "local-test-code" })`);
  assert.equal(r.ok, true, r.error);
  assert.equal(r.state.signedIn, true);
});

await step("PC-A: first sync uploads everything", async () => {
  const s = await waitFor(async () => {
    const st = await A(`window.whisperash.getState()`);
    return st.lastSyncAt && st.pending === 0 && st.state === "idle" ? st : null;
  });
  assert.equal(s.lastError, null);
});

await step("PC-B: wrong password is rejected", async () => {
  const r = await B(`window.whisperash.login({ serverUrl: "${SERVER}", email: "${email}", password: "nope nope nope" })`);
  assert.equal(r.ok, false);
});

await step("PC-B: log in and receive history + dictionary", async () => {
  const r = await B(`window.whisperash.login({ serverUrl: "${SERVER}", email: "${email}", password: "${password}" })`);
  assert.equal(r.ok, true, r.error);
  await waitFor(async () => {
    const rows = await B(`window.electronAPI.getTranscriptions(50).then(r => (r.transcriptions || r).map(t => t.text))`);
    return rows.includes(marker);
  });
  const words = await B(`window.electronAPI.getDictionary()`);
  assert.ok(words.includes("WhisperAsh") && words.includes("Groq"), JSON.stringify(words));
});

await step("PC-B: settings from PC-A applied (Devanagari)", async () => {
  await waitFor(async () => (await B(`localStorage.getItem("hindiScriptPreference")`)) === "devanagari");
});

await step("PC-B: delete a dictionary word, PC-A sees it gone", async () => {
  await B(`window.electronAPI.applyDictionaryChanges({ remove: ["Groq"] })`);
  await sleep(6000); // debounce + push
  await B(`window.whisperash.syncNow()`);
  await A(`window.whisperash.syncNow()`);
  const words = await A(`window.electronAPI.getDictionary()`);
  assert.ok(!words.includes("Groq") && words.includes("WhisperAsh"), JSON.stringify(words));
});

await step("PC-A offline: change is kept and synced later", async () => {
  await A(`window.whisperash.setServerUrl("http://127.0.0.1:9")`);
  await A(`window.electronAPI.applyDictionaryChanges({ add: ["Saaras"] })`);
  const r = await A(`window.whisperash.syncNow()`);
  assert.ok(r.state.state === "offline" || r.state.state === "error", r.state.state);
  assert.ok(r.state.pending > 0);
  await A(`window.whisperash.setServerUrl("${SERVER}")`);
  const r2 = await A(`window.whisperash.syncNow()`);
  assert.equal(r2.state.pending, 0);
  await B(`window.whisperash.syncNow()`);
  const words = await B(`window.electronAPI.getDictionary()`);
  assert.ok(words.includes("Saaras"), JSON.stringify(words));
});

await step("PC-B: log out", async () => {
  const r = await B(`window.whisperash.logout()`);
  assert.equal(r.state.signedIn, false);
});

console.log(`\nAll ${passed} sync checks passed (account ${email}).`);
