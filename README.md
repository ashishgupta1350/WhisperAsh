# WhisperAsh

Personal voice dictation for Windows, in the style of Typeless. Press a hotkey in any app and speak
English, Hindi or Hinglish. WhisperAsh transcribes the speech with Groq Whisper and cleans it up
with a Groq LLM, which removes "um / uh", fixes punctuation and grammar, and handles self-corrections
such as "no wait, I mean…". The result is then pasted where your cursor is.

Your history, personal dictionary and settings sync between your PCs through your own Cloudflare
Worker. You log in with an email and password. Everything works offline and syncs later.

* `app/`: the desktop app. It is a fork of [OpenWhispr](https://github.com/OpenWhispr/openwhispr)
  v1.11.1 (MIT licence; see `app/LICENSE`), trimmed to dictation only and rebranded.
* `worker/`: the Cloudflare Worker and D1 database that handle login and sync.
* `test-audio/`: test clips, an end-to-end test script and a benchmark helper.

## Why this stack

* **Electron (Node), not Tauri.** This PC has Node but no Rust toolchain. OpenWhispr is the most
  popular open-source app of this kind (11.6k GitHub stars), it is MIT-licensed, and it already
  has Groq support, a hotkey listener, paste helpers, history and a dictionary. Forking it saved
  writing all of that from scratch.
* **Groq** has the most generous free speech-to-text allowance. Free-tier limits for
  `whisper-large-v3` and `whisper-large-v3-turbo` are 20 requests/min, 2,000/day, 7,200 audio
  seconds/hour and 28,800 audio seconds/day (about 8 hours a day). Every request is billed as at
  least 10 seconds, and uploads are capped at 25 MB. Source: <https://console.groq.com/docs/rate-limits>,
  checked 2026-11-15. The page says its table shows the base limits, so check your own limits at
  <https://console.groq.com/settings/limits>. WhisperAsh shows a usage counter under
  **Settings → Profile**.
* **Cleanup model.** Groq shut down the Llama 3.x chat models on 2026-08-16
  (<https://console.groq.com/docs/deprecations>). WhisperAsh therefore uses `openai/gpt-oss-20b`
  with low reasoning effort: it is fast, its free tier allows 1,000 requests and 200K tokens a day,
  and you can switch to `openai/gpt-oss-120b` in Settings → Language Model.

## What it does (matched to Typeless)

| Typeless feature | WhisperAsh |
|---|---|
| Global dictation hotkey in any app | **Right Ctrl**: tap to start, tap again to stop. Settings → Hotkeys switches to hold-to-talk or another key. |
| Filler removal, grammar, self-corrections, lists | LLM cleanup (Settings → Language Model; can be turned off). |
| Floating indicator while recording / processing | Small pill at the bottom of the screen. |
| Personal dictionary | Dictionary page. Words are sent to Whisper as hints and to the cleanup model as spellings. Synced. |
| Tone per app | Settings → Tone & style (personal / work / email apps). |
| History with copy / retry | Home page. Synced between PCs. |
| Translate | Settings → Preferences → Language → "Hindi / Hinglish output: Translate to English", or the separate translation hotkey. |
| Hindi + English + Hinglish | Output in **Roman Hinglish** (default, never translated), **Devanagari**, or **English**. |
| Tray icon | Right-click the tray icon for settings and quit. |
| Usage | Settings → Profile shows **free words left today and this month** (estimated from your own pace and Groq's free limits), plus request and audio meters. |
| Backup engine | If Groq fails, the same recording goes to Google Gemini automatically (free key from aistudio.google.com/apikey, Settings → Profile). |

## 1. Run it on this PC

The app is already installed in `app/`. To start it:

```powershell
cd C:\Users\onegr\Documents\practice-app-making\voice-dictation\app
```

```powershell
npm.cmd run whisperash
```

Windows **Smart App Control must be off**. WhisperAsh isn't code-signed, so Smart App Control
blocks it: Windows Security → App & browser control → Smart App Control → Off. If Windows
Firewall asks whether Electron may use public and private networks, click **Cancel**: WhisperAsh
only makes outgoing connections.

## 2. Groq account and API key (free, about 2 minutes)

1. Open <https://console.groq.com> and sign in with Google or email. No card is needed.
2. Open **API Keys** → **Create API Key**, name it `WhisperAsh`, and copy the key (it starts with `gsk_`).
3. In WhisperAsh, open **Settings → Speech to Text**. Groq is already selected. Paste the key into
   the Groq API key box.
4. Do the same under **Settings → Language Model** if it asks again (the same key works for both).

The key is stored only on this PC and encrypted with Windows DPAPI. It never goes into the
code, a command line or Cloudflare.

## 3. Cloudflare account, Worker and D1 (free tier)

You do this once. Your PCs then all log in to the same Worker.

1. Create a free account at <https://dash.cloudflare.com/sign-up> and verify your email.
2. Open PowerShell in the worker folder and install wrangler (local to this folder):

   ```powershell
   cd C:\Users\onegr\Documents\practice-app-making\voice-dictation\worker
   ```

   ```powershell
   npm.cmd install
   ```

3. Log in to Cloudflare. A browser window opens; click **Allow**:

   ```powershell
   npx.cmd wrangler login
   ```

4. Create the database:

   ```powershell
   npx.cmd wrangler d1 create whisperash
   ```

   Copy the `database_id` it prints into `worker\wrangler.toml`, replacing the zeros.
5. Create the tables in the cloud database:

   ```powershell
   npm.cmd run db:remote
   ```

6. Set the secrets. The first command generates a random pepper and pipes it straight into
   wrangler, so it never appears on screen. The second asks you to type a sign-up code that only you
   know; it stops strangers from creating accounts on your Worker.

   ```powershell
   $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) | npx.cmd wrangler secret put PEPPER
   ```

   ```powershell
   npx.cmd wrangler secret put SIGNUP_CODE
   ```

   Don't lose or change `PEPPER` after accounts exist: every password hash depends on it.
7. Deploy:

   ```powershell
   npm.cmd run deploy
   ```

   It prints your Worker URL, for example `https://whisperash-sync.<your-subdomain>.workers.dev`.
8. In WhisperAsh, open **Settings → Profile**. Paste the Worker URL and enter your email, a
   password (8+ characters) and the sign-up code, then click **Create account**.

**What the Worker stores:** your email; your password as a PBKDF2-SHA256 hash (100,000 iterations,
per-user salt, server-side pepper), never the password itself; and session tokens as SHA-256
hashes only. It also stores your dictation history, dictionary and synced settings. History text is
stored as-is in your D1 database (readable by your Cloudflare account), so don't dictate secrets
you wouldn't keep there. After 8 wrong passwords, logins for that email are locked for 15 minutes.

### Test the Worker locally (no Cloudflare account needed)

```powershell
cd C:\Users\onegr\Documents\practice-app-making\voice-dictation\worker
```

`.dev.vars` holds local-only test secrets. The setup already created it; it is git-ignored.

```powershell
npm.cmd run db:local; npm.cmd run dev
```

Then, in a second PowerShell window, from the same folder:

```powershell
$env:SIGNUP_CODE = 'local-test-code'; node test/api.test.mjs
```

## 4. Install on a second PC

1. On the second PC: turn off Smart App Control (see above) and install Node.js LTS from
   <https://nodejs.org>.
2. Copy the whole `voice-dictation` folder over (USB, OneDrive or `git clone`). Leave out the
   `node_modules` folders.
3. In PowerShell:

   ```powershell
   cd <where you put it>\voice-dictation\app
   ```

   ```powershell
   npm.cmd install --ignore-scripts; node node_modules/electron/install.js; npx.cmd electron-builder install-app-deps
   ```

   ```powershell
   npm.cmd run whisperash
   ```

4. In WhisperAsh: paste your Groq key (Settings → Speech to Text), then open Settings → Profile,
   enter the same Worker URL, email and password, and click **Log in**. History, dictionary and
   settings download within a few seconds.

The Groq key is entered per PC on purpose: it is never uploaded to Cloudflare. Settings that
depend on the device (microphone and hotkey) also stay per PC.

## Speed

Typeless-like speed matters most. What WhisperAsh does to keep it fast:

* Records Opus/WebM, which is small (about 10 KB per second of speech), so uploads are tiny.
* Opens the connection to Groq as soon as you press the hotkey, so key release doesn't wait on DNS
  or a TLS handshake.
* Keeps the microphone warm for 60 seconds after a dictation, so the next one starts at once.
* Uses a fast cleanup model (`gpt-oss-20b`, low reasoning) and falls back to the raw transcript if
  cleanup fails.
* Saves locally first; Cloudflare sync runs in the background a few seconds later.
* Shows per-stage timings for the last dictation in Settings → Profile and appends them to
  `%APPDATA%\WhisperAsh\whisperash-timing.log`.

Benchmark numbers: see `test-audio/BENCHMARK.md`.

## If Hinglish quality isn't good enough

Whisper sometimes writes Hindi in Devanagari or mishears mixed sentences. The cleanup step
transliterates to the script you chose. If that still isn't good enough, the paid fallback is
**Sarvam AI Saaras v3** in "codemix" mode, which is built for Indian code-mixed speech. It isn't
wired in; ask if you want it.

## Troubleshooting

* **Nothing happens on the hotkey:** check that the WhisperAsh tray icon is running. If another app
  already uses Right Ctrl, pick another key in Settings → Hotkeys.
* **Text appears in the wrong place:** keep the cursor in the target box until the text appears
  (usually about 1 second).
* **"Session expired":** log in again under Settings → Profile. Unsynced changes are kept.
* **Logs:** `%APPDATA%\WhisperAsh\logs` and the terminal window that runs `npm.cmd run whisperash`.
