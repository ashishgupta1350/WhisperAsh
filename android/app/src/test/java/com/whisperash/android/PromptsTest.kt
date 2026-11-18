package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    // Real APK run, PC recording 17 (17 Nov 2026): Qwen deleted "English" six times.
    private val raw17 = "Dekho, ek issue yeh a rahe hai ki, let's say ki, like I start with Hindi, like speaking with Hindi, the Hindi word. Toh it picks up the Hindi word and then, you know, it kind of, like even the English words is transcribing in Hindi, right? And just for example, you can check the past messages that I've sent to this app. Right, and let's say if I start with English and say Hinglish, ya Hindi words, toh it comes in Hinglish. Okay, so, I mean, like, but in typeless or like other whisper flow, what happens is ki let's say I'm talking in Hindi, main ek sentence Hindi mein bol raha hoon, and then like English comes up, so English words come up, toh English mein nazar aata hai. And then also, English shows up in that sentence."
    private val bad17 = "Dekho, ek issue yeh aa raha hai ki, let's say ki I start with Hindi, like speaking with Hindi words. Toh it picks up the Hindi word and then, even the words are transcribing in Hindi, right? And just for example, you can check the past that I've sent to this app. Right? And let's say if I start with Hinglish, or Hindi words, toh it comes in Hinglish. Okay. So, I mean, but in Typeless or like other Whisper flows, what happens is ki let's say I'm in Hindi, main ek Hindi mein bol raha hoon, and then like comes up, so words come up, toh mein nazar aata hai. And then also, shows up in that sentence."

    @Test fun retentionFlagsDeletedWords() {
        val r = Prompts.checkRetention(raw17, bad17)
        assertTrue(r.flagged)
        assertEquals("english", r.gone.first())
        assertTrue(Prompts.retentionRetry(raw17, r.gone).contains("(english, "))
    }

    @Test fun retentionAllowsNormalCleanup() {
        // fillers, a dropped repeat and Hinglish spelling fixes are fine
        assertFalse(Prompts.checkRetention(
            "So, like if I close the app and open it, are the settings forgotten by the app?",
            "So, if I close the app and open it, are the settings forgotten by the app?").flagged)
        assertFalse(Prompts.checkRetention(
            "I mean the Windows app. I'm talking about the Windows app.", "I mean the Windows app.").flagged)
        assertFalse(Prompts.checkRetention(
            "Okay, to yeh batao ki main kaise karke gurgaon jaa sakta hu, aur kitna time lagega mirko gurgaon jaane me, aur gurgaon se wapis ane me kitna time lagega? Main Rohingya sector 8 mein rehta hu, to mirko yeh pata lagana hai ki gurgaon jaa ke wapis ane me kitna time lagega?",
            "Okay, toh yeh batao ki main kaise karke Gurgaon jaa sakta hoon, aur kitna time lagega merko Gurgaon jaane mein, aur Gurgaon se wapas aane mein kitna time lagega? Main Rohini Sector 8 mein rehta hoon, toh merko yeh pata lagana hai ki Gurgaon jaake wapas aane mein kitna time lagega.").flagged)
    }

    @Test fun retentionFlagsBigCut() {
        val said = "We should first organize the customer data, then segment buyers by budget, location, profession and family size, and only after that design personas for each project."
        assertTrue(Prompts.checkRetention(said, "Organize the data, then design personas.").flagged)
    }

    @Test fun quietSpeechPassesTheGate() {
        assertTrue(LevelMeter.isSpeechWindow(0.0068, 0.025)) // PC recording 66, peak window
        assertFalse(LevelMeter.isSpeechWindow(0.0015, 0.01)) // room noise
        assertTrue(LevelMeter.hasSpeech(0.025, 100))
        assertFalse(LevelMeter.hasSpeech(0.015, 0))
        assertFalse(LevelMeter.hasSpeech(0.3, 0))
    }

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
        assertTrue(p.contains("addressed to \"Ash\""))
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
        // Addresses are fixed in code, so the (trimmed) cleanup prompt carries no address rule.
        assertFalse(Prompts.buildCleanupSystemPrompt("", "auto", "roman", emptyList(), null).contains("EMAIL AND WEB ADDRESSES"))
    }

    @Test fun trimmedPromptMatchesDesktop() {
        val p = Prompts.buildCleanupSystemPrompt("Ash", "auto", "roman", emptyList(), null)
        // Rule first, the 3 Hinglish examples (the phone one must stay), no WRONG example, 2 English examples.
        assertTrue(p.startsWith("LANGUAGE AND SCRIPT (most important rule"))
        assertTrue(p.contains("Output: Apna phone connect kar diya hai, toh you can use the phone."))
        assertTrue(p.contains("Output: Main Hindi mein bol raha hoon"))
        assertTrue(p.contains("Output: Bhai, kal ki meeting cancel ho gayi hai"))
        assertFalse(p.contains("WRONG"))
        assertEquals(2, Regex("\nInput: [a-z]").findAll(p.substringAfter("\nEXAMPLES:")).count())
        assertTrue(p.contains("addressed to \"Ash\""))
        // Desktop measured ~720 tokens for the fixed part (was ~1,110); keep Android in the same range.
        assertTrue("prompt grew to ${p.length} chars", p.length < 3300)
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
