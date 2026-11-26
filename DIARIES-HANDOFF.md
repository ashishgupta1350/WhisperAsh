# WhisperAsh Diaries and Notes

This branch starts from the last combined Claude Code commit, `6cbf61d` on `accounts-login-and-fixes`. Earlier Claude work is already in that branch's Git history.

## Architecture

| Component | Path | Role |
| --- | --- | --- |
| Android | `android/app/src/main/java/com/whisperash/android/` | Kotlin and Compose. A foreground recorder captures AAC, then the meeting job chunks the audio and calls Gemini. SQLite saves the transcript and summary. |
| Windows | `app/` | Electron and React, based on OpenWhispr. The renderer captures WebM chunks. The main process transcribes and stores entries under the WhisperAsh user-data directory. |
| Sync | `worker/src/index.js` and `worker/schema.sql` | Cloudflare Worker and D1. Both clients use authenticated push and cursor-based pull. Each record belongs to one account and is keyed by kind and ID. |

Diaries reuse the recorder but have their own navigation and sync kind. Gemini 3.5 Flash-Lite transcribes and summarizes by default. The Gemini 3.6 Flash retry is available on the device with the audio. The title combines the entry date and a short theme. Audio stays on the device where it was recorded. Transcript, summary, duration, title edits, and deletions sync.

Windows Notes uses the Android Notes payload over the same sync endpoints. It preserves synced folders, favourites, and the lock flag. To edit a note locked on Android, unlock it on Android first. A conflicting edit leaves the Windows draft open so it can be reviewed. A newly signed-in account can claim entries made before the first sign-in. Diary and Windows Notes collections are selected per account after that.

## Build and test

- Android from `android/`: `gradlew.bat :app:testDebugUnitTest :app:assembleRelease`
- Windows from `app/`: `npm run typecheck` and `npm run whisperash:dist`
- Worker from `worker/`: run the API and gateway suites against a local Wrangler Worker with a local D1 database and test provider credentials. The gateway suite serves its own mock provider.

The Worker change adds the `diaries` kind to the existing D1 table. It requires no schema migration on the live database. Clients leave diary changes queued if the server has yet to accept that kind. To roll back a Worker deployment, redeploy the previous Worker version. The client changes can be merged or reverted independently in Git.
