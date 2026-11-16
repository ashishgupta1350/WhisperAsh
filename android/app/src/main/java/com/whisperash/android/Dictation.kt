package com.whisperash.android

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject

data class DictationResult(
    val text: String,
    val rawText: String,
    val provider: String,
    val sttModel: String,
    val cleanupModel: String?,
    val timings: JSONObject,
    val note: String? = null,
)

class NoSpeechException : Exception("No speech heard")

/**
 * The hot path: recording -> Groq Whisper (Gemini backup) -> Groq cleanup (qwen, then gpt-oss-20b)
 * -> text. Everything else (history, usage, sync) happens after the text is back.
 */
class DictationEngine(private val app: WhisperAshApp) {

    fun process(rec: Recording, targetPkg: String?, stopAt: Long, cleanupModelOverride: String? = null, sttOverride: String? = null, onStage: (String) -> Unit = {}): DictationResult {
        val s = app.settings
        val t = JSONObject()
        val audioSec = rec.durationMs / 1000.0
        t.put("audio_ms", rec.durationMs)
        t.put("upload_bytes", rec.file.length())
        t.put("encode_flush_ms", System.currentTimeMillis() - stopAt)
        try {
            // A clip with no voice at all would only make Whisper invent "Thank you."
            if (rec.peak < 0.02 || rec.speechMs < 150) throw NoSpeechException()
            val quiet = rec.peak < 0.08
            val language = s.language
            val pref = s.hindiScript
            // The signed-in account email is a likely thing to dictate: give Whisper and cleanup its exact spelling.
            val dictionary = (app.db.dictionary() + listOfNotNull(s.email.takeIf { it.contains("@") })).distinct()
            val prompt = Prompts.whisperPrompt(language, pref, dictionary)

            // ---- speech to text ----
            val sttStart = System.currentTimeMillis()
            var note: String? = null
            val stt: SttResult = try {
                if (sttOverride == "gemini" || (!app.secure.has(SecureStore.GROQ_KEY) && app.gemini.available() && s.geminiBackup)) {
                    app.gemini.transcribe(rec.file, rec.mime, language, dictionary)
                } else {
                    val notes = ArrayList<String>()
                    val r = whisperGuarded(rec, s.sttModel, Prompts.whisperLanguageFor(language, pref), prompt, language, dictionary, notes)
                    if (notes.isNotEmpty()) {
                        t.put("stt_notes", notes.joinToString("; "))
                        Log.i(TAG, "STT notes: ${notes.joinToString("; ")}")
                    }
                    r
                }
            } catch (e: ApiException) {
                if (e.provider == "Groq" && s.geminiBackup && app.gemini.available() && (e.retryable || e.status == 401)) {
                    Log.w(TAG, "Groq STT failed (${e.status}), using Gemini backup")
                    note = "Groq failed (${e.status}); Gemini backup used"
                    app.gemini.transcribe(rec.file, rec.mime, language, dictionary)
                } else throw e
            }
            t.put("stt_ms", System.currentTimeMillis() - sttStart)
            t.put("stt_provider", stt.provider)

            val raw = stt.text.trim()
            if (raw.isEmpty() || Prompts.isLikelyHallucination(raw, if (quiet) prompt else "", quiet) || (stt.noSpeechProb > 0.8 && quiet)) {
                throw NoSpeechException()
            }

            // ---- cleanup ----
            // "x at the rate gmail dot com" -> x@gmail.com before and after cleanup (also when cleanup is skipped).
            val heard = Prompts.fixSpokenEmails(raw)
            var text = heard
            var cleanupModel: String? = null
            if (s.useCleanup && !Prompts.isShortCleanUtterance(raw, pref)) {
                onStage("Polishing…")
                val cleanStart = System.currentTimeMillis()
                val category = Prompts.appCategory(targetPkg)
                val tone = if (s.toneEnabled) category to (s.toneStyles[category] ?: "formal") else null
                val system = Prompts.buildCleanupSystemPrompt(s.agentName, language, pref, dictionary, tone)
                val maxTokens = (raw.length + 200).coerceIn(256, 4096)
                val models = cleanupModelOverride?.let { listOf(it) } ?: listOf(s.cleanupModel, Settings.FALLBACK_CLEANUP_MODEL).distinct()
                for (m in models) {
                    try {
                        val r = app.groq.chat(m, system, Prompts.wrapTranscript(heard), maxTokens)
                        app.db.recordUsage("llm", 0.0, r.tokens, m)
                        t.put("cleanup_tokens", r.tokens)
                        val cleaned = Prompts.tidyModelOutput(r.text)
                        val rawWords = raw.split(Regex("\\s+")).size
                        // An empty answer for a real sentence is a model failure, not "filler only".
                        text = Prompts.fixSpokenEmails(if (cleaned.isEmpty() && rawWords > 3) heard else cleaned)
                        cleanupModel = m
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Cleanup with $m failed: ${e.message}")
                        if (m == models.last()) note = (note?.let { "$it; " } ?: "") + "cleanup skipped (${e.message?.take(60)})"
                    }
                }
                // Roman (Hinglish) mode must never leave Devanagari behind: one short transliteration pass if it did.
                if (pref == "roman" && Prompts.hindiRulesApply(language) && Prompts.hasDevanagari(text)) {
                    val m = cleanupModel ?: s.cleanupModel
                    try {
                        val r = app.groq.chat(m, Prompts.ROMAN_FIX_PROMPT, text, maxTokens)
                        app.db.recordUsage("llm", 0.0, r.tokens, m)
                        val fixed = Prompts.tidyModelOutput(r.text)
                        if (fixed.isNotBlank()) text = fixed
                        t.put("roman_fix", true)
                    } catch (e: Exception) {
                        Log.w(TAG, "Roman fix failed: ${e.message}")
                    }
                }
                t.put("cleanup_ms", System.currentTimeMillis() - cleanStart)
            } else {
                t.put("cleanup_ms", 0)
                t.put("cleanup_skipped", true)
            }
            t.put("total_ms", System.currentTimeMillis() - stopAt)
            return DictationResult(text, raw, stt.provider, stt.model, cleanupModel, t, note)
        } finally {
            rec.file.delete()
        }
    }

