// WhisperAsh benchmark: Groq Whisper models x cleanup models on real audio files.
// Uses the Groq key saved in WhisperAsh (decrypted on this PC; never printed).
//
// Run (from the app folder):
//   npx electron scripts/whisperash-bench.js <audio files...>
//
// Prints per-file latency and text for whisper-large-v3-turbo and whisper-large-v3,
// then cleanup latency for each LLM, and writes a JSON report next to the first file.
const { app } = require("electron");
const fs = require("fs");
const path = require("path");

app.setName("WhisperAsh");
app.setPath("userData", path.join(app.getPath("appData"), "WhisperAsh"));

const STT_MODELS = ["whisper-large-v3-turbo", "whisper-large-v3"];
const LLM_MODELS = (process.env.BENCH_LLMS || "openai/gpt-oss-20b,openai/gpt-oss-120b,qwen/qwen3.8-27b").split(",");
const GAP_MS = 3500; // stay under 20 requests/minute

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function mimeFor(file) {
  const ext = path.extname(file).toLowerCase();
  return { ".wav": "audio/wav", ".webm": "audio/webm", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".ogg": "audio/ogg", ".flac": "audio/flac" }[ext] || "application/octet-stream";
}

async function transcribe(key, file, model, { language, prompt } = {}) {
  const form = new FormData();
  form.append("file", new Blob([fs.readFileSync(file)], { type: mimeFor(file) }), path.basename(file));
  form.append("model", model);
  form.append("response_format", "verbose_json");
  if (language) form.append("language", language);
  if (prompt) form.append("prompt", prompt);
  const t0 = performance.now();
  const res = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { authorization: `Bearer ${key}` },
    body: form,
  });
  const ms = Math.round(performance.now() - t0);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) return { ms, error: body?.error?.message || `HTTP ${res.status}` };
  return { ms, text: body.text?.trim(), language: body.language, duration: body.duration, serverMs: res.headers.get("x-groq-region") ? undefined : undefined };
}

async function cleanup(key, model, system, text) {
  const t0 = performance.now();
  const body = {
    model,
    messages: [
      { role: "system", content: system },
      { role: "user", content: `<transcript>${text}</transcript>` },
    ],
    temperature: 0.2,
    max_completion_tokens: 1024,
  };
  if (model.startsWith("openai/gpt-oss")) {
    body.reasoning_effort = "low";
    body.include_reasoning = false;
  } else if (model.startsWith("qwen/")) {
    body.reasoning_effort = "none";
    body.reasoning_format = "hidden";
  }
  const res = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const ms = Math.round(performance.now() - t0);
  const json = await res.json().catch(() => ({}));
  if (!res.ok) return { ms, error: json?.error?.message || `HTTP ${res.status}` };
  return { ms, text: json.choices?.[0]?.message?.content?.trim(), tokens: json.usage?.total_tokens };
}

async function main() {
  const files = process.argv.slice(2).filter((a) => !a.startsWith("--") && fs.existsSync(a));
  if (!files.length) throw new Error("Give one or more audio files");

  const EnvironmentManager = require("../src/helpers/environment");
  const env = new EnvironmentManager();
  await env.init();
  const key = process.env.GROQ_API_KEY;
  if (!key) throw new Error("No Groq key saved in WhisperAsh yet (Settings > Speech to Text)");

  const enPrompts = require("../src/locales/en/prompts.json");
  const hindiRule =
    "LANGUAGE AND SCRIPT: The speaker may mix Hindi and English (Hinglish). Write Hindi words in Roman (Latin) script the way people type Hinglish in chats. Keep English words in normal English spelling. If the transcript contains Devanagari, transliterate it to Roman script. Never translate.";
  const system = enPrompts.cleanupPrompt.replace(/\{\{agentName\}\}/g, "Assistant") + "\n\n" + hindiRule;

  // Warm the connection once so the first measurement isn't a cold TLS handshake.
  await fetch("https://api.groq.com/openai/v1/models", { method: "HEAD" }).catch(() => {});

  const report = [];
  for (const file of files) {
    const entry = { file: path.basename(file), stt: {}, cleanup: {} };
    for (const model of STT_MODELS) {
      const r = await transcribe(key, file, model, { prompt: "Haan, kal meeting hai. Main report bhej dunga, okay?" });
      entry.stt[model] = r;
      console.log(`[stt] ${entry.file} ${model}: ${r.ms} ms ${r.error ? "ERROR " + r.error : `(${r.language}, ${r.duration}s) ${r.text}`}`);
      await sleep(GAP_MS);
    }
    const raw = entry.stt["whisper-large-v3"]?.text || entry.stt["whisper-large-v3-turbo"]?.text;
    if (raw) {
      for (const model of LLM_MODELS) {
        const r = await cleanup(key, model, system, raw);
        entry.cleanup[model] = r;
        console.log(`[llm] ${entry.file} ${model}: ${r.ms} ms ${r.error ? "ERROR " + r.error : r.text}`);
        await sleep(1500);
      }
    }
    report.push(entry);
  }

  const summary = {};
  for (const e of report) {
    for (const [m, r] of Object.entries({ ...e.stt, ...e.cleanup })) {
      if (r.error) continue;
      (summary[m] ||= []).push(r.ms);
    }
  }
  console.log("\nMedian latency (ms):");
  for (const [m, list] of Object.entries(summary)) {
    const s = [...list].sort((a, b) => a - b);
    console.log(`  ${m.padEnd(28)} ${s[Math.floor(s.length / 2)]}  (n=${s.length}, min ${s[0]}, max ${s[s.length - 1]})`);
  }
  const out = path.join(path.dirname(files[0]), "whisperash-bench-report.json");
  fs.writeFileSync(out, JSON.stringify(report, null, 2));
  console.log(`\nReport: ${out}`);
}

app.whenReady().then(async () => {
  try {
    await main();
  } catch (err) {
    console.error("Bench failed:", err.message);
    process.exitCode = 1;
  }
  app.quit();
});
