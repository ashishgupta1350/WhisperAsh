package com.whisperash.android

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * "Re-transcribe (better model)" for a History entry: the kept recording → Gemini [GeminiMeetingClient.BETTER_MODEL]
 * (generateContent, inline audio) → the normal dictation cleanup (same prompt, dictionary, Hinglish rules) with Groq
 * [GroqMeetingClient.CHAT_MODEL]. Routing per provider: own key → provider directly, else the signed-in account →
 * Worker gateway with `x-whisperash-purpose: dictation` (counted as dictation words, not meeting minutes).
 */
class MissingRecordingException : Exception("The recording for this entry is no longer on the phone")
class NoGeminiException : Exception("No Gemini key — add one in Settings or sign in")

object Retranscribe {
    /** The ⋯ item shows only when the entry still has its recording on this phone. */
    fun canRun(audioPath: String?, exists: (String) -> Boolean = { File(it).exists() }): Boolean =
        !audioPath.isNullOrBlank() && exists(audioPath)

    /** What Gemini is asked to write for one dictation, in the user's chosen script. */
    fun prompt(pref: String, dictionary: List<String>): String {
        val script = when (pref) {
            "devanagari" -> "Write Hindi words in Devanagari and English words in English (Latin) spelling."
            "english" -> "Write Hindi words in Roman (Latin) letters the way people type Hinglish; English words as normal English."
            else -> "Write Hindi words in Roman (Latin) letters the way people type Hinglish on WhatsApp (for example \"haan, kal tak bhej dunga\"); English words as normal English. Never use Devanagari."
        }
        var p = "Transcribe this voice recording word for word. The speaker may use Hindi, English or a mix of both (Hinglish). " +
            "$script Do not translate, summarise or answer it. Write nothing but what was said."
        if (dictionary.isNotEmpty()) p += " Spell these words exactly like this when they are said: " + dictionary.joinToString(", ") + "."
        return p
    }
}

/** Errors in plain words. Account answers (limit reached, paused, signed out) keep the server's own wording. */
object RetranscribeErrors {
    fun message(e: Throwable): String = when {
        e is MissingRecordingException || e is NoGeminiException -> e.message!!
        e is NoSpeechException -> "No speech was found in this recording."
        e is ApiException && e.code == "timeout" -> "Gemini took too long — try again later"
        e is ApiException && e.provider == "WhisperAsh" && !e.message.isNullOrBlank() -> e.message!!
        e is ApiException && e.status == 0 -> "No connection. Try again when you're online."
        e is ApiException && (e.status == 401 || e.status == 403) -> "${e.provider} refused the key. Check it in Settings."
        e is ApiException && e.status == 429 -> "${e.provider} is busy or out of quota. Try again later."
        e is ApiException -> e.message ?: "The engine refused."
        e is java.io.InterruptedIOException -> "Gemini took too long — try again later"
        e is java.io.IOException -> "No connection. Try again when you're online."
        else -> e.message ?: "Something went wrong."
    }
}

/** Replace with undo, and restore: (text, previous text). */
object TextUndo {
    /** The new text goes in; the old one is kept unless the old one was itself a re-transcribe (keep the original). */
    fun replace(current: String, prev: String?, new: String): Pair<String, String?> = new to (prev ?: current)

    /** Brings back the previous text; null when there is nothing to restore. */
    fun restore(current: String, prev: String?): Pair<String, String?>? = prev?.let { it to null }
}

class HistoryRetranscriber(private val app: WhisperAshApp) {
    private val gemini = GeminiMeetingClient(app.http)
    private val groq = GroqMeetingClient(app.http)

    /** Returns the new text (not saved: the caller replaces it with undo). */
    fun run(item: HistoryItem): String {
        val audio = item.audioPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 } ?: throw MissingRecordingException()
        val geminiTarget = app.meetingTarget(MeetingRoutes.GEMINI) ?: throw NoGeminiException()
        val s = app.settings
        val dictionary = (app.db.dictionary() + listOfNotNull(s.email.takeIf { it.contains("@") })).distinct()
        val raw = gemini.generate(geminiTarget, null, Retranscribe.prompt(s.hindiScript, dictionary), audio, "audio/mp4",
            GeminiMeetingClient.BETTER_MODEL, GatewayHeaders.DICTATION, retranscribe = true,
        ).let { Prompts.tidyModelOutput(it) }.trim()
        if (raw.isEmpty()) throw NoSpeechException()
        val heard = Prompts.fixSpokenEmails(raw)
        val groqTarget = app.meetingTarget(MeetingRoutes.GROQ)
        if (groqTarget == null || !s.useCleanup || Prompts.isShortCleanUtterance(raw, s.hindiScript)) return heard
        return cleanup(groqTarget, heard, raw, item.app, s.language, s.hindiScript)
    }

    /** The dictation cleanup, as in DictationEngine, on GPT-OSS 120B. Never loses words; a failure keeps the transcript. */
    private fun cleanup(t: ApiTarget, heard: String, raw: String, targetPkg: String?, language: String, pref: String): String {
        val s = app.settings
        val category = Prompts.appCategory(targetPkg)
        val tone = if (s.toneEnabled) category to (s.toneStyles[category] ?: "formal") else null
        val system = Prompts.buildCleanupSystemPrompt(s.agentName, language, pref, app.db.dictionary(), tone)
        fun chat(sys: String, user: String) = groq.chat(t, sys, user, GroqMeetingClient.CHAT_MODEL, GatewayHeaders.DICTATION)
        var text = try {
            val cleaned = Prompts.tidyModelOutput(chat(system, Prompts.wrapTranscript(heard)))
            val out = Prompts.fixSpokenEmails(if (cleaned.isEmpty() && raw.split(Regex("\\s+")).size > 3) heard else cleaned)
            val kept = Prompts.checkRetention(heard, out)
            if (!kept.flagged) out else try {
                val second = Prompts.fixSpokenEmails(Prompts.tidyModelOutput(chat(system, Prompts.retentionRetry(heard, kept.gone))))
                if (second.isNotBlank() && !Prompts.checkRetention(heard, second).flagged) second else heard
            } catch (e: Exception) { heard }
        } catch (e: ApiException) {
            if (e.final) throw e          // the account said no (limit, paused, signed out): say so
            Log.w(TAG, "Cleanup failed, keeping the transcript: ${e.message}")
            heard
        }
        if (pref == "roman" && Prompts.hindiRulesApply(language) && Prompts.hasDevanagari(text)) {
            try { Prompts.tidyModelOutput(chat(Prompts.ROMAN_FIX_PROMPT, text)).takeIf { it.isNotBlank() }?.let { text = it } } catch (_: Exception) {}
        }
        return text
    }

    companion object { const val TAG = "WhisperAshRedo" }
}

/** Re-transcribes in flight (History entry id), the last finished one (for the Undo bar) and errors. */
object HistoryJobs {
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The entry whose text was just replaced: the History screen shows "Text replaced · Undo" for it. */
    val replaced = MutableStateFlow<String?>(null)
}
