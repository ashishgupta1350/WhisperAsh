const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  WhisperAshMeetings,
  MeetingStopError,
  formatStamp,
  applyOffset,
  deepgramLines,
  groqLines,
  parseSummary,
  joinTranscript,
  meetingEngine,
  resolveTarget,
  transcribePart,
  summarize,
  splitByWords,
  PROMPTS,
} = require("../../src/helpers/whisperashMeetings");

const AUDIO = Buffer.from("fake-webm-audio");

function jsonResponse(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function geminiAnswer(text) {
  return { candidates: [{ content: { parts: [{ text }] } }] };
}

// Routes fake provider calls by URL and request kind; records every call.
function makeFetch(handlers) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    let kind;
    if (url.includes(":generateContent")) {
      kind = String(init.body).includes("inline_data") ? "geminiAudio" : "geminiText";
    } else if (url.includes("/v1/listen")) kind = "deepgram";
    else if (url.includes("/audio/transcriptions")) kind = "groqStt";
    else if (url.includes("/chat/completions")) kind = "groqChat";
    calls.push({ kind, url, init });
    const handler = handlers[kind];
    if (!handler) throw new Error(`unexpected call ${kind} ${url}`);
    return handler(url, init);
  };
  return { fetchImpl, calls };
}

const OWN_ALL = { gemini: "g-key", deepgram: "d-key", groq: "q-key" };
const ACCOUNT = { base: "https://ws.example.dev/", token: "session-token" };

// sleeps records the busy-retry waits instead of waiting.
function ctx(fetchImpl, { keys = OWN_ALL, account = null, sleeps = [] } = {}) {
  return { fetch: fetchImpl, keys, account, mimeType: "audio/webm", sleep: async (ms) => sleeps.push(ms) };
}

// ---------- chain order and fallbacks ----------

test("Gemini answers first: engine gemini, no other provider called", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(200, geminiAnswer("[00:01] Speaker 1: haan, kal tak bhej dunga\n[00:05] Speaker 2: theek hai")),
  });
  const result = await transcribePart(ctx(fetchImpl), AUDIO, { n: 1, total: 2, previous: null });
  assert.equal(result.engine, "gemini");
  assert.deepEqual(result.lines, ["[00:01] Speaker 1: haan, kal tak bhej dunga", "[00:05] Speaker 2: theek hai"]);
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio"]);
  const body = JSON.parse(calls[0].init.body);
  assert.match(body.contents[0].parts[0].text, /^This is part 1 of 2 of a meeting recording\./);
  assert.equal(body.contents[0].parts[1].inline_data.mime_type, "audio/webm");
  assert.equal(body.generationConfig.maxOutputTokens, 16384);
});

test("Gemini fails -> Deepgram, then the ROMANIZE pass", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(500, { error: { message: "boom" } }),
    deepgram: () =>
      jsonResponse(200, {
        results: { utterances: [{ start: 3.2, speaker: 0, transcript: "हाँ ठीक है" }, { start: 65, speaker: 1, transcript: "okay done" }] },
      }),
    geminiText: () => jsonResponse(200, geminiAnswer("[00:03] Speaker 1: haan theek hai\n[01:05] Speaker 2: okay done")),
  });
  const result = await transcribePart(ctx(fetchImpl), AUDIO, { n: 1, total: 1 });
  assert.equal(result.engine, "deepgram");
  assert.deepEqual(result.lines, ["[00:03] Speaker 1: haan theek hai", "[01:05] Speaker 2: okay done"]);
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "deepgram", "geminiText"]);
  assert.match(calls[1].url, /model=nova-3&language=multi&diarize=true&utterances=true/);
  assert.equal(calls[1].init.headers.authorization, "Token d-key");
  const romanizeBody = JSON.parse(calls[2].init.body);
  assert.equal(romanizeBody.systemInstruction.parts[0].text, PROMPTS.ROMANIZE_PROMPT);
});