    // ---------- Whisper with the gap / loop guard (TranscriptGuard) ----------

    /**
     * Whisper on Groq, protected against dropped speech: long recordings go as ~25 s pieces cut at
     * pauses (in parallel), and every transcript is checked and redone without the script example
     * when broken. Gemini is only tried last, for a whole recording, when its key is saved.
     */
    private fun whisperGuarded(
        rec: Recording, model: String, lang: String, prompt: String, language: String,
        dictionary: List<String>, notes: MutableList<String>,
    ): SttResult {
        val seconds = rec.durationMs / 1000.0
        val pcm = rec.pcm
        if (pcm != null && TranscriptGuard.isLongRecording(seconds)) {
            val ranges = TranscriptGuard.splitAtPauses(pcm, rec.rate)
            notes += "${ranges.size} pieces"
            val texts = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                ranges.mapIndexed { i, range ->
                    async {
                        val piece = java.io.File(app.cacheDir, "piece-${System.nanoTime()}-$i.m4a")
                        try {
                            encodePiece(pcm, range, rec.rate, piece)
                            val secs = (range.last - range.first + 1).toDouble() / rec.rate
                            guardedOnce(piece, "audio/mp4", model, lang, prompt, secs, "piece ${i + 1}", notes)
                        } finally {
                            piece.delete()
                        }
                    }
                }.awaitAll()
            }
            return SttResult(TranscriptGuard.joinPieces(texts), null, 0.0, model, "groq")
        }
        var text = guardedOnce(rec.file, rec.mime, model, lang, prompt, seconds, "whole", notes)
        if (TranscriptGuard.check(text, seconds, prompt).broken && settingsAllowGemini()) {
            try {
                val g = app.gemini.transcribe(rec.file, rec.mime, language, dictionary).text.trim()
                val picked = TranscriptGuard.pickMoreComplete(text, g, seconds) ?: text
                if (picked != text) notes += "Gemini redo kept"
                text = picked
            } catch (e: Exception) {
                Log.w(TAG, "Gemini redo failed: ${e.message}")
            }
        }
        return SttResult(text, null, 0.0, model, "groq")
    }

    private fun settingsAllowGemini() = app.settings.geminiBackup && app.gemini.available()

    /** One Groq request, checked; a broken result is redone without the example (large-v3, then Turbo). */
    private fun guardedOnce(
        file: java.io.File, mime: String, model: String, lang: String, prompt: String,
        seconds: Double, label: String, notes: MutableList<String>,
    ): String {
        val first: String? = try {
            app.groq.transcribe(file, mime, model, lang, prompt).text.trim().also { app.db.recordUsage("stt", seconds, 0, model) }
        } catch (e: ApiException) {
            if (label == "whole" || e.status == 401) throw e
            null
        }
        val check = if (first == null) TranscriptGuard.Check(true, "request_failed") else TranscriptGuard.check(first, seconds, prompt)
        if (!check.broken) return first!!
        val again = redo(file, mime, lang, seconds)
        val picked = TranscriptGuard.pickMoreComplete(first, again, seconds)
        synchronized(notes) { notes += "$label redone (${check.reason})" + if (picked != first) " -> redo kept" else " -> original kept" }
        return picked ?: throw ApiException(0, "Groq", "Whisper could not transcribe $label")
    }

    private fun redo(file: java.io.File, mime: String, lang: String, seconds: Double): String? {
        fun once(model: String) = try {
            app.groq.transcribe(file, mime, model, lang, "").text.trim().also { app.db.recordUsage("stt", seconds, 0, model) }
        } catch (e: ApiException) {
            null
        }
        val v3 = once("whisper-large-v3")
        if (v3 != null && !TranscriptGuard.check(v3, seconds).broken) return v3
        val turbo = once("whisper-large-v3-turbo")
        if (v3 == null) return turbo
        if (turbo == null) return v3
        return TranscriptGuard.pickMoreComplete(v3, turbo, seconds)
    }

    /** One piece of the recording as a small AAC file (mobile data friendly). */
    private fun encodePiece(pcm: ShortArray, range: IntRange, rate: Int, out: java.io.File) {
        val enc = AacEncoder(rate, out)
        val buf = java.nio.ByteBuffer.allocate(6_400).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = range.first
        while (i <= range.last) {
            buf.clear()
            while (buf.remaining() >= 2 && i <= range.last) { buf.putShort(pcm[i]); i++ }
            enc.feed(buf.array(), buf.position())
        }
        enc.finish()
    }

    /** After insertion: history, timing, sync. Never on the hot path. */
    fun record(result: DictationResult, audioMs: Long, targetPkg: String?, insertedAt: Long, stopAt: Long) {
        result.timings.put("stop_to_insert_ms", insertedAt - stopAt)
        result.timings.put("ts", System.currentTimeMillis())
        result.timings.put("words", result.text.split(Regex("\\s+")).count { it.isNotEmpty() })
        app.db.recordTiming(result.timings)
        if (result.text.isNotBlank()) {
            app.db.addHistory(
                result.text, result.rawText, result.provider,
                listOfNotNull(result.sttModel, result.cleanupModel).joinToString(" + "), audioMs, targetPkg,
            )
            app.sync.requestSync(SyncClient.Reason.CHANGE)
        }
    }

    companion object {
        const val TAG = "WhisperAsh"
    }
}

