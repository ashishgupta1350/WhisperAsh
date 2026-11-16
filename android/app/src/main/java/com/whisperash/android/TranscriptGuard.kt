package com.whisperash.android

import kotlin.math.abs

/**
 * Protection against Whisper silently dropping parts of a dictation, the same rules as the desktop
 * (app/src/utils/transcriptGuard.js and audioChunks.js); keep them in step.
 *
 * On 2026-11-16 a 2 min 20 s dictation came back as 73 words: Whisper looped on its prompt and
 * skipped two minutes, and re-runs left holes in long, quiet audio. So, with only a Groq key:
 *  - recordings of 30 s or more are cut at pauses into ~25 s pieces, sent in parallel and joined;
 *  - every transcript (or piece) is checked, and a broken one is redone without the script example
 *    (large-v3, then Turbo). A redo only replaces the original when clearly better, because without
 *    the example Whisper turns Hinglish into English.
 */
object TranscriptGuard {
    const val LONG_AUDIO_SECONDS = 30.0
    private const val MIN_CHECK_SECONDS = 12.0
    private const val MIN_WORDS_PER_SECOND = 1.2
    private const val TARGET_CHUNK_SECONDS = 25.0
    private const val MIN_CHUNK_SECONDS = 15.0
    private const val MAX_CHUNK_SECONDS = 30.0

    data class Check(val broken: Boolean, val reason: String) {
        val invented get() = broken && !reason.startsWith("too_few_words")
    }

    fun isLongRecording(seconds: Double) = seconds >= LONG_AUDIO_SECONDS

    private val WORD = Regex("[^\\p{L}\\p{N}']+")
    private fun words(text: String?) = (text ?: "").lowercase().split(WORD).filter { it.isNotEmpty() }
    fun wordCount(text: String?) = words(text).size

    fun check(text: String?, seconds: Double?, example: String = ""): Check {
        val w = words(text)
        var run = 1
        for (i in 1 until w.size) {
            run = if (w[i] == w[i - 1]) run + 1 else 1
            if (run >= 4) return Check(true, "repeated_word (${w[i]})")
        }
        fun repeats(n: Int, times: Int): String? {
            val seen = HashMap<String, Int>()
            for (i in 0..w.size - n) {
                val key = w.subList(i, i + n).joinToString(" ")
                val c = (seen[key] ?: 0) + 1
                seen[key] = c
                if (c >= times) return key
            }
            return null
        }
        repeats(6, 3)?.let { return Check(true, "repeated_phrase ($it)") }
        repeats(8, 2)?.let { return Check(true, "repeated_phrase ($it)") }
        val ex = words(example)
        if (ex.size >= 3) {
            val joined = " " + w.joinToString(" ") + " "
            for (i in 0..ex.size - 3) {
                val phrase = " " + ex.subList(i, i + 3).joinToString(" ") + " "
                val first = joined.indexOf(phrase)
                if (first >= 0 && joined.indexOf(phrase, first + 1) >= 0) return Check(true, "prompt_echo (${phrase.trim()})")
            }
        }
        if (seconds != null && seconds >= MIN_CHECK_SECONDS && w.size / seconds < MIN_WORDS_PER_SECOND) {
            return Check(true, "too_few_words (${w.size} words in ${seconds.toInt()} s)")
        }
        return Check(false, "")
    }

    /** Whisper's first transcript vs a redo made without the script example. */
    fun pickMoreComplete(original: String?, retry: String?, seconds: Double?): String? {
        if (retry.isNullOrBlank()) return original
        if (original.isNullOrBlank()) return retry
        val first = check(original, seconds)
        val second = check(retry, seconds)
        if (first.invented) return if (second.invented) original else retry
        if (second.broken) return original
        return if (wordCount(retry) >= wordCount(original) * 1.25) retry else original
    }

    /** [start, end) sample ranges of 16-bit mono PCM, each cut at the quietest moment between 15 and 30 s. */
    fun splitAtPauses(pcm: ShortArray, rate: Int): List<IntRange> {
        val total = pcm.size
        val maxLen = (MAX_CHUNK_SECONDS * rate).toInt()
        if (total <= maxLen) return listOf(0 until total)
        val frame = maxOf(1, (0.05 * rate).toInt())
        val out = ArrayList<IntRange>()
        var start = 0
        while (total - start > maxLen) {
            val from = start + (MIN_CHUNK_SECONDS * rate).toInt()
            val to = minOf(start + maxLen, total - frame)
            val target = start + TARGET_CHUNK_SECONDS * rate
            var bestAt = target.toInt()
            var best = Double.MAX_VALUE
            var at = from
            while (at + frame * 6 <= to) {
                var energy = 0.0
                for (i in at until at + frame * 6) { val s = pcm[i] / 32768.0; energy += s * s }
                val score = energy * (1 + abs(at - target) / rate * 0.02)
                if (score < best) { best = score; bestAt = at + frame * 3 }
                at += frame
            }
            out += start until bestAt
            start = bestAt
        }
        out += start until total
        return out
    }

    /** Joins piece transcripts, dropping a word repeated across a cut. */
    fun joinPieces(texts: List<String>): String {
        val sb = StringBuilder()
        for (raw in texts) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(t); continue }
            val last = sb.split(Regex("\\s+")).last().lowercase().replace(WORD, "")
            val parts = t.split(Regex("\\s+"))
            val first = parts.first().lowercase().replace(WORD, "")
            sb.append(' ').append(if (last.isNotEmpty() && last == first) parts.drop(1).joinToString(" ") else t)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
}
