package com.whisperash.android

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Meeting recorder prompts, shared word for word with the desktop app and the Worker contract
 * (side-session-notes meeting-spec.md). Keep the three texts identical across apps.
 */
object MeetingPrompts {
    const val TRANSCRIBE = """This is part {n} of {total} of a meeting recording. People speak Hindi, English, or a mix of both (Hinglish).
Write down exactly what is said, in Roman script only: Hindi words in Latin letters the way Indians type them on WhatsApp (for example "haan, kal tak bhej dunga"), English words as normal English. Never use Devanagari. Do not translate.
Put each speaker turn on its own line as: [mm:ss] Speaker N: text
Times are from the start of this part. Number speakers Speaker 1, Speaker 2 and so on, and keep the same number for the same voice.
Write (unclear) for words you cannot make out. Leave out filler sounds (uh, um, ah, hmm), silence, music and background noise. Write nothing else: no heading, no summary, no notes."""

    const val PREVIOUS = """The previous part ended with these lines. Keep the same speaker numbers for the same voices:
{previous}"""

    const val ROMANIZE = """Convert this meeting transcript to Roman script. Hindi words, whether in Devanagari or Latin letters, go into Latin letters the way Indians type them on WhatsApp. English words stay English. Do not translate, summarise, add or drop anything. Keep every line's [time] and Speaker label exactly as they are, one line per line. Output only the converted transcript."""

    const val SUMMARY = """You write meeting notes from the transcript of a meeting held in Hindi, English or Hinglish.
Write the notes in simple English. Keep names, places and Hindi terms as they are, in Roman script.
Use only what is in the transcript. Do not invent names, dates, numbers or decisions.
Reply in exactly this format:
Title: <5 to 8 words>
Summary:
<3 to 5 short sentences>
Key points:
- <point>
Decisions:
- <decision, or "None">
Action items:
- <who> → <what> → <by when, or "no date">  (or "- None")"""

    /** Part n (1-based) of total; [previous] = the last lines of the part before, when it is already done. */
    fun transcribe(n: Int, total: Int, previous: List<String>?): String {
        var p = TRANSCRIBE.replace("{n}", n.toString()).replace("{total}", total.toString())
        if (n > 1 && !previous.isNullOrEmpty()) p += "\n" + PREVIOUS.replace("{previous}", previous.joinToString("\n"))
        return p
    }
}

data class MeetingSummary(val title: String?, val body: String)

/** Deepgram utterance (seconds from the start of the part, speaker from 0). */
data class Utterance(val startSec: Double, val speaker: Int, val text: String)

/** Groq Whisper verbose_json segment. */
data class Segment(val startSec: Double, val endSec: Double, val text: String)

/** A piece of the recording sent on its own: [startMs, endMs) of the whole meeting. */
data class PartSpan(val index: Int, val startMs: Long, val endMs: Long, val hasSpeech: Boolean)

