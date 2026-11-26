package com.whisperash.android

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The WhisperAsh account refused (limit, paused, signed out): no other provider would help. */
class MeetingStopped(message: String) : Exception(message)

/** Every provider failed some part; the audio is kept for Retry. */
class MeetingFailed(message: String, val detail: String = message) : Exception(message)

/** One part's audio. [index] from 0, [total] parts in the meeting. */
data class MeetingPart(
    val index: Int, val total: Int, val file: File, val mime: String,
    val startMs: Long, val durationMs: Long, val hasSpeech: Boolean,
)

/** One part's transcript lines, times from the start of the part. engine "" = no speech, nothing sent. */
data class PartResult(val index: Int, val engine: String, val lines: List<String>)

interface AudioEngine {
    val name: String
    /** Deepgram / Groq output goes through the ROMANIZE pass; Gemini writes Roman script itself. */
    val needsRomanize: Boolean
    fun transcribe(part: MeetingPart, prompt: String): List<String>
}

interface TextEngine {
    val name: String
    /** Most words of transcript per ROMANIZE call (the answer must fit the model's output limit). */
    val maxWords: Int
    fun complete(system: String, user: String): String
}

/**
 * Per part: Gemini → Deepgram (+ ROMANIZE) → Groq Whisper (+ ROMANIZE), at most [maxParallel] parts at
 * a time; then one SUMMARY over the whole transcript (Gemini text, then Groq). Engines without a key or
 * account are simply left out of the lists.
 */
