package com.whisperash.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Debug-only test entry points that need no screen:
 *  DEBUG_TTS_FILE      --es text … --es lang hi-IN --es file x.wav   speak a sentence into a WAV
 *  DEBUG_SETTING       --es key hindiScriptPreference --es value devanagari    change a synced setting
 *  DEBUG_PROCESS_FILE  --es file x.wav [--es pkg com.whatsapp]       run the full pipeline, log RESULT
 * Files live in /sdcard/Android/data/com.whisperash.android/files/test/. Nothing is saved to history.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WhisperAshApp
        val dir = context.getExternalFilesDir("test")
        val pending = goAsync()
        when (intent.action) {
            "com.whisperash.android.DEBUG_PROCESS_FILE" -> app.scope.launch {
                val name = intent.getStringExtra("file") ?: "missing"
                try {
                    val stopAt = System.currentTimeMillis()
                    val src = File(dir, name)
                    // WAV goes through the AAC encoder like the mic; other files (desktop .webm recordings) are sent as they are.
                    val rec = if (name.endsWith(".wav")) Wav.toRecording(src, context.cacheDir) else {
                        val copy = File(context.cacheDir, "test-" + System.currentTimeMillis() + "-" + name)
                        src.copyTo(copy, overwrite = true)
                        Recording(copy, if (name.endsWith(".webm")) "audio/webm" else "audio/mp4", 5_000, 1.0, 5_000)
                    }
                    val r = app.engine.process(
                        rec, intent.getStringExtra("pkg"), stopAt,
                        cleanupModelOverride = intent.getStringExtra("model"), sttOverride = intent.getStringExtra("stt"),
                    )
                    Log.i(TAG, "RESULT " + JSONObject().put("file", name).put("raw", r.rawText).put("text", r.text)
                        .put("cleanup", r.cleanupModel ?: "skipped").put("stt", r.provider).put("note", r.note ?: "").put("timings", r.timings).put("rate", JSONObject(app.groq.lastRateHeaders as Map<*, *>)))
                } catch (e: Exception) {
                    Log.i(TAG, "RESULT " + JSONObject().put("file", name).put("error", "${e.javaClass.simpleName}: ${e.message}"))
                } finally {
                    pending.finish()
                }
            }
            "com.whisperash.android.DEBUG_TTS_FILE" -> {
                val text = intent.getStringExtra("text") ?: return pending.finish()
                val out = File(dir, intent.getStringExtra("file") ?: "tts.wav")
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context.applicationContext) { st ->
                    val t = tts
                    if (t == null || st != TextToSpeech.SUCCESS) { Log.i(TAG, "TTS init failed"); pending.finish(); return@TextToSpeech }
                    Log.i(TAG, "TTS language -> " + t.setLanguage(Locale.forLanguageTag(intent.getStringExtra("lang") ?: "en-IN")))
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { Log.i(TAG, "TTS done ${out.name} ${out.length()}"); t.shutdown(); pending.finish() }
                        @Deprecated("") override fun onError(id: String?) { Log.i(TAG, "TTS error"); t.shutdown(); pending.finish() }
                    })
                    t.synthesizeToFile(text, null, out, "clip")
                }
            }
            "com.whisperash.android.DEBUG_SETTING" -> {
                val key = intent.getStringExtra("key")
                if (key != null) app.settings.setSynced(key, intent.getStringExtra("value"))
                Log.i(TAG, "SETTING $key=${intent.getStringExtra("value")}")
                app.scope.launch {
                    try { app.sync.syncNow(SyncClient.Reason.MANUAL) } finally {
                        Log.i(TAG, "SYNC done, error='${app.settings.lastSyncError}' pending=${app.settings.settingsDirty}")
                        pending.finish()
                    }
                }
            }
            else -> pending.finish()
        }
    }

    companion object {
        const val TAG = "WhisperAshTest"
    }
}