object MeetingText {
    private val TIMED = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?::(\d{1,2}))?]\s*(.*)$""")
    const val GROQ_NOTE = "— Speakers not separated (backup engine) —"

    /** [mm:ss], or [h:mm:ss] from one hour on. */
    fun formatTime(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** Seconds of a line's leading [time], or null when the line has none. */
    fun lineSeconds(line: String): Long? {
        val m = TIMED.find(line) ?: return null
        val (a, b, c) = m.destructured
        return if (c.isNotEmpty()) a.toLong() * 3600 + b.toLong() * 60 + c.toLong() else a.toLong() * 60 + b.toLong()
    }

    /** Moves part-relative [mm:ss] times to meeting times; lines without a time are kept as they are. */
    fun offsetLines(lines: List<String>, offsetMs: Long): List<String> = lines.map { line ->
        val m = TIMED.find(line) ?: return@map line
        val sec = lineSeconds(line)!! + offsetMs / 1000
        "[${formatTime(sec)}] ${m.groupValues[4]}".trimEnd()
    }

    /** A model's answer as transcript lines: no blank lines, code fences or thinking. */
    fun modelLines(out: String): List<String> =
        out.replace(Regex("(?s)<think>.*?</think>"), "").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }

    fun deepgramLines(utterances: List<Utterance>): List<String> = utterances.filter { it.text.isNotBlank() }.map {
        "[${formatTime(it.startSec.toLong())}] Speaker ${it.speaker + 1}: ${it.text.trim()}"
    }

    /** Whisper segments merged into lines of about [lineSec] seconds. */
    fun groqLines(segments: List<Segment>, lineSec: Double = 20.0): List<String> {
        val out = ArrayList<String>()
        var start = -1.0
        val text = StringBuilder()
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            if (start < 0) start = s.startSec
            if (text.isNotEmpty()) text.append(' ')
            text.append(t)
            if (s.endSec - start >= lineSec) {
                out += "[${formatTime(start.toLong())}] $text"
                text.clear(); start = -1.0
            }
        }
        if (text.isNotEmpty()) out += "[${formatTime(start.toLong())}] $text"
        return out
    }

    fun words(lines: List<String>) = lines.sumOf { l -> l.split(Regex("\\s+")).count { it.isNotEmpty() } }

    /** Splits lines into chunks of at most [maxWords] words (a longer single line stays whole). */
    fun chunkByWords(lines: List<String>, maxWords: Int): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var n = 0
        for (l in lines) {
            val w = words(listOf(l))
            if (cur.isNotEmpty() && n + w > maxWords) { out += cur; cur = ArrayList(); n = 0 }
            cur += l; n += w
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** The meeting's transcript: parts in order, times from the start of the meeting. */
    fun join(spans: List<PartSpan>, results: List<PartResult>): String {
        val byIndex = results.associateBy { it.index }
        val out = ArrayList<String>()
        for (s in spans) {
            val r = byIndex[s.index] ?: continue
            if (r.lines.isEmpty()) continue
            if (r.engine == "groq") out += GROQ_NOTE
            out += offsetLines(r.lines, s.startMs)
        }
        return out.joinToString("\n")
    }

    /** "gemini" | "deepgram" | "groq" | "mixed". */
    fun engineLabel(results: List<PartResult>): String {
        val used = results.map { it.engine }.filter { it.isNotEmpty() }.distinct()
        return when (used.size) { 0 -> ""; 1 -> used[0]; else -> "mixed" }
    }

    fun engineName(engine: String) = when (engine) {
        "gemini" -> "Gemini"; "deepgram" -> "Deepgram"; "groq" -> "Groq Whisper"; "mixed" -> "Mixed engines"; else -> ""
    }

    fun durationText(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d h %02d min".format(s / 3600, (s / 60) % 60) else if (s >= 60) "${s / 60} min" else "$s s"
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun defaultTitle(createdMs: Long): String =
        "Meeting " + SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(Date(createdMs))

    /** Title line → title; everything else is the summary block, kept as written. */
    fun parseSummary(out: String): MeetingSummary {
        val lines = out.replace(Regex("(?s)<think>.*?</think>"), "").trim().removePrefix("```").removeSuffix("```").trim().lines()
        val titleLine = lines.firstOrNull { it.trim().trimStart('#', '*', ' ').lowercase().startsWith("title:") }
        val title = titleLine?.substringAfter(':')?.trim()?.trim('*', '"', ' ')?.takeIf { it.isNotEmpty() }
        val body = lines.filter { it !== titleLine }.joinToString("\n").trim()
        return MeetingSummary(title, body)
    }

    private val SECTION = Regex("""^(summary|key points|decisions|action items|title)\s*:\s*""", RegexOption.IGNORE_CASE)

    /** A list card's preview of a summary: plain sentences, no "Summary:" / "Key points:" headings or list markers. */
    fun preview(summary: String): String = summary.lines()
        .map { it.trim().replace("**", "").replace(Regex("^#+\\s*"), "").replace(SECTION, "") }
        .map { it.replace(Regex("""^([-*•]|\d{1,3}[.)])\s+"""), "").trim() }
        .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
        .joinToString(" ")

    /** Text to copy or share. */
    fun shareText(m: Meeting): String = buildString {
        append(m.title).append("\n")
        append(metaLine(m)).append("\n\n")
        if (m.summary.isNotBlank()) append(m.summary.trim()).append("\n\n")
        if (m.transcript.isNotBlank()) append("Transcript\n").append(m.transcript.trim())
    }.trim()

    fun metaLine(m: Meeting): String = listOf(
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(m.createdMs)),
        if (m.durationMs > 0) durationText(m.durationMs) else "",
        engineName(m.engine),
    ).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Sync items are at most 512 KB: very long transcripts are cut at a line, with a note. */
    fun fitForSync(transcript: String, other: String, maxBytes: Int = 480_000): String {
        val budget = maxBytes - other.toByteArray().size
        if (transcript.toByteArray().size <= budget) return transcript
        val note = "\n(Transcript cut to fit sync. The full text is on the phone that recorded it.)"
        val out = StringBuilder()
        var bytes = note.toByteArray().size
        for (l in transcript.lines()) {
            val b = l.toByteArray().size + 1
            if (bytes + b > budget) break
            out.append(l).append('\n'); bytes += b
        }
        return out.toString().trimEnd() + note
    }
}

/**
 * Where to cut a long recording: about every 15 minutes, at the quietest second within a minute
 * of the mark. Up to 18 minutes stays one part; the last 18–30 minutes are split in two halves.
 */
object CutPlanner {
    const val PART_SEC = 900
    const val WINDOW_SEC = 60
    const val MIN_LAST_SEC = 180

    /** Cut points in ms (at the middle of the quietest second). */
    fun cuts(energy: FloatArray, partSec: Int = PART_SEC, windowSec: Int = WINDOW_SEC, minLastSec: Int = MIN_LAST_SEC): List<Long> {
        val total = energy.size
        val out = ArrayList<Long>()
        var start = 0
        while (total - start > partSec + minLastSec) {
            val left = total - start
            // Less than two parts' worth left: two even halves, not a full part and a short tail.
            val target = if (left < 2 * partSec) start + left / 2 else start + partSec
            val lo = maxOf(start + 1, target - windowSec)
            val hi = minOf(target + windowSec, total - minLastSec)
            var best = target.coerceIn(lo, hi)
            for (i in lo..hi) {
                val e = energy[i]; val b = energy[best]
                if (e < b || (e == b && kotlin.math.abs(i - target) < kotlin.math.abs(best - target))) best = i
            }
            out += best * 1000L + 500
            start = best
        }
        return out
    }

    fun spans(energy: FloatArray, speech: BooleanArray, totalMs: Long): List<PartSpan> {
        val cuts = cuts(energy)
        val starts = listOf(0L) + cuts
        val ends = cuts + totalMs
        return starts.indices.map { i ->
            val from = (starts[i] / 1000).toInt()
            val to = ((ends[i] + 999) / 1000).toInt().coerceAtMost(speech.size)
            // No energy log (older or broken file): assume speech, the engines decide.
            val has = speech.isEmpty() || (from until to).any { it < speech.size && speech[it] }
            PartSpan(i, starts[i], ends[i], has)
        }
    }
}

/**
 * Which URL and key a meeting call uses, per provider: the phone's own key for that provider first
 * (direct), else the WhisperAsh account gateway, else none.
 */
object MeetingRoutes {
    const val GEMINI = "gemini"
    const val DEEPGRAM = "deepgram"
    const val GROQ = "groq"
    const val DEEPGRAM_BASE = "https://api.deepgram.com"

    fun directBase(provider: String) = when (provider) {
        GEMINI -> GeminiClient.BASE
        DEEPGRAM -> DEEPGRAM_BASE
        else -> GroqClient.BASE
    }

    fun pick(provider: String, ownKey: String?, accountToken: String?, server: String): ApiTarget? {
        if (!ownKey.isNullOrBlank()) {
            val base = directBase(provider)
            return ApiTarget(base, ownKey, false, "$base/")
        }
        if (!accountToken.isNullOrBlank()) {
            val s = server.trimEnd('/')
            return ApiTarget("$s/$provider", accountToken, true, "$s/")
        }
        return null
    }
}

/** Per-meeting folder: audio.m4a, energy.bin (loudness per second), part-N.m4a / part-N.txt while transcribing. */
object MeetingFiles {
    fun dir(root: File, id: String) = File(File(root, "meetings"), id)

    /** Removes a meeting's folder, given its audio path (only inside a "meetings" folder). */
    fun deleteFor(audioPath: String) {
        val d = File(audioPath).parentFile ?: return
        if (d.parentFile?.name == "meetings") d.deleteRecursively()
    }

    fun writeEnergy(dir: File, rms: FloatArray, speech: BooleanArray) {
        DataOutputStream(File(dir, "energy.bin").outputStream().buffered()).use { o ->
            o.writeInt(rms.size)
            for (i in rms.indices) { o.writeFloat(rms[i]); o.writeBoolean(speech.getOrElse(i) { false }) }
        }
    }

    fun readEnergy(dir: File): Pair<FloatArray, BooleanArray>? = try {
        DataInputStream(File(dir, "energy.bin").inputStream().buffered()).use { i ->
            val n = i.readInt()
            val rms = FloatArray(n); val speech = BooleanArray(n)
            for (k in 0 until n) { rms[k] = i.readFloat(); speech[k] = i.readBoolean() }
            rms to speech
        }
    } catch (_: Exception) { null }

    /** A finished part's text, kept so Retry does not pay for it again: engine on the first line. */
    fun writePart(dir: File, r: PartResult) {
        File(dir, "part-${r.index}.txt").writeText(r.engine + "\n" + r.lines.joinToString("\n"))
    }

    fun readPart(dir: File, index: Int): PartResult? {
        val f = File(dir, "part-$index.txt")
        if (!f.exists()) return null
        val lines = f.readText().lines()
        return PartResult(index, lines.first(), lines.drop(1).filter { it.isNotBlank() })
    }

    fun clearParts(dir: File, keepText: Boolean) {
        dir.listFiles()?.forEach {
            if (it.name.startsWith("part-") && (!keepText || it.name.endsWith(".m4a"))) it.delete()
        }
    }
}
