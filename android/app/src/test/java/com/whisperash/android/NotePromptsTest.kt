package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePromptsTest {
    @Test fun parsesTitleAndKeepsTheFormatAsSaid() {
        val out = "Title: Client call and next steps\nThe client call went well today.\n\nTomorrow:\n- [ ] Call Ravi\n- [x] Send the deck\n1. First point\n- milk"
        val (title, body) = NotePrompts.parse(out, "raw words")
        assertEquals("Client call and next steps", title)
        assertEquals("The client call went well today.\n\nTomorrow:\n- [ ] Call Ravi\n- [x] Send the deck\n1. First point\n- milk", body)
    }

    @Test fun survivesTagsThinkingBoldHeadingsAndMissingTitle() {
        val (title, body) = NotePrompts.parse("<think>x</think><note>## Kal\n**kal meeting hai**\n- report bhejna hai</note>", "kal meeting hai aur report bhejna hai yaar")
        assertEquals("kal meeting hai aur report bhejna…", title)
        assertEquals("Kal\nkal meeting hai\n- report bhejna hai", body)
        assertEquals("Shopping" to "- milk", NotePrompts.parse("```\n**Title:** Shopping\n- milk\n```", "x").let { it.first.trim('*', ' ') to it.second })
    }

    @Test fun emptyAnswerKeepsTheWordsAsSaid() {
        val (title, body) = NotePrompts.parse("", "remember to call the bank")
        assertEquals("remember to call the bank", title)
        assertEquals("remember to call the bank", body)
    }

    @Test fun appendContinuesListsAndStartsNewParagraphs() {
        assertEquals("- [ ] milk\n- [ ] eggs", NotePrompts.appendBody("- [ ] milk\n", "- [ ] eggs"))
        assertEquals("1. one\n2. two", NotePrompts.appendBody("1. one", "2. two"))
        assertEquals("Went well.\n\nAlso, call Ravi.", NotePrompts.appendBody("Went well.", "Also, call Ravi."))
        assertEquals("- milk\n\nThen a thought.", NotePrompts.appendBody("- milk", "Then a thought."))
        assertEquals("new", NotePrompts.appendBody("  ", "new"))
        assertEquals("- eggs", NotePrompts.parseAppend("Title: x\n- eggs", "eggs"))
        assertEquals("eggs bhi", NotePrompts.parseAppend("", "eggs bhi"))
    }

    @Test fun promptCarriesHinglishRuleDictionaryAndKeepStyle() {
        val p = NotePrompts.system(false, "auto", "roman", listOf("WhisperAsh"))
        assertTrue(p.contains("Title: <2 to 6 words>"))
        assertTrue(p.contains("\"- [ ] \""))
        assertTrue(p.contains("Never summarise"))
        assertFalse(p.contains("Action items:"))
        assertTrue(p.contains("Roman (Latin) letters"))
        assertTrue(p.endsWith("WhisperAsh"))
        val a = NotePrompts.system(true, "en", "roman", emptyList())
        assertTrue(a.contains("Write only the new content"))
        assertFalse(a.contains("Title: <2 to 6 words>"))
    }

    @Test fun blocksRenderParagraphsListsAndCheckboxes() {
        val body = "Went well today.\n\n\nTomorrow:\n- [ ] Call Ravi\n  - [x] sub task\n- milk\n* eggs\n2) second\n10. tenth\n"
        val b = NoteBlocks.parse(body)
        assertEquals(
            listOf(
                NoteBlock.Para(0, "Went well today."),
                NoteBlock.Gap(1),
                NoteBlock.Para(3, "Tomorrow:"),
                NoteBlock.Todo(4, 0, false, "Call Ravi"),
                NoteBlock.Todo(5, 1, true, "sub task"),
                NoteBlock.Bullet(6, 0, "milk"),
                NoteBlock.Bullet(7, 0, "eggs"),
                NoteBlock.Numbered(8, 0, "2", "second"),
                NoteBlock.Numbered(9, 0, "10", "tenth"),
            ),
            b,
        )
        assertEquals(1 to 2, NoteBlocks.counts(body))
    }

    @Test fun togglingATickChangesOnlyThatLine() {
        val body = "Shopping\n- [ ] milk\n- [X] eggs\n- bread"
        assertEquals("Shopping\n- [x] milk\n- [X] eggs\n- bread", NoteBlocks.toggle(body, 1))
        assertEquals("Shopping\n- [ ] milk\n- [ ] eggs\n- bread", NoteBlocks.toggle(body, 2))
        assertEquals(body, NoteBlocks.toggle(body, 3)) // not a checkbox
        assertEquals(body, NoteBlocks.toggle(body, 9))
        val back = NoteBlocks.toggle(NoteBlocks.toggle(body, 1), 1)
        assertEquals(body, back)
    }

    @Test fun shareTextShowsTicks() {
        val n = Note("1", "List", "Intro\n- [ ] a\n- [x] b\n- c\n1. d", "", null, false, 0, 0)
        assertEquals("List\n\nIntro\n☐ a\n☑ b\n• c\n1. d", NotePrompts.shareText(n))
    }
}
