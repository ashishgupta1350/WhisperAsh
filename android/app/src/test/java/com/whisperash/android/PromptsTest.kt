package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test fun shortCleanUtteranceSkipsCleanup() {
        assertTrue(Prompts.isShortCleanUtterance("Thanks, see you tomorrow.", "roman"))
        assertFalse(Prompts.isShortCleanUtterance("um thanks", "roman"))
        assertFalse(Prompts.isShortCleanUtterance("कल मिलते हैं", "roman")) // needs transliteration
        assertFalse(Prompts.isShortCleanUtterance("kal milte hain", "devanagari")) // may need Devanagari
        assertTrue(Prompts.isShortCleanUtterance("कल मिलते हैं", "devanagari"))
        assertFalse(Prompts.isShortCleanUtterance("hello there", "english"))
        assertFalse(Prompts.isShortCleanUtterance("one two three four five six", "roman"))
    }

    @Test fun hindiRuleAppliesForAutoAndHindiOnly() {
        assertTrue(Prompts.hindiRulesApply("auto"))
        assertTrue(Prompts.hindiRulesApply("hi"))
        assertFalse(Prompts.hindiRulesApply("en"))
        val p = Prompts.buildCleanupSystemPrompt("Ash", "auto", "roman", listOf("WhisperAsh", "Groq"), null)
        assertTrue(p.startsWith("LANGUAGE AND SCRIPT (most important rule"))
        assertTrue(p.contains("REMINDER: never translate"))
        assertTrue(p.endsWith("Custom Dictionary (use these exact spellings when they appear in the text): WhisperAsh, Groq"))
        assertTrue(p.contains("Mentions of \"Ash\""))
        assertTrue(p.contains("\$300"))
        val en = Prompts.buildCleanupSystemPrompt("", "en", "roman", emptyList(), null)
        assertFalse(en.contains("Hinglish"))
    }

    @Test fun toneByApp() {
        assertEquals("personal", Prompts.appCategory("com.whatsapp"))
        assertEquals("email", Prompts.appCategory("com.google.android.gm"))
        assertEquals("work", Prompts.appCategory("com.Slack"))
        assertEquals("other", Prompts.appCategory("com.android.chrome"))
        val p = Prompts.buildCleanupSystemPrompt("", "auto", "roman", emptyList(), "personal" to "very_casual")
        assertTrue(p.contains("The text is a personal chat message. Apply this tone: very casual"))
    }

    @Test fun whisperPromptHasBiasThenDictionary() {
        assertEquals("Haan, kal meeting hai. Main report bhej dunga, okay? Theek hai, let's check it. Groq, Ashish", Prompts.whisperPrompt("auto", "roman", listOf("Groq", "Ashish")))
        assertEquals("Groq", Prompts.whisperPrompt("en", "roman", listOf("Groq")))
    }

    @Test fun tidyAndHallucination() {
        assertEquals("Hello there.", Prompts.tidyModelOutput("<think>x</think>\"Hello there.\""))
        assertEquals("Send it.", Prompts.tidyModelOutput("<transcript>\nSend it.\n</transcript>"))
        assertTrue(Prompts.isLikelyHallucination("Thank you.", "", quietAudio = true))
        assertFalse(Prompts.isLikelyHallucination("Thank you.", "", quietAudio = false))
        assertTrue(Prompts.isLikelyHallucination("Groq, Ashish", "Groq, Ashish", quietAudio = false))
        assertFalse(Prompts.isLikelyHallucination("Please tell Ashish the Groq bill is due on Friday", "Groq, Ashish", quietAudio = false))
    }

    @Test fun spokenEmails() {
        assertEquals("ravi.s@gmail.com", Prompts.fixSpokenEmails("ravi.s at the rate gmail.com"))
        assertEquals("ravi.s@gmail.com", Prompts.fixSpokenEmails("Ravi dot S at the rate gmail dot com"))
        assertEquals("Mail me at ravi_k@yahoo.co.in please", Prompts.fixSpokenEmails("Mail me at ravi underscore k at the red yahoo dot co.in please"))
        assertEquals("send to ravi@outlook.com", Prompts.fixSpokenEmails("send to ravi at outlook dot com"))
        assertEquals("ravi@gmail.com", Prompts.fixSpokenEmails("ravi@gmail.com"))
        // Ordinary sentences stay as they are.
        assertEquals("Meet me at the station at 5.", Prompts.fixSpokenEmails("Meet me at the station at 5."))
        assertEquals("I am at home dot dot dot", Prompts.fixSpokenEmails("I am at home dot dot dot"))
        assertFalse(Prompts.isShortCleanUtterance("ravi at the rate gmail.com", "roman"))
        assertTrue(Prompts.buildCleanupSystemPrompt("", "auto", "roman", emptyList(), null).contains("EMAIL AND WEB ADDRESSES"))
    }

    @Test fun whisperLanguageFollowsScript() {
        assertEquals("en", Prompts.whisperLanguageFor("auto", "roman"))
        assertEquals("hi", Prompts.whisperLanguageFor("auto", "devanagari"))
        assertEquals("auto", Prompts.whisperLanguageFor("auto", "english"))
        assertEquals("en", Prompts.whisperLanguageFor("en", "devanagari"))
    }

    @Test fun leadingSpace() {
        assertEquals("", Prompts.leadingSpace(""))
        assertEquals("", Prompts.leadingSpace("hello "))
        assertEquals(" ", Prompts.leadingSpace("hello"))
        assertEquals(" ", Prompts.leadingSpace("done."))
        assertEquals("", Prompts.leadingSpace("("))
    }
}