class MeetingChain(
    private val audio: List<AudioEngine>,
    private val text: List<TextEngine>,
    private val maxParallel: Int = 2,
    private val kind: RecordingKind = RecordingKind.MEETING,
    private val log: (String) -> Unit = {},
) {
    class PartFailed(message: String) : Exception(message)

    fun transcribePart(part: MeetingPart, previous: List<String>?): PartResult {
        if (!part.hasSpeech) return PartResult(part.index, "", emptyList())
        val prompt = if (kind.isDiary) DiaryPrompts.transcribe(part.index + 1, part.total)
            else MeetingPrompts.transcribe(part.index + 1, part.total, previous)
        val errors = ArrayList<String>()
        for (e in audio) {
            try {
                var lines = e.transcribe(part, prompt)
                if (lines.isEmpty()) throw ApiException(502, e.name, "empty answer")
                if (e.needsRomanize) lines = romanize(lines)
                return PartResult(part.index, e.name, lines)
            } catch (ex: Exception) {
                if (isStop(ex)) throw MeetingStopped(ex.message ?: "WhisperAsh stopped the meeting transcript")
                log("part ${part.index + 1}/${part.total} ${e.name} failed: ${ex.message}")
                errors += "${MeetingText.engineName(e.name).ifEmpty { e.name }}: ${ex.message}"
            }
        }
        throw PartFailed(
            if (errors.isEmpty()) "No meeting engine is set up" else errors.joinToString("; ")
        )
    }

    /**
     * Transcribes the parts not in [cached], in order, [maxParallel] at a time. A part gets the previous
     * part's last lines when that part is already done. [onPart] sees each new result (to keep it for Retry).
     */
    suspend fun transcribeAll(
        parts: List<MeetingPart>,
        cached: Map<Int, PartResult> = emptyMap(),
        onPart: (PartResult) -> Unit = {},
        onProgress: (done: Int, total: Int, inFlight: List<Int>) -> Unit = { _, _, _ -> },
    ): List<PartResult> = coroutineScope {
        val results = ConcurrentHashMap(cached)
        val failures = ConcurrentHashMap<Int, String>()
        val inFlight = java.util.concurrent.ConcurrentSkipListSet<Int>()
        val sem = Semaphore(maxParallel)
        fun report() = onProgress(results.size, parts.size, ArrayList(inFlight)) // ArrayList(set) copies via toArray: safe while parts finish (toList() can throw)
        report()
        val jobs = parts.filter { !results.containsKey(it.index) }.map { part ->
            sem.acquire() // in order: part n starts before part n+1
            inFlight += part.index
            report()
            launch(Dispatchers.IO) {
                try {
                    val prev = if (part.index == 0) null else results[part.index - 1]?.lines?.takeLast(6)?.takeIf { it.isNotEmpty() }
                    val r = transcribePart(part, prev)
                    results[part.index] = r
                    onPart(r)
                } catch (e: PartFailed) {
                    failures[part.index] = e.message ?: "failed"
                } finally {
                    inFlight -= part.index
                    sem.release()
                    report()
                }
            }
        }
        jobs.joinAll()
        if (failures.isNotEmpty()) {
            val first = failures.keys.min()
            throw MeetingFailed("Could not transcribe part ${first + 1} of ${parts.size} (${failures[first]}). The recording is kept: tap Retry.", failures[first] ?: "failed")
        }
        parts.map { results.getValue(it.index) }
    }

    /** ROMANIZE in chunks; a chunk every text engine fails on is kept as it was. */
    fun romanize(lines: List<String>): List<String> {
        if (text.isEmpty() || lines.isEmpty()) return lines
        return MeetingText.chunkByWords(lines, text[0].maxWords).flatMap { romanizeChunk(it, 0) }
    }

    private fun romanizeChunk(chunk: List<String>, engine: Int): List<String> {
        if (engine >= text.size) return chunk
        val e = text[engine]
        if (chunk.size > 1 && MeetingText.words(chunk) > e.maxWords) {
            return MeetingText.chunkByWords(chunk, e.maxWords).flatMap { romanizeChunk(it, engine) }
        }
        try {
            val out = MeetingText.modelLines(e.complete(MeetingPrompts.ROMANIZE, chunk.joinToString("\n")))
            // Must look like the same transcript, not an answer about it.
            if (out.isNotEmpty() && out.size * 2 >= chunk.size) return out
            log("romanize with ${e.name}: ${out.size} lines for ${chunk.size}")
        } catch (ex: Exception) {
            if (isStop(ex)) throw MeetingStopped(ex.message ?: "WhisperAsh stopped the meeting transcript")
            log("romanize with ${e.name} failed: ${ex.message}")
        }
        return romanizeChunk(chunk, engine + 1)
    }

    /** Null when every text engine fails; throws [MeetingStopped] when the account says no. */
    fun summarize(transcript: String): MeetingSummary? {
        if (transcript.isBlank()) return null
        for (e in text) {
            try {
                val s = MeetingText.parseSummary(e.complete(if (kind.isDiary) DiaryPrompts.SUMMARY else MeetingPrompts.SUMMARY, transcript))
                if (s.title != null || s.body.isNotBlank()) return s
            } catch (ex: Exception) {
                if (isStop(ex)) throw MeetingStopped(ex.message ?: "WhisperAsh stopped the meeting summary")
                log("summary with ${e.name} failed: ${ex.message}")
            }
        }
        return null
    }

    companion object {
        const val TAG = "WhisperAshMeeting"
        val STOP_CODES = setOf("session_expired", "account_blocked", "meeting_limit")

        /** Account refusals stop the chain; own-key errors and gateway trouble (busy, no key, 5xx) do not. */
        fun isStop(e: Throwable) = e is ApiException && e.provider == "WhisperAsh" && e.code in STOP_CODES

        /** Gemini busy (503 / 429), not an account refusal and not a missing gateway key: worth one retry. */
        fun isBusy(e: Throwable) = e is ApiException && (e.status == 503 || e.status == 429) &&
            !isStop(e) && e.code != "no_key_configured"

        const val BUSY_WAIT_MS = 5_000L

        /** Runs [call]; when Gemini says it is busy, waits 5 s and tries the same call once more. */
        fun <T> retryIfBusy(sleep: (Long) -> Unit = { Thread.sleep(it) }, call: () -> T): T = try {
            call()
        } catch (e: Exception) {
            if (!isBusy(e)) throw e
            sleep(BUSY_WAIT_MS)
            call()
        }
    }
}

/** Builds the engine lists from whatever keys / account this phone has. */
/**
 * [better] = "Re-transcribe (better model)": the audio goes only to Gemini [GeminiMeetingClient.BETTER_MODEL]
 * (minimal thinking, 4-minute limit), so a failure is reported instead of quietly using another
 * engine; ROMANIZE and SUMMARY run as usual.
 */
