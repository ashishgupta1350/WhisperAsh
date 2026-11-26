package com.whisperash.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections

class MeetingTest {
    private val calls: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun audio(name: String, romanize: Boolean, result: (MeetingPart, String) -> List<String>) = object : AudioEngine {
        override val name = name
        override val needsRomanize = romanize
        override fun transcribe(part: MeetingPart, prompt: String): List<String> {
            calls += "$name:${part.index}"
            return result(part, prompt)
        }
    }

    private fun text(name: String, maxWords: Int = 6000, result: (String, String) -> String) = object : TextEngine {
        override val name = name
        override val maxWords = maxWords
        override fun complete(system: String, user: String): String {
            calls += "$name-text"
            return result(system, user)
        }
    }

    private fun part(i: Int, total: Int = 1, speech: Boolean = true) =
        MeetingPart(i, total, File("part-$i.m4a"), "audio/mp4", i * 900_000L, 900_000L, speech)

    private val upper = text("gemini") { _, user -> user.uppercase() }
    private fun account(status: Int, code: String) = ApiException(status, "WhisperAsh", "account says $code", code)

    // ---------- chain order and fallbacks ----------
    @Test fun geminiFirstAndNoRomanize() {
        val chain = MeetingChain(listOf(
            audio("gemini", false) { _, _ -> listOf("[00:01] Speaker 1: haan theek hai") },
            audio("deepgram", true) { _, _ -> fail("not reached"); emptyList() },
        ), listOf(upper))
        val r = chain.transcribePart(part(0), null)
        assertEquals("gemini", r.engine)
        assertEquals(listOf("[00:01] Speaker 1: haan theek hai"), r.lines)
        assertEquals(listOf("gemini:0"), calls)
    }

    @Test fun fallsBackToDeepgramThenGroqWithRomanize() {
        val chain = MeetingChain(listOf(
            audio("gemini", false) { _, _ -> throw account(503, "no_key_configured") },
            audio("deepgram", true) { _, _ -> throw ApiException(0, "Deepgram", "offline") },
            audio("groq", true) { _, _ -> listOf("[00:00] नमस्ते sab") },
        ), listOf(upper))
        val r = chain.transcribePart(part(0), null)
        assertEquals("groq", r.engine)
        assertEquals(listOf("[00:00] नमस्ते SAB"), r.lines) // went through the text engine (ROMANIZE)
        assertEquals(listOf("gemini:0", "deepgram:0", "groq:0", "gemini-text"), calls)
    }

    @Test fun emptyAnswerCountsAsFailure() {
        val chain = MeetingChain(listOf(
            audio("gemini", false) { _, _ -> emptyList() },
            audio("deepgram", true) { _, _ -> listOf("[00:02] Speaker 2: ok") },
        ), emptyList())
        assertEquals("deepgram", chain.transcribePart(part(0), null).engine)
    }

    @Test fun meetingLimitStopsTheWholeChain() {
        for ((status, code) in listOf(429 to "meeting_limit", 401 to "session_expired", 403 to "account_blocked")) {
            calls.clear()
            val chain = MeetingChain(listOf(
                audio("gemini", false) { _, _ -> throw account(status, code) },
                audio("deepgram", true) { _, _ -> listOf("x") },
            ), listOf(upper))
            try {
                runBlocking { chain.transcribeAll(listOf(part(0, 3), part(1, 3), part(2, 3))) }
                fail("should stop")
            } catch (e: MeetingStopped) {
                assertEquals("account says $code", e.message)
            }
            assertFalse(code, calls.any { it.startsWith("deepgram") })
        }
    }

    @Test fun ownKeyRefusalDoesNotStop() {
        // Same code but from the provider directly (own key): just try the next engine.
        val chain = MeetingChain(listOf(
            audio("gemini", false) { _, _ -> throw ApiException(429, "Gemini", "quota", "meeting_limit") },
            audio("deepgram", true) { _, _ -> listOf("[00:00] Speaker 1: ok") },
        ), emptyList())
        assertEquals("deepgram", chain.transcribePart(part(0), null).engine)
        assertTrue(MeetingChain.isStop(account(429, "meeting_limit")))
        assertFalse(MeetingChain.isStop(account(429, "upstream_busy")))
        assertFalse(MeetingChain.isStop(account(502, "upstream_key")))
    }

    @Test fun summaryLimitStopsWithoutFallback() {
        val chain = MeetingChain(emptyList(), listOf(
            text("gemini") { _, _ -> throw account(429, "meeting_limit") },
            text("groq") { _, _ -> fail("not reached"); "" },
        ))
        try { chain.summarize("[00:00] Speaker 1: hi"); fail("should stop") } catch (_: MeetingStopped) {}
    }