test("Gemini and Deepgram fail -> Groq whisper-large-v3, romanized by Groq chat when Gemini text fails", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: async () => {
      throw new Error("network down");
    },
    deepgram: () => jsonResponse(502, { error: "bad gateway" }),
    groqStt: () =>
      jsonResponse(200, {
        segments: [
          { start: 0, text: " namaste" },
          { start: 8, text: " sab log" },
          { start: 21, text: " next topic" },
        ],
      }),
    geminiText: () => jsonResponse(200, geminiAnswer("")),
    groqChat: () =>
      jsonResponse(200, { choices: [{ finish_reason: "stop", message: { content: "[00:00] namaste sab log\n[00:21] next topic" } }] }),
  });
  const result = await transcribePart(ctx(fetchImpl), AUDIO, { n: 2, total: 3 });
  assert.equal(result.engine, "groq");
  assert.deepEqual(result.lines, ["[00:00] namaste sab log", "[00:21] next topic"]);
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "deepgram", "groqStt", "geminiText", "groqChat"]);
  const sttBody = calls[2].init.body.toString();
  assert.match(sttBody, /name="model"\r\n\r\nwhisper-large-v3/);
  assert.match(sttBody, /name="response_format"\r\n\r\nverbose_json/);
  assert.doesNotMatch(sttBody, /name="language"/);
  assert.equal(JSON.parse(calls[4].init.body).model, "openai/gpt-oss-120b");
});

test("every provider fails: the part throws with each engine's reason", async () => {
  const { fetchImpl } = makeFetch({
    geminiAudio: () => jsonResponse(500, {}),
    deepgram: () => jsonResponse(500, {}),
    groqStt: () => jsonResponse(500, {}),
  });
  await assert.rejects(
    transcribePart(ctx(fetchImpl), AUDIO, { n: 1, total: 1 }),
    (err) => !err.stop && /Gemini/.test(err.message) && /Deepgram/.test(err.message) && /Groq/.test(err.message)
  );
});

test("account mode: 429 meeting_limit stops the chain (no fallback)", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () =>
      jsonResponse(429, { error: { code: "meeting_limit", message: "No meeting minutes left today." } }),
  });
  await assert.rejects(
    transcribePart(ctx(fetchImpl, { keys: {}, account: ACCOUNT }), AUDIO, { n: 1, total: 1 }),
    (err) => err instanceof MeetingStopError && err.code === "meeting_limit" && err.message === "No meeting minutes left today."
  );
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio"]);
});

test("account mode: 401 session_expired and 403 account_blocked also stop", async () => {
  for (const [status, code] of [
    [401, "session_expired"],
    [403, "account_blocked"],
  ]) {
    const { fetchImpl, calls } = makeFetch({ geminiAudio: () => jsonResponse(status, { error: { code, message: code } }) });
    await assert.rejects(transcribePart(ctx(fetchImpl, { keys: {}, account: ACCOUNT }), AUDIO, { n: 1, total: 1 }), {
      code,
    });
    assert.equal(calls.length, 1);
  }
});

test("account mode: upstream_busy (retried once) / no_key_configured fall back to the next provider", async () => {
  const sleeps = [];
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(429, { error: { code: "upstream_busy", message: "busy" } }),
    deepgram: () => jsonResponse(503, { error: { code: "no_key_configured", message: "no key" } }),
    groqStt: () => jsonResponse(200, { segments: [{ start: 1, text: "hello" }] }),
    geminiText: () => jsonResponse(200, geminiAnswer("[00:01] hello")),
  });
  const result = await transcribePart(ctx(fetchImpl, { keys: {}, account: ACCOUNT, sleeps }), AUDIO, { n: 1, total: 1 });
  assert.equal(result.engine, "groq");
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "geminiAudio", "deepgram", "groqStt", "geminiText"]);
  assert.deepEqual(sleeps, [5000]);
});

test("own key: a 429 with code meeting_limit is only Gemini busy: one retry, then the chain falls back", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(429, { error: { code: "meeting_limit", message: "x" } }),
    deepgram: () => jsonResponse(200, { results: { utterances: [] } }),
  });
  const result = await transcribePart(ctx(fetchImpl), AUDIO, { n: 1, total: 1 });
  assert.equal(result.engine, "deepgram");
  assert.deepEqual(result.lines, []);
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "geminiAudio", "deepgram"]);
});

// ---------- Gemini busy retry ----------

test("Gemini 503 busy: wait 5 s, retry the same call once, and use its answer", async () => {
  const sleeps = [];
  let n = 0;
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => (++n === 1 ? jsonResponse(503, { error: { message: "overloaded" } }) : jsonResponse(200, geminiAnswer("[00:00] Speaker 1: ab theek"))),
  });
  const result = await transcribePart(ctx(fetchImpl, { sleeps }), AUDIO, { n: 1, total: 1 });
  assert.deepEqual(result, { engine: "gemini", lines: ["[00:00] Speaker 1: ab theek"] });
  assert.deepEqual(sleeps, [5000]);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].init.body, calls[1].init.body); // the same call
});

