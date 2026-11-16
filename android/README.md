# WhisperAsh for Android

A voice keyboard for your phone. Tap the mic in any app (WhatsApp, Gmail, Chrome...) and the
cleaned-up text appears where you're typing. It uses the same Groq models, prompts, Hindi/Hinglish
rules and account as WhisperAsh on your PC.

- **Keyboard:** a big mic key. Tap to start and tap to finish, or hold it while you talk and let go.
  ✕ cancels, ↶ Undo removes what was just inserted. 🌐 goes back to your normal keyboard (hold it
  to pick one). The chip at top left switches how Hindi is written: Hinglish (Roman), हिन्दी, or English.
- **App:** setup checklist, a test box, Groq "free words left" and speed, history, personal
  dictionary, settings (language, Hindi script, cleanup model, tone per app, keys), account and sync.
- **Speed:** audio is compressed (AAC, 32 kbit/s) while you talk and the connection to Groq is
  opened as you start, so stopping only has to upload a small file. Short clean phrases skip cleanup.
- **Keys:** your Groq key (and an optional Gemini backup key) are typed into the app and stored
  encrypted with the Android Keystore. They are never in the code and never synced.

## Put it on your phone (sideload)

1. Copy `android\app\build\outputs\apk\release\app-release.apk` to your phone (USB, Google
   Drive, or email it to yourself).
2. On the phone, open the file. If Android asks, allow "Install unknown apps" for the app you
   opened it from (Files, Drive or Chrome), then tap **Install**.
   Play Protect may warn that the app is unknown: tap **More details → Install anyway**.
3. Open **WhisperAsh** and follow the checklist on the first screen:
   1. **Turn on the WhisperAsh keyboard** (opens Android's keyboard list; switch WhisperAsh on and accept the notice).
   2. **Allow the microphone.**
   3. **Add your Groq API key** (Settings tab). Optional: a Gemini key for backup.
   4. **Switch to WhisperAsh** (or tap 🌐 on any keyboard).
   5. **Log in** with your WhisperAsh account (Account tab) to get your PC's history, dictionary and settings.

Or with a USB cable and USB debugging on, from this folder:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

Android shows a notice when you turn on any third-party keyboard saying it "may be able to collect
all the text you type". WhisperAsh only sends audio to Groq (or Gemini, if you added a key) and your
history to your own sync server.

## Build

Needs the Android SDK (`%LOCALAPPDATA%\Android\Sdk`) and JDK 17+. From `android\`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"; .\gradlew.bat testDebugUnitTest assembleDebug assembleRelease
```

Both APKs are signed with this PC's debug key, so either can be installed over the other.

## Test hooks (debug build only)

The debug APK's keyboard listens for adb broadcasts so dictation can be tested without speaking:

```powershell
# make a Hindi clip with the phone's own text-to-speech
adb shell "am broadcast -p com.whisperash.android -a com.whisperash.android.DEBUG_TTS --es text 'नमस्ते' --es lang hi-IN --es file hi.wav"
# dictate a WAV from /sdcard/Android/data/com.whisperash.android/files/test/ into the focused text box
adb shell am broadcast -p com.whisperash.android -a com.whisperash.android.DEBUG_DICTATE_FILE --es file hi.wav
# record the real mic for 3 s
adb shell am broadcast -p com.whisperash.android -a com.whisperash.android.DEBUG_MIC --el ms 3000
# change a synced setting
adb shell am broadcast -p com.whisperash.android -a com.whisperash.android.DEBUG_SET --es key hindiScriptPreference --es value devanagari
```

Results are logged under the `WhisperAsh` logcat tag. The release APK has none of this.