    @Test fun everyEngineFailingKeepsOtherPartsAndFails() {
        val done = ArrayList<PartResult>()
        val chain = MeetingChain(listOf(
            audio("gemini", false) { p, _ -> if (p.index == 1) throw ApiException(500, "Gemini", "boom") else listOf("[00:00] Speaker 1: part ${p.index}") },
        ), emptyList())
        try {
            runBlocking { chain.transcribeAll(listOf(part(0, 3), part(1, 3), part(2, 3)), onPart = { synchronized(done) { done += it } }) }
            fail("should fail")
        } catch (e: MeetingFailed) {
            assertTrue(e.message!!, e.message!!.contains("part 2 of 3"))
        }
        assertEquals(setOf(0, 2), done.map { it.index }.toSet())
    }

    @Test fun cachedPartsAreNotSentAgain() {
        val chain = MeetingChain(listOf(audio("gemini", false) { p, _ -> listOf("[00:00] new ${p.index}") }), emptyList())
        val cached = mapOf(0 to PartResult(0, "deepgram", listOf("[00:00] old 0")))
        val out = runBlocking { chain.transcribeAll(listOf(part(0, 2), part(1, 2)), cached) }
        assertEquals(listOf("deepgram", "gemini"), out.map { it.engine })
        assertEquals(listOf("gemini:1"), calls)
    }

    @Test fun silentPartIsSkipped() {
        val chain = MeetingChain(listOf(audio("gemini", false) { _, _ -> fail("silence is not sent"); emptyList() }), emptyList())
        val r = chain.transcribePart(part(0, speech = false), null)
        assertEquals("", r.engine)
        assertTrue(r.lines.isEmpty())
    }

    @Test fun previousLinesGoInThePromptWhenReady() {
        val prompts = Collections.synchronizedMap(HashMap<Int, String>())
        val chain = MeetingChain(listOf(audio("gemini", false) { p, prompt ->
            prompts[p.index] = prompt
            (1..8).map { "[00:0$it] Speaker 1: line $it of part ${p.index}" }
        }), emptyList(), maxParallel = 1)
        runBlocking { chain.transcribeAll(listOf(part(0, 2), part(1, 2))) }
        assertTrue(prompts[0]!!.startsWith("This is part 1 of 2 of a meeting recording."))
        assertFalse(prompts[0]!!.contains("The previous part ended"))
        val p2 = prompts[1]!!
        assertTrue(p2.startsWith("This is part 2 of 2 of a meeting recording."))
        assertTrue(p2.contains("The previous part ended with these lines. Keep the same speaker numbers for the same voices:\n[00:03] Speaker 1: line 3 of part 0"))
        assertFalse(p2.contains("line 2 of part 0")) // only the last 6 lines
    }

    @Test fun atMostTwoPartsInFlight() {
        var now = 0; var most = 0
        val lock = Any()
        val chain = MeetingChain(listOf(audio("gemini", false) { _, _ ->
            synchronized(lock) { now++; most = maxOf(most, now) }
            Thread.sleep(50)
            synchronized(lock) { now-- }
            listOf("[00:00] x")
        }), emptyList())
        val out = runBlocking { chain.transcribeAll((0 until 5).map { part(it, 5) }) }
        assertEquals((0 until 5).toList(), out.map { it.index })
        assertEquals(2, most)
    }

    // ---------- Gemini model and busy retry ----------
    @Test fun meetingAudioUsesFlashAndTextUsesFlashLite() {
        assertEquals("gemini-3.6-flash", GeminiMeetingClient.MEETING_MODEL)
        assertEquals("gemini-3.5-flash-lite", GeminiMeetingClient.MODEL)
    }

    @Test fun busyGeminiIsRetriedOnceAfterFiveSeconds() {
        for (busy in listOf(ApiException(503, "Gemini", "overloaded", "503"), ApiException(429, "WhisperAsh", "busy", "upstream_busy"))) {
            val waits = ArrayList<Long>()
            var n = 0
            val out = MeetingChain.retryIfBusy({ waits += it }) { if (n++ == 0) throw busy else "ok" }
            assertEquals("ok", out)
            assertEquals(2, n)
            assertEquals(listOf(5_000L), waits)
        }
    }