class MeetingEngines(private val app: WhisperAshApp, private val better: Boolean = false,
    private val kind: RecordingKind = RecordingKind.MEETING, private val diaryOwner: String = "") {
    private val gemini = GeminiMeetingClient(app.http)
    private val deepgram = DeepgramClient(app.http)
    private val groq = GroqMeetingClient(app.http)

    private fun geminiTarget(): ApiTarget? = app.meetingTarget(MeetingRoutes.GEMINI)
        ?.takeIf { !kind.isDiary || !it.viaAccount || app.settings.userId == diaryOwner }

    fun audio(): List<AudioEngine> = if (better || kind.isDiary) listOfNotNull(
        geminiTarget()?.let { t ->
            object : AudioEngine {
                override val name = "gemini"
                override val needsRomanize = false
                override fun transcribe(part: MeetingPart, prompt: String) = MeetingText.modelLines(MeetingChain.retryIfBusy {
                    gemini.generate(t, null, prompt, part.file, part.mime,
                        if (better) GeminiMeetingClient.BETTER_MODEL else GeminiMeetingClient.MODEL, GatewayHeaders.MEETING)
                })
            }
        },
    ) else listOfNotNull(
        app.meetingTarget(MeetingRoutes.GEMINI)?.let { t ->
            object : AudioEngine {
                override val name = "gemini"
                override val needsRomanize = false
                override fun transcribe(part: MeetingPart, prompt: String) =
                    MeetingText.modelLines(MeetingChain.retryIfBusy { gemini.generate(t, null, prompt, part.file, part.mime) })
            }
        },
        app.meetingTarget(MeetingRoutes.DEEPGRAM)?.let { t ->
            object : AudioEngine {
                override val name = "deepgram"
                override val needsRomanize = true
                override fun transcribe(part: MeetingPart, prompt: String) = MeetingText.deepgramLines(deepgram.listen(t, part.file, part.mime))
            }
        },
        app.meetingTarget(MeetingRoutes.GROQ)?.let { t ->
            object : AudioEngine {
                override val name = "groq"
                override val needsRomanize = true
                override fun transcribe(part: MeetingPart, prompt: String): List<String> {
                    val lines = MeetingText.groqLines(groq.transcribe(t, part.file, part.mime))
                    if (!t.viaAccount) app.db.recordUsage("stt", part.durationMs / 1000.0, 0, GroqMeetingClient.STT_MODEL)
                    return lines
                }
            }
        },
    )

    fun text(): List<TextEngine> = listOfNotNull(
        geminiTarget()?.let { t ->
            object : TextEngine {
                override val name = "gemini"
                override val maxWords = 6000
                override fun complete(system: String, user: String) = MeetingChain.retryIfBusy { gemini.generate(t, system, user) }
            }
        },
        if (kind.isDiary) null else app.meetingTarget(MeetingRoutes.GROQ)?.let { t ->
            object : TextEngine {
                override val name = "groq"
                // 4,096 output tokens hold about 2,000 words of Hinglish.
                override val maxWords = 2000
                override fun complete(system: String, user: String) = groq.chat(t, system, user)
            }
        },
    )
}

/**
 * Turns a recorded meeting into transcript + summary: cut into parts, run the chain, save. Parts that
 * finished are kept on disk, so Retry after a failure only redoes the rest.
 */
class MeetingJob(private val app: WhisperAshApp) {

