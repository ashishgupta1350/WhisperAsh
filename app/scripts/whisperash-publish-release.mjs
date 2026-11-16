// Publishes the Windows installer as a GitHub Release asset.
//   node scripts/whisperash-publish-release.mjs <installer.exe> <tag> [title]
// Auth: the GitHub login git already saved in Windows Credential Manager
// (`git credential fill`). The token is kept in memory only and never printed.
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const OWNER = "ashishgupta1350";
const REPO = "WhisperAsh";
const ASSET_NAME = "WhisperAsh-Setup.exe";
const [file, tag, title = `WhisperAsh ${tag} for Windows`] = process.argv.slice(2);
if (!file || !tag || !fs.existsSync(file)) {
  console.error("Usage: node scripts/whisperash-publish-release.mjs <installer.exe> <tag> [title]");
  process.exit(2);
}

function githubToken() {
  const r = spawnSync("git", ["credential", "fill"], {
    input: "protocol=https\nhost=github.com\n\n",
    encoding: "utf8",
    env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
  });
  const line = (r.stdout || "").split(/\r?\n/).find((l) => l.startsWith("password="));
  if (!line) throw new Error("No saved GitHub login found (push once with git to sign in).");
  return line.slice("password=".length);
}

const token = githubToken();
const api = async (url, opts = {}) => {
  const res = await fetch(url, {
    ...opts,
    headers: {
      authorization: `Bearer ${token}`,
      accept: "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
      "user-agent": "whisperash-release-script",
      ...(opts.headers || {}),
    },
  });
  const body = res.status === 204 ? null : await res.json().catch(() => null);
  if (!res.ok && res.status !== 404) {
    throw new Error(`${opts.method || "GET"} ${url.replace(/\?.*/, "")} -> ${res.status} ${body?.message || ""}`);
  }
  return { status: res.status, body };
};

const sizeMb = (fs.statSync(file).size / 1048576).toFixed(1);
const notes = [
  "Windows installer for WhisperAsh (voice dictation with Groq Whisper, Hinglish support and Cloudflare sync).",
  "",
  `**Download:** \`${ASSET_NAME}\` below (${sizeMb} MB).`,
  "",
  "1. Turn off Smart App Control (Windows Security > App & browser control). The app is not code-signed.",
  "2. Run the installer. If Windows says \"Windows protected your PC\", click More info > Run anyway.",
  "3. Paste a free Groq API key (console.groq.com) in Settings > Speech to Text.",
  "4. Tap Right Ctrl to start and stop dictation, or hold it while speaking.",
  "",
  "Full guide and unattended-install commands: see the README.",
].join("\n");

let release = (await api(`https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/${tag}`)).body;
if (!release?.id) {
  release = (
    await api(`https://api.github.com/repos/${OWNER}/${REPO}/releases`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ tag_name: tag, target_commitish: "main", name: title, body: notes, make_latest: "true" }),
    })
  ).body;
  console.log(`Created release ${release.tag_name}: ${release.html_url}`);
} else {
  console.log(`Using existing release ${release.tag_name}: ${release.html_url}`);
}

for (const asset of release.assets || []) {
  if (asset.name === ASSET_NAME) {
    await api(`https://api.github.com/repos/${OWNER}/${REPO}/releases/assets/${asset.id}`, { method: "DELETE" });
    console.log("Replaced the previous installer asset.");
  }
}

console.log(`Uploading ${path.basename(file)} as ${ASSET_NAME} (${sizeMb} MB)...`);
const data = fs.readFileSync(file);
const t0 = Date.now();
const up = await api(
  `https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${release.id}/assets?name=${encodeURIComponent(ASSET_NAME)}`,
  { method: "POST", headers: { "content-type": "application/octet-stream", "content-length": String(data.length) }, body: data }
);
console.log(`Uploaded in ${Math.round((Date.now() - t0) / 1000)} s: ${up.body.browser_download_url} (${up.body.size} bytes, state ${up.body.state})`);