    @Test fun busyTwiceGoesToTheNextProvider() {
        var n = 0
        try {
            MeetingChain.retryIfBusy({}) { n++; throw ApiException(503, "Gemini", "overloaded") }
            fail("should throw")
        } catch (e: ApiException) {
            assertEquals(503, e.status)
        }
        assertEquals(2, n) // one retry only
        // In the chain: the second busy answer falls through to Deepgram.
        val chain = MeetingChain(listOf(
            audio("gemini", false) { _, _ -> MeetingChain.retryIfBusy({}) { throw ApiException(503, "Gemini", "overloaded") } },
            audio("deepgram", true) { _, _ -> listOf("[00:00] Speaker 1: ok") },
        ), emptyList())
        assertEquals("deepgram", chain.transcribePart(part(0), null).engine)
    }

    @Test fun refusalsAndOtherErrorsAreNotRetried() {
        for (e in listOf(account(429, "meeting_limit"), account(401, "session_expired"), account(403, "account_blocked"),
            account(503, "no_key_configured"), ApiException(500, "Gemini", "boom"), ApiException(0, "Gemini", "offline"))) {
            var n = 0
            val waits = ArrayList<Long>()
            try { MeetingChain.retryIfBusy({ waits += it }) { n++; throw e }; fail() } catch (_: ApiException) {}
            assertEquals(e.code ?: e.message, 1, n)
            assertTrue(waits.isEmpty())
        }
    }

    @Test fun progressReportsSurviveRacingParts() {
        // Parts finishing while progress is read used to throw NoSuchElementException (and fail the meeting).
        val chain = MeetingChain(listOf(audio("gemini", false) { _, _ -> listOf("[00:00] x") }), emptyList())
        repeat(40) {
            val out = runBlocking { chain.transcribeAll((0 until 30).map { part(it, 30) }, onProgress = { _, _, f -> f.minOrNull() }) }
            assertEquals(30, out.size)
        }
    }

    // ---------- romanize ----------
    @Test fun romanizeFallsBackAndChunksForGroq() {
        val groqChunks = ArrayList<Int>()
        val chain = MeetingChain(emptyList(), listOf(
            text("gemini") { _, _ -> throw ApiException(500, "Gemini", "down") },
            text("groq", maxWords = 12) { _, user -> groqChunks += user.lines().size; user.lowercase() },
        ))
        val lines = (1..6).map { "[00:0$it] Speaker 1: एक दो तीन" } // 6 words a line
        val out = chain.romanize(lines)
        assertEquals(lines.map { it.lowercase() }, out)
        assertEquals(listOf(2, 2, 2), groqChunks)
    }

    @Test fun romanizeKeepsOriginalWhenEverythingFails() {
        val chain = MeetingChain(emptyList(), listOf(text("gemini") { _, _ -> "Sure! Here you go." }))
        val lines = (1..6).map { "[00:0$it] Speaker 1: a" }
        assertEquals(lines, chain.romanize(lines))
    }

    @Test fun romanizePromptIsTheContractText() {
        var sys = ""
        MeetingChain(emptyList(), listOf(text("gemini") { s, u -> sys = s; u })).romanize(listOf("[00:00] a"))
        assertTrue(sys.startsWith("Convert this meeting transcript to Roman script."))
    }

    // ---------- own key vs account ----------
    @Test fun ownKeyFirstThenAccountThenNothing() {
        val server = "https://sync.example.dev/"
        val own = MeetingRoutes.pick(MeetingRoutes.DEEPGRAM, "dg-own", "tok", server)!!
        assertEquals("https://api.deepgram.com", own.base)
        assertFalse(own.viaAccount)
        assertEquals("dg-own", own.token)

        val acc = MeetingRoutes.pick(MeetingRoutes.GEMINI, null, "tok", server)!!
        assertEquals("https://sync.example.dev/gemini", acc.base)
        assertTrue(acc.viaAccount)
        assertEquals("tok", acc.token)
        assertEquals("https://sync.example.dev/groq", MeetingRoutes.pick(MeetingRoutes.GROQ, "", "tok", server)!!.base)
        assertEquals("https://api.groq.com", MeetingRoutes.pick(MeetingRoutes.GROQ, "gsk", null, server)!!.base)
        assertNull(MeetingRoutes.pick(MeetingRoutes.GEMINI, null, null, server))
        assertNull(MeetingRoutes.pick(MeetingRoutes.GEMINI, " ", "", server))
    }

    // ---------- cut planning ----------
    @Test fun cutsAtQuietestSecondNearEachMark() {
        val e = FloatArray(50 * 60) { 0.1f }
        e[905] = 0.001f   // quiet just after 15:00
        e[1790] = 0.002f  // quiet just before 30:00 (+ the first cut)
        e[700] = 0.0f     // too far from the mark: ignored
        // The last 20 minutes (1790 s → 3000 s) are split in two even halves.
        assertEquals(listOf(905_500L, 1_790_500L, 2_395_500L), CutPlanner.cuts(e))
    }

