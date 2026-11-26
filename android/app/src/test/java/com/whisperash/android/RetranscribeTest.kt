package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "Re-transcribe (better model)" for History entries and meetings. */
class RetranscribeTest {
    private val server = "https://sync.example.dev"

    @Test fun betterModelIsOneConstantWithAFourMinuteLimit() {
        assertEquals("gemini-3.6-flash", GeminiMeetingClient.BETTER_MODEL)
        assertEquals(mapOf("thinkingLevel" to "minimal", "includeThoughts" to false), GeminiMeetingClient.BETTER_THINKING)
        assertEquals(4L, GeminiMeetingClient.BETTER_TIMEOUT_MIN)
        assertEquals(GeminiMeetingClient.MEETING_MODEL, GeminiMeetingClient.BETTER_MODEL)
        assertEquals("openai/gpt-oss-120b", GroqMeetingClient.CHAT_MODEL)
    }

    @Test fun ownKeyGoesDirectElseTheAccountGateway() {
        val ownGemini = MeetingRoutes.pick(MeetingRoutes.GEMINI, "AIza-own", "session", server)!!
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent",
            GeminiMeetingClient.url(ownGemini, GeminiMeetingClient.BETTER_MODEL))
        val accGemini = MeetingRoutes.pick(MeetingRoutes.GEMINI, null, "session", server)!!
        assertEquals("$server/gemini/v1beta/models/gemini-3.6-flash:generateContent", GeminiMeetingClient.url(accGemini, GeminiMeetingClient.BETTER_MODEL))
        val ownGroq = MeetingRoutes.pick(MeetingRoutes.GROQ, "gsk-own", "session", server)!!
        assertEquals("https://api.groq.com/openai/v1/chat/completions", GroqMeetingClient.chatUrl(ownGroq))
        val accGroq = MeetingRoutes.pick(MeetingRoutes.GROQ, null, "session", server)!!
        assertEquals("$server/groq/openai/v1/chat/completions", GroqMeetingClient.chatUrl(accGroq))
        assertNull(MeetingRoutes.pick(MeetingRoutes.GEMINI, null, null, server))   // → "No Gemini key — add one in Settings or sign in"
    }

    @Test fun purposeHeaderOnlyThroughTheAccount() {
        val acc = ApiTarget("$server/gemini", "session", true, "$server/")
        assertEquals(
            listOf("Authorization" to "Bearer session", "x-whisperash-purpose" to "dictation"),
            GatewayHeaders.forTarget(acc, GatewayHeaders.DICTATION, "x-goog-api-key" to "session"),
        )
        assertEquals("meeting", GatewayHeaders.forTarget(acc, GatewayHeaders.MEETING, "x" to "y").toMap()["x-whisperash-purpose"])
        val own = ApiTarget(GeminiClient.BASE, "AIza-own", false, "")
        assertEquals(listOf("x-goog-api-key" to "AIza-own"), GatewayHeaders.forTarget(own, GatewayHeaders.DICTATION, "x-goog-api-key" to "AIza-own"))
    }

    @Test fun menuItemHiddenWithoutTheRecording() {
        assertFalse(Retranscribe.canRun(null))
        assertFalse(Retranscribe.canRun(""))
        assertFalse(Retranscribe.canRun("/data/rec/old.m4a") { false })
        assertTrue(Retranscribe.canRun("/data/rec/new.m4a") { true })
    }

    @Test fun undoKeepsTheOriginalAndRestoresIt() {
        val (t1, p1) = TextUndo.replace("kal meeting hai", null, "Kal meeting hai.")
        assertEquals("Kal meeting hai." to "kal meeting hai", t1 to p1)
        // A second re-transcribe keeps the original words for Undo.
        val (t2, p2) = TextUndo.replace(t1, p1, "Kal meeting hai!")
        assertEquals("Kal meeting hai!" to "kal meeting hai", t2 to p2)
        assertEquals("kal meeting hai" to null, TextUndo.restore(t2, p2))
        assertNull(TextUndo.restore("text", null))   // nothing to restore: the menu item is hidden
    }

    @Test fun errorsReadPlainly() {
        assertEquals("No Gemini key — add one in Settings or sign in", RetranscribeErrors.message(NoGeminiException()))
        assertEquals("The recording for this entry is no longer on the phone", RetranscribeErrors.message(MissingRecordingException()))
        assertEquals("Gemini took too long — try again later",
            RetranscribeErrors.message(ApiException(408, "Gemini", "Gemini took too long — try again later", "timeout")))
        assertEquals("Gemini took too long — try again later",
            RetranscribeErrors.message(ApiException(408, "WhisperAsh", "Gemini took too long — try again later", "timeout")))
        // Account answers keep the server's words.
        assertEquals("You've used your 2,000 words for today.",
            RetranscribeErrors.message(ApiException(429, "WhisperAsh", "You've used your 2,000 words for today.", "word_limit")))
        assertEquals("No connection. Try again when you're online.", RetranscribeErrors.message(ApiException(0, "Gemini", "No connection to Gemini (x)")))
        assertEquals("Gemini refused the key. Check it in Settings.", RetranscribeErrors.message(ApiException(403, "Gemini", "Gemini 403: denied")))
        assertEquals("Gemini is busy or out of quota. Try again later.", RetranscribeErrors.message(ApiException(429, "Gemini", "Gemini 429")))
        assertEquals("No speech was found in this recording.", RetranscribeErrors.message(NoSpeechException()))
        assertEquals("Gemini took too long — try again later", RetranscribeErrors.message(java.net.SocketTimeoutException("timeout")))
    }

    @Test fun dictationPromptFollowsTheScriptChoice() {
        assertTrue(Retranscribe.prompt("roman", emptyList()).contains("Never use Devanagari"))
        assertTrue(Retranscribe.prompt("devanagari", emptyList()).contains("Hindi words in Devanagari"))
        assertTrue(Retranscribe.prompt("roman", listOf("WhisperAsh")).endsWith("WhisperAsh."))
        assertFalse(Retranscribe.prompt("roman", emptyList()).contains("Spell these"))
    }

    @Test fun betterMeetingRunOnlyUsesGemini() {
        // In better mode a Gemini failure is reported, never quietly replaced by Deepgram / Groq output.
        val calls = ArrayList<String>()
        val chain = MeetingChain(listOf(object : AudioEngine {
            override val name = "gemini"
            override val needsRomanize = false
            override fun transcribe(part: MeetingPart, prompt: String): List<String> { calls += name; throw ApiException(408, "Gemini", "Gemini took too long — try again later", "timeout") }
        }), emptyList())
        try {
            kotlinx.coroutines.runBlocking { chain.transcribeAll(listOf(MeetingPart(0, 1, java.io.File("a.m4a"), "audio/mp4", 0, 1000, true))) }
            fail("should fail")
        } catch (e: MeetingFailed) {
            assertEquals("Gemini: Gemini took too long — try again later", e.detail)
        }
        assertEquals(listOf("gemini"), calls)
    }
}
