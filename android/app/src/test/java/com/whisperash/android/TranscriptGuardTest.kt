package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptGuardTest {
    private val example = "Haan, kal meeting hai. Main report bhej dunga, okay? Theek hai, let's check it."
    private val looped =
        "So, I have shared three experts with you. One contains our customer data, second contains our booking data, " +
            "third contains our receipt data. Now, I want you to analyze all these three data and segment each customer into " +
            "various categories so I can create customer hai, hai. hai let check it hai, let's check it. hai, right now before " +
            "you create any analysis, I want you to just organize the data and present"

    @Test fun catchesTheLostDictation() {
        val c = TranscriptGuard.check(looped, 139.6, example)
        assertTrue(c.broken)
        assertTrue(c.reason.startsWith("too_few_words"))
    }

    @Test fun catchesLoopsAndEchoes() {
        assertTrue(TranscriptGuard.check("ok hai hai hai hai done", 5.0, example).broken)
        assertTrue(TranscriptGuard.check("theek hai let's check it and theek hai let's check it", 8.0, example).broken)
        val loop = "So, I have a few questions. So, I have a few questions. So, I have a few questions."
        assertTrue(TranscriptGuard.check(loop, 28.0).reason.startsWith("repeated_phrase"))
    }

    @Test fun normalSpeechPasses() {
        assertFalse(TranscriptGuard.check("Thanks, see you tomorrow.", 2.7, example).broken)
        assertFalse(TranscriptGuard.check("Checking ki recording, check check ki recording ho rahi hai ki nahi?", 7.0, example).broken)
        assertFalse(TranscriptGuard.check("whether we know about their kids, whether we know about their religion, whether we know about their home state", 8.0).broken)
    }

    @Test fun redoNeverSwapsHinglishForEnglish() {
        val hinglish = "Bhai tum kya kar rahe ho mereko 5 lakh ki payment chahiye jaldi se bhej do"
        val english = "Bro what are you doing I need a payment of 5 lakhs send it quickly please now"
        assertEquals(hinglish, TranscriptGuard.pickMoreComplete(hinglish, english, 6.0))
        val loop = "So, I have a few questions. So, I have a few questions. So, I have a few questions."
        assertEquals("their demands on time", TranscriptGuard.pickMoreComplete(loop, "their demands on time", 28.0))
    }

    @Test fun splitsLongAudioAtPauses() {
        val rate = 16_000
        val pcm = ShortArray(140 * rate) { i -> (Math.sin(i * 0.07) * 12_000).toInt().toShort() }
        for (p in listOf(23, 47, 74, 99, 121)) for (i in (p * rate - rate / 4) until (p * rate + rate / 4)) pcm[i] = 0
        val pieces = TranscriptGuard.splitAtPauses(pcm, rate)
        assertTrue(pieces.size in 5..7)
        assertEquals(0, pieces.first().first)
        assertEquals(pcm.size - 1, pieces.last().last)
        for (i in pieces.indices) {
            assertTrue(pieces[i].last - pieces[i].first + 1 <= 30 * rate)
            if (i > 0) assertEquals(pieces[i - 1].last + 1, pieces[i].first)
        }
        assertTrue(Math.abs(pieces[0].last / rate.toDouble() - 23) < 0.3)
        assertEquals(listOf(0 until 29 * rate), TranscriptGuard.splitAtPauses(ShortArray(29 * rate), rate))
    }

    @Test fun joinsPiecesWithoutDoubling() {
        assertEquals("so I can create customer personas for each project",
            TranscriptGuard.joinPieces(listOf("so I can create customer", "customer personas for each project", "")))
    }
}
