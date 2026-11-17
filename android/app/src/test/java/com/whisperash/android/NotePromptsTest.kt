package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePromptsTest {
    @Test fun parsesTitleAndBullets() {
        val out = "Title: Groceries and Friday meeting\n- Buy milk, eggs and 2 kg rice\n- Meeting moved to Friday 3 PM\nAction items:\n- [ ] Send the report before Friday"
        val (title, body) = NotePrompts.parse(out, "raw words")
        assertEquals("Groceries and Friday meeting", title)
        assertTrue(body.startsWith("- Buy milk"))
        assertTrue(body.contains("- [ ] Send the report before Friday"))
        assertTrue(!body.contains("Title:"))
    }

    @Test fun survivesTagsThinkingAndMissingTitle() {
        val (title, body) = NotePrompts.parse("<think>x</think><note>- kal meeting hai\n- report bhejna hai</note>", "kal meeting hai aur report bhejna hai yaar")
        assertEquals("kal meeting hai aur report bhejna…", title)
        assertEquals("- kal meeting hai\n- report bhejna hai", body)
    }

    @Test fun emptyAnswerKeepsTheWords() {
        val (title, body) = NotePrompts.parse("", "remember to call the bank")
        assertEquals("remember to call the bank", title)
        assertEquals("- remember to call the bank", body)
    }

    @Test fun promptCarriesHinglishRuleAndDictionary() {
        val p = NotePrompts.system(false, "auto", "roman", listOf("WhisperAsh"))
        assertTrue(p.contains("Title:"))
        assertTrue(p.contains("Roman (Latin) letters"))
        assertTrue(p.endsWith("WhisperAsh"))
        assertTrue(NotePrompts.system(true, "en", "roman", emptyList()).contains("Keep every existing bullet"))
    }
}