test("Gemini busy twice: no third try, the next provider takes the part", async () => {
  const sleeps = [];
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(503, {}),
    deepgram: () => jsonResponse(200, { results: { utterances: [] } }),
  });
  const result = await transcribePart(ctx(fetchImpl, { sleeps }), AUDIO, { n: 1, total: 1 });
  assert.equal(result.engine, "deepgram");
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "geminiAudio", "deepgram"]);
  assert.deepEqual(sleeps, [5000]);
});

test("Gemini 500 or the gateway's no_key_configured are not busy: no retry", async () => {
  for (const [status, body] of [
    [500, {}],
    [503, { error: { code: "no_key_configured", message: "no key" } }],
  ]) {
    const sleeps = [];
    const { fetchImpl, calls } = makeFetch({
      geminiAudio: () => jsonResponse(status, body),
      deepgram: () => jsonResponse(200, { results: { utterances: [] } }),
    });
    await transcribePart(ctx(fetchImpl, { keys: {}, account: ACCOUNT, sleeps }), AUDIO, { n: 1, total: 1 });
    assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "deepgram"]);
    assert.deepEqual(sleeps, []);
  }
});

test("account 429 meeting_limit on a ROMANIZE / SUMMARY text call stops without a retry", async () => {
  const sleeps = [];
  const { fetchImpl, calls } = makeFetch({
    geminiText: () => jsonResponse(429, { error: { code: "meeting_limit", message: "used up" } }),
  });
  await assert.rejects(summarize(ctx(fetchImpl, { keys: {}, account: ACCOUNT, sleeps }), "[00:00] x"), { code: "meeting_limit" });
  assert.equal(calls.length, 1);
  assert.deepEqual(sleeps, []);
});

test("every Gemini call uses gemini-3.5-flash-lite (audio, ROMANIZE, SUMMARY)", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(200, geminiAnswer("[00:00] Speaker 1: नमस्ते")),
    geminiText: () => jsonResponse(200, geminiAnswer("Title: T\n[00:00] Speaker 1: namaste")),
  });
  await transcribePart(ctx(fetchImpl), AUDIO, { n: 1, total: 1 }); // Devanagari -> ROMANIZE
  await summarize(ctx(fetchImpl), "[00:00] Speaker 1: namaste");
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "geminiText", "geminiText"]);
  for (const c of calls) {
    assert.match(c.url, /\/models\/gemini-3\.5-flash-lite:generateContent$/);
  }
});

test("TRANSCRIBE prompt: the filler-sounds line, word for word", () => {
  assert.ok(
    PROMPTS.TRANSCRIBE_PROMPT.includes(
      "Write (unclear) for words you cannot make out. Leave out filler sounds (uh, um, ah, hmm), silence, music and background noise. Write nothing else: no heading, no summary, no notes."
    )
  );
});

// ---------- own key vs account ----------

test("resolveTarget: own key wins, else account gateway, else skip", () => {
  assert.deepEqual(resolveTarget("gemini", { keys: { gemini: " k " }, account: ACCOUNT }), {
    mode: "own",
    base: "https://generativelanguage.googleapis.com",
    key: "k",
  });
  assert.deepEqual(resolveTarget("deepgram", { keys: {}, account: ACCOUNT }), {
    mode: "account",
    base: "https://ws.example.dev/deepgram",
    key: "session-token",
  });
  assert.equal(resolveTarget("groq", { keys: {}, account: null }), null);
  assert.equal(resolveTarget("groq", { keys: {}, account: { base: "https://x", token: "" } }), null);
});

test("own key calls go direct with the provider's own auth header", async () => {
  const { fetchImpl, calls } = makeFetch({ geminiAudio: () => jsonResponse(200, geminiAnswer("[00:00] Speaker 1: hi")) });
  await transcribePart(ctx(fetchImpl, { keys: { gemini: "g-key" }, account: ACCOUNT }), AUDIO, { n: 1, total: 1 });
  assert.equal(
    calls[0].url,
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"
  );
  assert.equal(calls[0].init.headers["x-goog-api-key"], "g-key");
  assert.equal(calls[0].init.headers.authorization, undefined);
  assert.equal(calls[0].init.headers["x-whisperash-purpose"], undefined);
});