    @Test fun shortMeetingsAreOnePartAndTailsAreNotTiny() {
        assertTrue(CutPlanner.cuts(FloatArray(17 * 60) { 0.1f }).isEmpty())
        assertTrue(CutPlanner.cuts(FloatArray(18 * 60) { 0.1f }).isEmpty())
        // 19 min: two even halves.
        assertEquals(listOf(570_500L), CutPlanner.cuts(FloatArray(19 * 60) { 0.1f }))
        // 3 hours: no part longer than 18 minutes or shorter than 8.
        val total = 180 * 60
        val cuts = CutPlanner.cuts(FloatArray(total) { (it % 97) / 97f })
        val bounds = listOf(0L) + cuts + total * 1000L
        for (i in 1 until bounds.size) {
            val len = bounds[i] - bounds[i - 1]
            assertTrue("part $i is $len ms", len in 8 * 60_000L..18 * 60_000L)
        }
        assertEquals(13, bounds.size - 1)
    }

    @Test fun spansKnowWhichPartsHaveSpeech() {
        val n = 40 * 60
        val speech = BooleanArray(n) { it < 600 || it > 2000 }
        val spans = CutPlanner.spans(FloatArray(n) { 0.1f }, speech, n * 1000L)
        assertEquals(listOf(true, false, true), spans.map { it.hasSpeech })
        assertEquals(0L, spans[0].startMs)
        assertEquals(n * 1000L, spans.last().endMs)
        assertEquals(spans[0].endMs, spans[1].startMs)
        // No energy log: one part, assumed to have speech.
        assertEquals(listOf(PartSpan(0, 0, 5000, true)), CutPlanner.spans(FloatArray(0), BooleanArray(0), 5000))
    }

    // ---------- summary ----------
    @Test fun summaryTitleAndBlock() {
        val s = MeetingText.parseSummary(
            "**Title:** Budget review for Q3 launch\nSummary:\nWe met.\nKey points:\n- Rent up\nDecisions:\n- None\nAction items:\n- Ravi → send quote → Friday"
        )
        assertEquals("Budget review for Q3 launch", s.title)
        assertTrue(s.body.startsWith("Summary:\nWe met."))
        assertTrue(s.body.endsWith("- Ravi → send quote → Friday"))
        assertFalse(s.body.contains("Title"))
        val none = MeetingText.parseSummary("Summary:\nShort call.")
        assertNull(none.title)
        assertEquals("Summary:\nShort call.", none.body)
        assertEquals("Title: x", MeetingText.parseSummary("<think>hmm</think>\nTitle: x").let { "Title: " + it.title })
    }

    @Test fun summaryFallsBackToGroqAndReturnsNullWhenAllFail() {
        val chain = MeetingChain(emptyList(), listOf(
            text("gemini") { _, _ -> throw ApiException(503, "WhisperAsh", "no key", "no_key_configured") },
            text("groq") { sys, _ -> assertTrue(sys.contains("Reply in exactly this format:")); "Title: Weekly sync\nSummary:\nOk." },
        ))
        assertEquals("Weekly sync", chain.summarize("[00:00] Speaker 1: hi")!!.title)
        val dead = MeetingChain(emptyList(), listOf(text("gemini") { _, _ -> throw ApiException(0, "Gemini", "offline") }))
        assertNull(dead.summarize("[00:00] Speaker 1: hi"))
        assertNull(dead.summarize("  "))
    }

    // ---------- time stamps ----------
    @Test fun timesMoveToMeetingTime() {
        assertEquals("00:05", MeetingText.formatTime(5))
        assertEquals("59:59", MeetingText.formatTime(3599))
        assertEquals("1:00:00", MeetingText.formatTime(3600))
        assertEquals("2:05:09", MeetingText.formatTime(7509))
        val lines = listOf("[00:05] Speaker 1: haan", "continued line", "[14:59] Speaker 2: ok", "[1:02:03] Speaker 1: x")
        assertEquals(
            listOf("[15:05] Speaker 1: haan", "continued line", "[29:59] Speaker 2: ok", "[1:17:03] Speaker 1: x"),
            MeetingText.offsetLines(lines, 900_000),
        )
        assertEquals(listOf("[1:00:05] Speaker 1: a"), MeetingText.offsetLines(listOf("[45:05] Speaker 1: a"), 900_400))
    }

