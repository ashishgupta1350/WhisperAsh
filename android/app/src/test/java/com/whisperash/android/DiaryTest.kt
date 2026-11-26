package com.whisperash.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DiaryTest {
    @Test fun diaryPromptAndSummaryUsePersonalVoice() {
        var prompt = ""
        val engine = object : AudioEngine {
            override val name = "gemini"
            override val needsRomanize = false
            override fun transcribe(part: MeetingPart, input: String): List<String> {
                prompt = input
                return listOf("[00:00] Kal main park gaya")
            }
        }
        val text = object : TextEngine {
            override val name = "gemini"
            override val maxWords = 6000
            override fun complete(system: String, user: String): String {
                assertTrue(system.contains("first person"))
                return "Title: Walk in the park\nSummary:\nI went to the park."
            }
        }
        val chain = MeetingChain(listOf(engine), listOf(text), kind = RecordingKind.DIARY)
        val result = chain.transcribePart(MeetingPart(0, 1, File("fixture.m4a"), "audio/mp4", 0, 1000, true), null)
        assertTrue(prompt.contains("Do not add speaker labels"))
        assertEquals("Walk in the park", chain.summarize(result.lines.single())?.title)
        assertEquals("Kal main park gaya", DiaryText.themeFromTranscript(result.lines.single()))
        assertTrue(DiaryText.title(1, "Walk in the park").endsWith(" · Walk in the park"))
        assertNotEquals(RecordingKind.DIARY.wireName, RecordingKind.MEETING.wireName)
    }
}