test("account calls go to the gateway with the session token and the meeting purpose", async () => {
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(500, {}),
    deepgram: () => jsonResponse(200, { results: { utterances: [{ start: 0, speaker: 2, transcript: "ok" }] } }),
    geminiText: () => jsonResponse(200, geminiAnswer("[00:00] Speaker 3: ok")),
  });
  await transcribePart(ctx(fetchImpl, { keys: {}, account: ACCOUNT }), AUDIO, { n: 1, total: 1 });
  assert.equal(calls[0].url, "https://ws.example.dev/gemini/v1beta/models/gemini-3.5-flash-lite:generateContent");
  assert.match(calls[1].url, /^https:\/\/ws\.example\.dev\/deepgram\/v1\/listen\?/);
  for (const c of calls) {
    assert.equal(c.init.headers.authorization, "Bearer session-token");
    assert.equal(c.init.headers["x-whisperash-purpose"], "meeting");
    assert.equal(c.init.headers["x-goog-api-key"], undefined);
  }
});

test("signed out with only a Groq key: Gemini and Deepgram are skipped, Groq goes direct", async () => {
  const { fetchImpl, calls } = makeFetch({
    groqStt: () => jsonResponse(200, { segments: [{ start: 0, text: "नमस्ते" }] }),
    groqChat: () => jsonResponse(200, { choices: [{ finish_reason: "stop", message: { content: "[00:00] namaste" } }] }),
  });
  const result = await transcribePart(
    ctx(fetchImpl, { keys: { groq: "q-key" }, account: null }),
    AUDIO,
    { n: 1, total: 1 }
  );
  assert.equal(result.engine, "groq");
  assert.deepEqual(result.lines, ["[00:00] namaste"]);
  // ROMANIZE has no Gemini either, so it goes to Groq chat with the same own key.
  assert.deepEqual(calls.map((c) => c.kind), ["groqStt", "groqChat"]);
  assert.equal(calls[0].url, "https://api.groq.com/openai/v1/audio/transcriptions");
  for (const c of calls) assert.equal(c.init.headers.authorization, "Bearer q-key");
});

test("ROMANIZE failing everywhere keeps the backup engine's lines as they are", async () => {
  const { fetchImpl } = makeFetch({
    deepgram: () => jsonResponse(200, { results: { utterances: [{ start: 2, speaker: 0, transcript: "ठीक है" }] } }),
    geminiText: () => jsonResponse(500, {}),
    groqChat: () => jsonResponse(500, {}),
  });
  const result = await transcribePart(
    ctx(fetchImpl, { keys: { deepgram: "d", groq: "q" } }),
    AUDIO,
    { n: 1, total: 1 }
  );
  assert.deepEqual(result, { engine: "deepgram", lines: ["[00:02] Speaker 1: ठीक है"] });
});

test("no key and not signed in: a clear error, no calls", async () => {
  const { fetchImpl, calls } = makeFetch({});
  await assert.rejects(transcribePart(ctx(fetchImpl, { keys: {}, account: null }), AUDIO, { n: 1, total: 1 }), /sign in/i);
  assert.equal(calls.length, 0);
});

test("the previous part's last lines go into the TRANSCRIBE prompt", async () => {
  const { fetchImpl, calls } = makeFetch({ geminiAudio: () => jsonResponse(200, geminiAnswer("[00:00] Speaker 1: aage")) });
  await transcribePart(ctx(fetchImpl), AUDIO, { n: 2, total: 2, previous: ["[14:58] Speaker 2: chalo"] });
  const prompt = JSON.parse(calls[0].init.body).contents[0].parts[0].text;
  assert.match(prompt, /This is part 2 of 2/);
  assert.match(prompt, /The previous part ended with these lines\. Keep the same speaker numbers for the same voices:\n\[14:58\] Speaker 2: chalo$/);
});

// ---------- summary ----------

test("parseSummary: Title line becomes the title, the rest is kept as is", () => {
  const text = "Title: Budget review for the Q3 launch\nSummary:\nWe agreed.\nKey points:\n- one\nDecisions:\n- None\nAction items:\n- Ravi → send deck → Friday";
  const parsed = parseSummary(text);
  assert.equal(parsed.title, "Budget review for the Q3 launch");
  assert.equal(parsed.summary, "Summary:\nWe agreed.\nKey points:\n- one\nDecisions:\n- None\nAction items:\n- Ravi → send deck → Friday");
  assert.equal(parseSummary("**Title:** Weekly sync\nSummary:\nx").title, "Weekly sync");
  assert.deepEqual(parseSummary("Summary:\nno title"), { title: "", summary: "Summary:\nno title" });
});