    /**
     * [better] = "Re-transcribe (better model)": the old title / summary / transcript / engine are kept for Undo,
     * and if it fails the meeting keeps its old text (status stays done) with the reason shown.
     */
    suspend fun run(id: String, fresh: Boolean, better: Boolean = false, progress: (String) -> Unit) {
        val m = app.db.meeting(id) ?: return
        val audio = m.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
        if (audio == null) {
            if (better) app.db.saveMeeting(m.copy(error = "The recording for this meeting is no longer on the phone."))
            else app.db.saveMeeting(m.copy(status = "failed", error = "The recording is not on this phone."))
            return
        }
        val dir = audio.parentFile!!
        if (fresh || better) MeetingFiles.clearParts(dir, keepText = false)
        val hadText = m.status == "done" && m.transcript.isNotBlank()
        app.db.saveMeeting(
            if (better && hadText) m.copy(status = "transcribing", error = null,
                prevTitle = m.title, prevSummary = m.summary, prevTranscript = m.transcript, prevEngine = m.engine)
            else m.copy(status = "transcribing", error = null)
        )
        if (better) progress("Re-transcribing with ${GeminiMeetingClient.BETTER_LABEL}…")

        val engines = MeetingEngines(app, better, m.kind, m.diaryOwner)
        val audioEngines = engines.audio()
        if (audioEngines.isEmpty()) {
            fail(id, if (better || m.kind.isDiary) "Sign into the diary's WhisperAsh account or add a Gemini key in Settings, then tap Retry."
                else "Sign in on the Account tab, or add a Gemini, Deepgram or Groq key in Settings, then tap Retry.", better)
            return
        }
        val chain = MeetingChain(audioEngines, engines.text(), kind = m.kind, log = { Log.w(MeetingChain.TAG, it) })

        progress("Preparing the recording…")
        val (rms, speech) = MeetingFiles.readEnergy(dir) ?: (FloatArray(0) to BooleanArray(0))
        val spans = CutPlanner.spans(rms, speech, m.durationMs.coerceAtLeast(1))
        val parts = try {
            spans.map { s ->
                val file = if (spans.size == 1) audio else File(dir, "part-${s.index}.m4a").also {
                    if (!it.exists() || it.length() == 0L) MeetingAudio.cut(audio, it, s.startMs, s.endMs)
                }
                MeetingPart(s.index, spans.size, file, "audio/mp4", s.startMs, s.endMs - s.startMs, s.hasSpeech)
            }
        } catch (e: Exception) {
            fail(id, "Could not read the recording (${e.message}).", better)
            return
        }
        val cached = spans.mapNotNull { MeetingFiles.readPart(dir, it.index) }.associateBy { it.index }

        val results = try {
            chain.transcribeAll(parts, cached,
                onPart = { MeetingFiles.writePart(dir, it) },
                onProgress = { done, total, inFlight ->
                    if (done < total) progress(
                        if (total == 1) "Transcribing…"
                        else "Transcribing part ${(inFlight.minOrNull() ?: done) + 1} of $total…"
                    )
                })
        } catch (e: MeetingStopped) {
            fail(id, e.message ?: "Stopped by WhisperAsh.", better); return
        } catch (e: MeetingFailed) {
            fail(id, if (better) "Re-transcribe didn't work (${e.detail}). The old transcript is kept." else e.message ?: "Transcription failed.", better); return
        }

        val transcript = MeetingText.join(spans, results)
        var note: String? = null
        val summary = if (transcript.isBlank()) null else {
            progress(if (m.kind.isDiary) "Writing the diary summary…" else "Writing the summary…")
            try {
                chain.summarize(transcript) ?: run { note = "The summary could not be written; the transcript is complete."; null }
            } catch (e: MeetingStopped) {
                note = e.message; null
            }
        }
        val current = app.db.meeting(id) ?: run { MeetingFiles.deleteFor(audio.path); return } // deleted meanwhile
        // Keep a title the user typed while it was transcribing.
        val untouched = current.title.isBlank() || current.title == current.kind.defaultTitle(current.createdMs) ||
            (better && current.title == current.prevTitle)
        app.db.saveMeeting(current.copy(
            title = when {
                !untouched -> current.title
                m.kind.isDiary -> DiaryText.title(m.createdMs, summary?.title ?: DiaryText.themeFromTranscript(transcript))
                else -> summary?.title ?: current.title
            },
            summary = summary?.body?.let { if (m.kind.isDiary) it.replace(Regex("^Summary\\s*:\\s*", RegexOption.IGNORE_CASE), "") else it } ?: "",
            transcript = transcript.ifBlank { "(No speech was heard in this recording.)" },
            engine = MeetingText.engineLabel(results).ifEmpty { audioEngines.first().name },
            status = "done",
            error = note,
        ))
        MeetingFiles.clearParts(dir, keepText = false)
        app.sync.requestSync(SyncClient.Reason.CHANGE)
        app.sync.dataVersion.value++
    }

    private fun fail(id: String, message: String, better: Boolean = false) {
        val m = app.db.meeting(id) ?: return
        if (better && m.prevTranscript != null) {
            // Nothing was replaced: the old text stays, with the reason; nothing to undo.
            app.db.saveMeeting(m.copy(status = "done", error = message, title = m.prevTitle ?: m.title, summary = m.prevSummary ?: m.summary,
                transcript = m.prevTranscript, engine = m.prevEngine ?: m.engine,
                prevTitle = null, prevSummary = null, prevTranscript = null, prevEngine = null))
        } else app.db.saveMeeting(m.copy(status = "failed", error = message))
        app.sync.requestSync(SyncClient.Reason.CHANGE)
        app.sync.dataVersion.value++
    }
}