    @Test fun joinOrdersPartsAndMarksGroq() {
        val spans = listOf(PartSpan(0, 0, 900_500, true), PartSpan(1, 900_500, 1_800_000, false), PartSpan(2, 1_800_000, 2_700_000, true))
        val results = listOf(
            PartResult(2, "groq", listOf("[00:10] baad mein")),
            PartResult(0, "gemini", listOf("[00:01] Speaker 1: shuru")),
            PartResult(1, "", emptyList()),
        )
        assertEquals("[00:01] Speaker 1: shuru\n${MeetingText.GROQ_NOTE}\n[30:10] baad mein", MeetingText.join(spans, results))
        assertEquals("mixed", MeetingText.engineLabel(results))
        assertEquals("gemini", MeetingText.engineLabel(results.take(2).drop(1)))
    }

    @Test fun deepgramAndGroqLines() {
        assertEquals(
            listOf("[00:03] Speaker 1: namaste", "[01:05] Speaker 2: hello ji"),
            MeetingText.deepgramLines(listOf(Utterance(3.4, 0, " namaste "), Utterance(65.0, 1, "hello ji"), Utterance(70.0, 0, " "))),
        )
        val segs = listOf(Segment(0.0, 8.0, "ek"), Segment(8.0, 15.0, "do"), Segment(15.0, 21.0, "teen"), Segment(21.0, 25.0, "char"))
        assertEquals(listOf("[00:00] ek do teen", "[00:21] char"), MeetingText.groqLines(segs))
    }

    @Test fun modelLinesDropFencesAndBlanks() {
        assertEquals(listOf("[00:01] Speaker 1: a", "[00:02] Speaker 2: b"),
            MeetingText.modelLines("```\n[00:01] Speaker 1: a\n\n  [00:02] Speaker 2: b  \n```"))
    }

    // ---------- prompts / sync / plan ----------
    @Test fun transcribePromptIsTheContractText() {
        val p = MeetingPrompts.transcribe(1, 1, listOf("ignored for part 1"))
        assertTrue(p.startsWith("This is part 1 of 1 of a meeting recording. People speak Hindi, English, or a mix of both (Hinglish).\n"))
        assertTrue(p.endsWith("\nWrite (unclear) for words you cannot make out. Leave out filler sounds (uh, um, ah, hmm), silence, music and background noise. Write nothing else: no heading, no summary, no notes."))
        assertTrue(p.contains("Put each speaker turn on its own line as: [mm:ss] Speaker N: text\n"))
        assertTrue(MeetingPrompts.SUMMARY.endsWith("- <who> → <what> → <by when, or \"no date\">  (or \"- None\")"))
    }

    @Test fun longTranscriptsAreCutForSync() {
        val t = (1..20_000).joinToString("\n") { "[00:00] Speaker 1: yeh ek lambi meeting ki line hai number $it" }
        val fit = MeetingText.fitForSync(t, "title summary")
        assertTrue(fit.toByteArray().size <= 480_000)
        assertTrue(fit.endsWith("(Transcript cut to fit sync. The full text is on the phone that recorded it.)"))
        assertEquals("short", MeetingText.fitForSync("short", ""))
    }

    @Test fun listPreviewIsPlainText() {
        val s = "Summary:\nThe team is testing the app.\n\nKey points:\n- They are experimenting with Gemini\n- **Speed** matters\n1. First\nDecisions:\n- None\nAction items:\n- Ravi → send quote → Friday"
        assertEquals(
            "The team is testing the app. They are experimenting with Gemini Speed matters First Ravi → send quote → Friday",
            MeetingText.preview(s),
        )
        assertEquals("Short call.", MeetingText.preview("Summary: Short call."))
    }

    @Test fun countsUseSingularForOne() {
        assertEquals("1 word", counted(1, "word"))
        assertEquals("0 words", counted(0, "word"))
        assertEquals("1,240 words", counted(1240, "word"))
        assertEquals("1 meeting", counted(1, "meeting"))
        assertEquals("2 dictations", counted(2, "dictation"))
    }

    @Test fun meetingMinutesLine() {
        assertEquals("87 min left today", AccountPlan(2000, 0, 2000, "day", 0L, "active", false, 120, 33, 87).meetingShort())
        assertNull(AccountPlan(2000, 0, 2000, "day", 0L, "active", false).meetingShort())
        assertEquals("87 of 120 meeting minutes left today", AccountPlan(2000, 0, 2000, "day", 0L, "active", false, 120, 33, 87).meetingSummary())
        assertNull(AccountPlan(2000, 0, 2000, "day", 0L, "active", false).meetingSummary())
    }
}