test("summarize: Gemini fails -> Groq chat; both fail -> empty (caller uses the default title)", async () => {
  const groqOnly = makeFetch({
    geminiText: () => jsonResponse(503, {}),
    groqChat: () => jsonResponse(200, { choices: [{ finish_reason: "stop", message: { content: "Title: Plan\nSummary:\nok" } }] }),
  });
  assert.deepEqual(await summarize(ctx(groqOnly.fetchImpl), "[00:00] x"), { title: "Plan", summary: "Summary:\nok" });
  assert.equal(JSON.parse(groqOnly.calls[0].init.body).systemInstruction.parts[0].text, PROMPTS.SUMMARY_PROMPT);

  const none = makeFetch({ geminiText: () => jsonResponse(500, {}), groqChat: () => jsonResponse(500, {}) });
  assert.deepEqual(await summarize(ctx(none.fetchImpl), "[00:00] x"), { title: "", summary: "" });
});

// ---------- timing ----------

test("formatStamp: mm:ss, then h:mm:ss from one hour", () => {
  assert.equal(formatStamp(0), "[00:00]");
  assert.equal(formatStamp(65.9), "[01:05]");
  assert.equal(formatStamp(3599), "[59:59]");
  assert.equal(formatStamp(3600), "[1:00:00]");
  assert.equal(formatStamp(2 * 3600 + 61), "[2:01:01]");
});

test("applyOffset adds the part's start to every stamp", () => {
  const lines = ["[00:05] Speaker 1: a", "[14:59] Speaker 2: b", "no stamp line"];
  assert.deepEqual(applyOffset(lines, 15 * 60 * 1000), ["[15:05] Speaker 1: a", "[29:59] Speaker 2: b", "no stamp line"]);
  assert.deepEqual(applyOffset(["[00:10] c"], 4 * 15 * 60 * 1000), ["[1:00:10] c"]);
  assert.deepEqual(applyOffset(["[1:02:03] d"], 0), ["[1:02:03] d"]);
});

test("joinTranscript: parts in order with offsets; backup parts noted", () => {
  const parts = [
    { index: 1, startMs: 900000, engine: "groq", lines: ["[00:02] two"] },
    { index: 0, startMs: 0, engine: "gemini", lines: ["[00:01] Speaker 1: one"] },
  ];
  assert.equal(
    joinTranscript(parts),
    "[00:01] Speaker 1: one\n(Part 2: Speakers not separated (backup engine))\n[15:02] two"
  );
  assert.equal(meetingEngine(parts), "mixed");
  assert.equal(meetingEngine([{ engine: "gemini" }, { engine: "gemini" }]), "gemini");
  assert.equal(joinTranscript([{ index: 0, startMs: 0, engine: "groq", lines: ["[00:00] x"] }]), "(Speakers not separated (backup engine))\n[00:00] x");
});

test("deepgramLines numbers speakers from 1; groqLines merges segments into ~20 s lines", () => {
  assert.deepEqual(
    deepgramLines({ results: { utterances: [{ start: 61.4, speaker: 0, transcript: " hi " }, { start: 70, speaker: 1, transcript: "" }] } }),
    ["[01:01] Speaker 1: hi"]
  );
  assert.equal(deepgramLines({}), null);
  assert.deepEqual(
    groqLines({
      segments: [
        { start: 0, text: "a" },
        { start: 10, text: "b" },
        { start: 19.9, text: "c" },
        { start: 20.5, text: "d" },
        { start: 45, text: "e" },
      ],
    }),
    ["[00:00] a b c", "[00:20] d", "[00:45] e"]
  );
});

test("splitByWords keeps ROMANIZE calls under the word cap", () => {
  const lines = ["one two three", "four five", "six seven eight nine"];
  assert.deepEqual(splitByWords(lines, 5), [["one two three", "four five"], ["six seven eight nine"]]);
});

// ---------- the meeting store end to end (temp folder, fake providers) ----------

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "wa-meetings-"));
}