/** "Free words left", the same estimate as the desktop (whisperashSync.js _wordAllowance). */
data class UsageSummary(
    val sttMinute: Db.Window, val sttHour: Db.Window, val sttDay: Db.Window,
    val llmMinute: Db.Window, val llmDay: Db.Window,
    val dictatedToday: Int, val dictatedMonth: Int,
    val leftToday: Int, val leftMonth: Int, val fullDay: Int, val perSecond: Double,
)

fun usageSummary(db: Db): UsageSummary {
    val now = System.currentTimeMillis()
    val sttDay = db.usage("stt", now - 86_400_000L)
    val sttHour = db.usage("stt", now - 3_600_000L)
    val sttMin = db.usage("stt", now - 60_000L)
    val llmDay = db.usage("llm", now - 86_400_000L)
    val llmMin = db.usage("llm", now - 60_000L)
    val today = db.wordsSince(Db.startOfToday())
    val month = db.wordsSince(Db.startOfMonth())
    val last24 = db.wordsSince(now - 86_400_000L)
    val perSecond = if (sttDay.seconds >= 60 && last24.first > 0) last24.first / sttDay.seconds else 2.0
    val perRequest = if (sttDay.requests >= 3 && last24.second > 0) last24.first.toDouble() / last24.second else 25.0
    val secondsLeft = maxOf(0.0, GroqLimits.AUDIO_PER_DAY - sttDay.seconds)
    val requestsLeft = maxOf(0, GroqLimits.STT_RPD - sttDay.requests)
    val leftToday = minOf(secondsLeft * perSecond, requestsLeft * perRequest).toInt()
    val fullDay = minOf(GroqLimits.AUDIO_PER_DAY * perSecond, GroqLimits.STT_RPD * perRequest).toInt()
    val cal = java.util.Calendar.getInstance()
    val daysLeft = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH) - cal.get(java.util.Calendar.DAY_OF_MONTH)
    return UsageSummary(
        sttMin, sttHour, sttDay, llmMin, llmDay, today.first, month.first,
        leftToday, leftToday + daysLeft * fullDay, fullDay, Math.round(perSecond * 100) / 100.0,
    )
}
