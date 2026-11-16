// Hinglish regression test: runs saved recordings through the same Whisper
// settings and cleanup rule the app uses (src/utils/hindiScript.js) and prints
// what Whisper wrote and what would be pasted.
//   npx electron scripts/whisperash-hinglish-test.js <audio files...>
// Env: HINDI_SCRIPT=roman|devanagari|english (default roman), CLEANUP_MODEL.
const { app } = require("electron");
const fs = require("fs");
const path = require("path");
const { pathToFileURL } = require("url");
app.setName("WhisperAsh");
app.setPath("userData", path.join(app.getPath("appData"), "WhisperAsh"));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  const hs = await import(pathToFileURL(path.join(__dirname, "..", "src", "utils", "hindiScript.js")).href);
  const pref = process.env.HINDI_SCRIPT || "roman";
  const model = process.env.CLEANUP_MODEL || "qwen/qwen3.8-27b";
  const EnvironmentManager = require("../src/helpers/environment");
  await new EnvironmentManager().init();
  const key = process.env.GROQ_API_KEY;
  const enPrompts = require("../src/locales/en/prompts.json");
  const base = enPrompts.cleanupPrompt.replace(/\{\{agentName\}\}/g, "WhisperAsh");
  const rule = hs.getHindiCleanupInstruction(pref);
  const system = hs.hindiRuleGoesFirst(pref)
    ? `${rule}\n\n${base}\n\nREMINDER: never translate. Hindi stays Hindi (in the script chosen above), English stays English.`
    : `${base}\n\n${rule}`;
  const language = hs.getWhisperLanguageFor("auto", pref);
  const prompt = hs.getHindiWhisperBias("auto", pref);
  console.log(`settings: script=${pref} whisperLanguage=${language || "auto"} cleanup=${model}\n`);

  for (const file of process.argv.slice(2).filter((f) => fs.existsSync(f))) {
    const form = new FormData();
    form.append("file", new Blob([fs.readFileSync(file)], { type: "audio/webm" }), path.basename(file));
    form.append("model", "whisper-large-v3");
    if (language) form.append("language", language);
    if (prompt) form.append("prompt", prompt);
    let t0 = performance.now();
    const stt = await (await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
      method: "POST", headers: { authorization: `Bearer ${key}` }, body: form,
    })).json();
    const sttMs = Math.round(performance.now() - t0);
    const raw = (stt.text || stt.error?.message || "").trim();
    t0 = performance.now();
    const body = {
      model,
      messages: [{ role: "system", content: system }, { role: "user", content: `<transcript>${raw}</transcript>` }],
      temperature: 0.2,
      max_completion_tokens: 1500,
    };
    if (model.startsWith("qwen/")) { body.reasoning_effort = "none"; body.reasoning_format = "hidden"; }
    if (model.startsWith("openai/gpt-oss")) { body.reasoning_effort = "low"; body.include_reasoning = false; }
    const llm = await (await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST", headers: { authorization: `Bearer ${key}`, "content-type": "application/json" }, body: JSON.stringify(body),
    })).json();
    const llmMs = Math.round(performance.now() - t0);
    const out = (llm.choices?.[0]?.message?.content || llm.error?.message || "").trim();
    console.log(`=== ${path.basename(file)}  (whisper ${sttMs} ms, cleanup ${llmMs} ms)\nWHISPER: ${raw}\nPASTED:  ${out}\n`);
    await sleep(3200);
  }
}

app.whenReady().then(async () => {
  try { await main(); } catch (e) { console.error(e); process.exitCode = 1; }
  app.quit();
});