test("a diary uses Gemini, has a dated title, and syncs only within its account", async (t) => {
  const dir = tmpDir();
  t.after(() => {
    // The test owns this directory, created by mkdtemp.
    fs.rmSync(dir, { recursive: true, force: true });
  });
  let owner = "test-owner";
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () => jsonResponse(200, geminiAnswer("[00:00] Kal main park gaya")),
    geminiText: () => jsonResponse(200, geminiAnswer("Title: Walk in the park\nSummary:\nI went to the park.")),
  });
  const store = new WhisperAshMeetings({
    dir, getOwner: () => owner, environmentManager: { getGeminiKey: () => "test-key" }, fetch: fetchImpl,
  });
  const { id } = store.start({ kind: "diaries" });
  store.append({ id, part: 0, data: AUDIO, partStartMs: 0 });
  store.stop({ id, durationMs: 5000 });
  await waitFor(store, id);
  const diary = store.get({ id });
  assert.match(diary.title, /\d{4} · Walk in the park$/);
  assert.equal(diary.summary, "I went to the park.");
  assert.deepEqual(calls.map((c) => c.kind), ["geminiAudio", "geminiText"]);
  assert.match(calls[0].init.body, /personal voice diary/);
  assert.match(calls[1].init.body, /first person/);
  assert.equal(store.list().length, 0);
  assert.equal(store.list({ kind: "diaries" }).length, 1);
  assert.ok(store.syncItems("diaries")[0].data.transcript);
  assert.equal(store.syncItems("diaries")[0].data.audio, undefined);
  owner = "test-other-owner";
  assert.equal(store.list({ kind: "diaries" }).length, 0);
  assert.equal(store.get({ id }), null);
  assert.equal(store.applyRemote({ kind: "diaries", id, deleted: true }), false);
  owner = "test-owner";
  assert.equal(store.list({ kind: "diaries" }).length, 1);
});

function waitFor(store, id) {
  return store.processing.get(id) || Promise.resolve();
}

test("store: record two parts, transcribe with offsets, summary, audio kept, sync item", async () => {
  const dir = tmpDir();
  let n = 0;
  const { fetchImpl } = makeFetch({
    geminiAudio: () => {
      n += 1;
      return jsonResponse(200, geminiAnswer(n === 1 ? "[00:03] Speaker 1: pehla" : "[00:04] Speaker 1: doosra"));
    },
    geminiText: () => jsonResponse(200, geminiAnswer("Title: Two part meeting\nSummary:\nShort.")),
  });
  const events = [];
  const store = new WhisperAshMeetings({
    dir,
    environmentManager: { getGeminiKey: () => "g", getDeepgramKey: () => "", getGroqKey: () => "" },
    fetch: fetchImpl,
    broadcast: (ch, p) => events.push(p),
  });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.append({ id, part: 0, data: Buffer.from("bb"), partStartMs: 0 });
  store.append({ id, part: 1, data: Buffer.from("cc"), partStartMs: 900000 });
  assert.equal(fs.readFileSync(path.join(dir, id, "part-1.webm"), "utf8"), "aabb");
  store.stop({ id, durationMs: 1000000 });
  await waitFor(store, id);
  const m = store.get({ id });
  assert.equal(m.status, "done");
  assert.equal(m.title, "Two part meeting");
  assert.equal(m.summary, "Summary:\nShort.");
  assert.equal(m.transcript, "[00:03] Speaker 1: pehla\n[15:04] Speaker 1: doosra");
  assert.equal(m.engine, "gemini");
  assert.equal(m.durationMs, 1000000);
  assert.equal(fs.existsSync(path.join(dir, id, "part-1.webm")), true, "kept for Re-transcribe");
  assert.ok(events.some((e) => e.stage === "transcribing" && e.partsTotal === 2));
  assert.deepEqual(store.syncItems()[0].data, {
    title: "Two part meeting",
    summary: "Summary:\nShort.",
    transcript: m.transcript,
    engine: "gemini",
    durationMs: 1000000,
    createdMs: m.createdMs,
    status: "done",
  });
  fs.rmSync(dir, { recursive: true, force: true });
});

test("store: a meeting_limit stop marks the meeting failed, keeps the audio, Retry finishes it", async () => {
  const dir = tmpDir();
  let limited = true;
  const { fetchImpl } = makeFetch({
    geminiAudio: () =>
      limited
        ? jsonResponse(429, { error: { code: "meeting_limit", message: "Meeting minutes used up for today." } })
        : jsonResponse(200, geminiAnswer("[00:01] Speaker 1: ho gaya")),
    geminiText: () => jsonResponse(500, {}),
    groqChat: () => jsonResponse(500, {}),
  });
  const store = new WhisperAshMeetings({
    dir,
    environmentManager: { getGeminiKey: () => "", getDeepgramKey: () => "", getGroqKey: () => "" },
    getAccount: () => ACCOUNT,
    fetch: fetchImpl,
  });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.stop({ id, durationMs: 5000 });
  await waitFor(store, id);
  let m = store.get({ id });
  assert.equal(m.status, "failed");
  assert.equal(m.error, "Meeting minutes used up for today.");
  assert.equal(m.hasAudio, true);
  assert.ok(fs.existsSync(path.join(dir, id, "part-1.webm")));

  limited = false;
  store.retry({ id });
  await waitFor(store, id);
  m = store.get({ id });
  assert.equal(m.status, "done");
  assert.equal(m.transcript, "[00:01] Speaker 1: ho gaya");
  assert.match(m.title, /^Meeting \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/); // summary failed everywhere
  assert.equal(m.summary, "");
  fs.rmSync(dir, { recursive: true, force: true });
});

test("store: cancel removes the meeting; a remote meeting and its delete apply", () => {
  const dir = tmpDir();
  const store = new WhisperAshMeetings({ dir, environmentManager: {}, fetch: async () => jsonResponse(500, {}) });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.cancel({ id });
  assert.equal(fs.existsSync(path.join(dir, id)), false);

  const remoteId = "3f2b1c9e-1111-4c2d-9a7b-abcdefabcdef";
  assert.equal(
    store.applyRemote({
      id: remoteId,
      data: { title: "Phone meeting", summary: "s", transcript: "t", engine: "deepgram", durationMs: 60000, createdMs: 5, status: "done" },
    }),
    true
  );
  assert.equal(store.list()[0].title, "Phone meeting");
  assert.deepEqual(store.syncData(remoteId), {
    title: "Phone meeting",
    summary: "s",
    transcript: "t",
    engine: "deepgram",
    durationMs: 60000,
    createdMs: 5,
    status: "done",
  });
  store.applyRemote({ id: remoteId, deleted: true });
  assert.equal(store.list().length, 0);
  assert.equal(store.applyRemote({ id: "../evil", data: {} }), false);
  fs.rmSync(dir, { recursive: true, force: true });
});

// ---------- Re-transcribe (better model) ----------
const { retranscribeDictation, CONSTANTS } = require("../../src/helpers/whisperashMeetings");

test("dictation re-transcribe: Gemini 3.6 Flash with minimal thinking and the dictation prompt", async () => {
  const { fetchImpl, calls } = makeFetch({ geminiAudio: () => jsonResponse(200, geminiAnswer(" haan, kal tak bhej dunga ")) });
  const text = await retranscribeDictation(ctx(fetchImpl), AUDIO);
  assert.equal(text, "haan, kal tak bhej dunga");
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/models\/gemini-3\.6-flash:generateContent$/);
  const body = JSON.parse(calls[0].init.body);
  assert.deepEqual(body.generationConfig.thinkingConfig, { thinkingLevel: "minimal", includeThoughts: false });
  assert.equal(body.contents[0].parts[0].text, PROMPTS.DICTATION_TRANSCRIBE_PROMPT);
  assert.equal(body.contents[0].parts[1].inline_data.mime_type, "audio/webm");
  assert.equal(calls[0].init.headers["x-goog-api-key"], "g-key");
  assert.equal(CONSTANTS.DICTATION_RETRANSCRIBE_TIMEOUT_MS, 4 * 60 * 1000);
});

test("dictation re-transcribe through the account: gateway, no meeting purpose header", async () => {
  const { fetchImpl, calls } = makeFetch({ geminiAudio: () => jsonResponse(200, geminiAnswer("ok")) });
  await retranscribeDictation(ctx(fetchImpl, { keys: {}, account: ACCOUNT }), AUDIO);
  assert.equal(calls[0].url, "https://ws.example.dev/gemini/v1beta/models/gemini-3.6-flash:generateContent");
  assert.equal(calls[0].init.headers.authorization, "Bearer session-token");
  assert.equal(calls[0].init.headers["x-whisperash-purpose"], undefined);
});

test("dictation re-transcribe: clear errors (no key, no audio, timeout, empty answer)", async () => {
  await assert.rejects(retranscribeDictation(ctx(makeFetch({}).fetchImpl, { keys: {}, account: null }), AUDIO), /Gemini key/);
  await assert.rejects(retranscribeDictation(ctx(makeFetch({}).fetchImpl), Buffer.alloc(0)), /no longer saved/);
  const slow = (url, init) =>
    new Promise((_resolve, reject) =>
      init.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })))
    );
  await assert.rejects(retranscribeDictation({ ...ctx(slow), timeoutMs: 20 }, AUDIO), /longer than 4 minutes/);
  const empty = makeFetch({ geminiAudio: () => jsonResponse(200, geminiAnswer("")) });
  await assert.rejects(retranscribeDictation(ctx(empty.fetchImpl), AUDIO), /no text/);
});

test("store: audio is kept after a meeting is done; Re-transcribe uses 3.6 Flash and Undo restores", async () => {
  const dir = tmpDir();
  let better = false;
  const { fetchImpl, calls } = makeFetch({
    geminiAudio: () =>
      jsonResponse(200, geminiAnswer(better ? "[00:01] Speaker 1: Vercel pe deploy" : "[00:01] Speaker 1: versal pe deploy")),
    geminiText: () => jsonResponse(200, geminiAnswer("Title: Deploy talk\nSummary:\nShort.")),
  });
  const store = new WhisperAshMeetings({
    dir,
    environmentManager: { getGeminiKey: () => "g", getDeepgramKey: () => "", getGroqKey: () => "" },
    fetch: fetchImpl,
  });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.stop({ id, durationMs: 4000 });
  await waitFor(store, id);
  let m = store.get({ id });
  assert.equal(m.status, "done");
  assert.equal(m.hasAudio, true, "audio stays for Re-transcribe");
  assert.equal(m.canUndoRetranscribe, false);

  better = true;
  store.retranscribe({ id });
  await waitFor(store, id);
  m = store.get({ id });
  assert.equal(m.transcript, "[00:01] Speaker 1: Vercel pe deploy");
  assert.equal(m.canUndoRetranscribe, true);
  const audioCalls = calls.filter((c) => c.kind === "geminiAudio");
  assert.match(audioCalls[0].url, /gemini-3\.5-flash-lite/);
  assert.match(audioCalls[1].url, /gemini-3\.6-flash:/);
  assert.deepEqual(JSON.parse(audioCalls[1].init.body).generationConfig.thinkingConfig, {
    thinkingLevel: "minimal",
    includeThoughts: false,
  });

  m = store.undoRetranscribe({ id });
  assert.equal(m.transcript, "[00:01] Speaker 1: versal pe deploy");
  assert.equal(m.canUndoRetranscribe, false);
  fs.rmSync(dir, { recursive: true, force: true });
});

test("store: a part that 3.6 Flash cannot redo keeps its earlier text, with a note", async () => {
  const dir = tmpDir();
  let better = false;
  const { fetchImpl } = makeFetch({
    geminiAudio: () => (better ? jsonResponse(500, {}) : jsonResponse(200, geminiAnswer("[00:01] Speaker 1: pehle"))),
    geminiText: () => jsonResponse(200, geminiAnswer("Title: T\nSummary:\nS")),
  });
  const store = new WhisperAshMeetings({
    dir,
    environmentManager: { getGeminiKey: () => "g", getDeepgramKey: () => "", getGroqKey: () => "" },
    fetch: fetchImpl,
  });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.stop({ id, durationMs: 4000 });
  await waitFor(store, id);
  better = true;
  store.retranscribe({ id });
  await waitFor(store, id);
  const m = store.get({ id });
  assert.equal(m.status, "done");
  assert.equal(m.transcript, "[00:01] Speaker 1: pehle");
  assert.match(m.error, /1 of 1 parts could not be redone/);
  fs.rmSync(dir, { recursive: true, force: true });
});

test("store: Re-transcribe refuses when the audio is gone; old audio is pruned after 30 days", () => {
  const dir = tmpDir();
  const store = new WhisperAshMeetings({ dir, environmentManager: {}, fetch: async () => jsonResponse(500, {}) });
  const id = "old-meeting-1";
  fs.mkdirSync(path.join(dir, id));
  fs.writeFileSync(path.join(dir, id, "part-1.webm"), "aa");
  fs.writeFileSync(
    path.join(dir, id, "meeting.json"),
    JSON.stringify({
      id,
      status: "done",
      createdMs: Date.now() - 31 * 864e5,
      parts: [{ index: 0, file: "part-1.webm", status: "done", lines: [] }],
    })
  );
  store._pruneOldAudio();
  assert.equal(fs.existsSync(path.join(dir, id, "part-1.webm")), false);
  assert.equal(store.get({ id }).hasAudio, false);
  assert.throws(() => store.retranscribe({ id }), /no longer saved/);
  fs.rmSync(dir, { recursive: true, force: true });
});

test("store: Stop asks main to open the dashboard on that meeting", async () => {
  const dir = tmpDir();
  const opened = [];
  const store = new WhisperAshMeetings({
    dir,
    environmentManager: {},
    fetch: async () => jsonResponse(500, {}),
    onStopped: (id) => opened.push(id),
  });
  const { id } = store.start();
  store.append({ id, part: 0, data: Buffer.from("aa"), partStartMs: 0 });
  store.stop({ id, durationMs: 1000 });
  assert.deepEqual(opened, [id]);
  await waitFor(store, id);
  fs.rmSync(dir, { recursive: true, force: true });
});
