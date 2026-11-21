package com.whisperash.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class NotesModelTest {
    private val day = 86_400_000L
    private fun note(id: String, title: String = id, body: String = "", edited: Long = 0, created: Long = 0, fav: Boolean = false,
                     folder: String? = null, binned: Long? = null, locked: Boolean = false) =
        Note(id, title, body, "", null, fav, created, edited, folderId = folder, binnedAt = binned, locked = locked, editedMs = edited)

    // ---------- views, search, sort ----------
    @Test fun viewsSplitAllFavouritesFoldersAndBin() {
        val notes = listOf(note("a", fav = true), note("b", folder = "f1"), note("c", binned = 5), note("d", fav = true, binned = 6))
        fun ids(v: NoteView) = NoteQuery.apply(notes, v, "", NoteSort.Title, emptyMap()).map { it.id }
        assertEquals(listOf("a", "b"), ids(NoteView.All))
        assertEquals(listOf("a"), ids(NoteView.Favourites))
        assertEquals(listOf("b"), ids(NoteView.InFolder("f1")))
        assertEquals(listOf("d", "c"), ids(NoteView.Bin))   // most recently deleted first
    }

    @Test fun searchCoversTitleBodyChecklistsAndFolderNamesButNotLockedText() {
        val notes = listOf(
            note("1", "Groceries", "- [ ] doodh\n- [x] anda"),
            note("2", "Trip", "Book tickets", folder = "f"),
            note("3", "Secret", "doodh bank pin", locked = true),
        )
        fun find(q: String) = NoteQuery.apply(notes, NoteView.All, q, NoteSort.Title, mapOf("f" to "Travel plans")).map { it.id }
        assertEquals(listOf("1"), find("DOODH"))       // checklist item; the locked note's text is not searched
        assertEquals(listOf("2"), find("travel"))      // folder name
        assertEquals(listOf("3"), find("secret"))      // locked notes still match on title
        assertEquals(3, find("  ").size)
    }

    @Test fun sortsByModifiedCreatedOrTitle() {
        val notes = listOf(note("x", "banana", edited = 10, created = 3), note("y", "Apple", edited = 30, created = 1), note("z", "cherry", edited = 20, created = 2))
        fun ids(s: NoteSort) = NoteQuery.apply(notes, NoteView.All, "", s, emptyMap()).map { it.id }
        assertEquals(listOf("y", "z", "x"), ids(NoteSort.Modified))
        assertEquals(listOf("x", "z", "y"), ids(NoteSort.Created))
        assertEquals(listOf("y", "x", "z"), ids(NoteSort.Title))
    }

    @Test fun recycleBinKeepsNotesFifteenDays() {
        val now = 100 * day
        assertFalse(NoteQuery.binExpired(note("a", binned = now - 14 * day), now))
        assertTrue(NoteQuery.binExpired(note("a", binned = now - 15 * day), now))
        assertFalse(NoteQuery.binExpired(note("a"), now))
        assertEquals(15, NoteQuery.daysLeftInBin(note("a", binned = now), now))
        assertEquals(1, NoteQuery.daysLeftInBin(note("a", binned = now - 14 * day - 1000), now))
    }

    // ---------- group by date ----------
    @Test fun dateGroupsReadLikeSamsungNotes() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = Calendar.getInstance(tz).apply { set(2026, Calendar.NOVEMBER, 20, 15, 0, 0) }
        val now = cal.timeInMillis
        fun at(y: Int, m: Int, d: Int, h: Int = 10) = Calendar.getInstance(tz).apply { set(y, m, d, h, 0, 0) }.timeInMillis
        assertEquals("Today", NoteQuery.groupLabel(at(2026, Calendar.NOVEMBER, 20, 0), now, tz))
        assertEquals("Yesterday", NoteQuery.groupLabel(at(2026, Calendar.NOVEMBER, 19, 23), now, tz))
        assertEquals("Previous 7 days", NoteQuery.groupLabel(at(2026, Calendar.NOVEMBER, 14), now, tz))
        assertEquals("Earlier this month", NoteQuery.groupLabel(at(2026, Calendar.NOVEMBER, 2), now, tz))
        assertEquals("October", NoteQuery.groupLabel(at(2026, Calendar.OCTOBER, 31), now, tz))
        assertEquals("December 2025", NoteQuery.groupLabel(at(2025, Calendar.DECEMBER, 5), now, tz))
        val groups = NoteQuery.groups(listOf(note("a", edited = now), note("b", edited = now - 1000), note("c", edited = at(2026, Calendar.OCTOBER, 1))), NoteSort.Modified, now, tz)
        assertEquals(listOf("Today" to listOf("a", "b"), "October" to listOf("c")), groups.map { it.first to it.second.map { n -> n.id } })
    }

    // ---------- folders ----------
    @Test fun folderTreeIndentsChildrenAndSurvivesBadData() {
        val fs = listOf(
            Folder("w", "Work", null, null), Folder("c", "Clients", "w", null), Folder("a", "Acme", "c", null),
            Folder("h", "Home", null, null), Folder("o", "Orphan", "missing", null),
            Folder("x", "Loop A", "y", null), Folder("y", "Loop B", "x", null),
        )
        val rows = FolderTree.rows(fs).map { it.folder.id to it.depth }
        assertEquals(listOf("h" to 0, "o" to 0, "w" to 0, "c" to 1, "a" to 2), rows)   // cycles never loop forever
        assertEquals(setOf("c", "a"), FolderTree.descendants(fs, "w").toSet())
        assertEquals("Work › Clients › Acme", FolderTree.path(fs, "a"))
        assertEquals("", FolderTree.path(fs, null))
    }

    // ---------- voice parts ----------
    @Test fun audioClipsRoundTrip() {
        val clips = listOf(AudioClip("/data/x/note-audio/a|b.m4a", 4200, 1000), AudioClip("/data/x/b.m4a", 0, 2000))
        assertEquals(clips, AudioClip.decode(AudioClip.encode(clips)))
        assertEquals(null, AudioClip.encode(emptyList()))
        assertTrue(AudioClip.decode(null).isEmpty())
        assertTrue(AudioClip.decode("garbage").isEmpty())
    }

    // ---------- History recordings: last 15 kept ----------
    @Test fun onlyTheNewestFifteenRecordingsAreKept() {
        val rows = (1..20).map { "h$it" to it * 1000L }.shuffled()
        val gone = RecordingStore.toPrune(rows)
        assertEquals((1..5).map { "h$it" }.toSet(), gone.toSet())
        assertTrue(RecordingStore.toPrune(rows.take(15)).isEmpty())
        assertEquals(15, RecordingStore.KEEP)
    }

    // ---------- formatting bar ----------
    @Test fun formatBarTurnsLinesIntoListsAndBack() {
        val t = "milk\neggs\nbread"
        val check = NoteFormat.toggleLines(t, 0, t.length, NoteFormat.Kind.Check)
        assertEquals("- [ ] milk\n- [ ] eggs\n- [ ] bread", check.text)
        assertEquals(t, NoteFormat.toggleLines(check.text, 0, check.text.length, NoteFormat.Kind.Check).text)
        assertEquals("- milk\n- eggs\n- bread", NoteFormat.toggleLines(check.text, 0, check.text.length, NoteFormat.Kind.Bullet).text)
        assertEquals("1. milk\n2. eggs\n3. bread", NoteFormat.toggleLines(t, 0, t.length, NoteFormat.Kind.Number).text)
        // only the line with the cursor; numbering continues from the line above
        val n = NoteFormat.toggleLines("1. one\ntwo", 9, 9, NoteFormat.Kind.Number)
        assertEquals("1. one\n2. two", n.text)
        assertEquals(12, n.selStart)
        // ticked boxes count as checklist lines
        assertEquals("- milk", NoteFormat.toggleLines("- [x] milk", 3, 3, NoteFormat.Kind.Bullet).text)
    }

    @Test fun boldWrapsAndUnwraps() {
        val b = NoteFormat.toggleBold("say hello now", 4, 9)
        assertEquals("say **hello** now", b.text)
        assertEquals(6 to 11, b.selStart to b.selEnd)
        assertEquals("say hello now", NoteFormat.toggleBold(b.text, b.selStart, b.selEnd).text)
        assertEquals("ab****", NoteFormat.toggleBold("ab", 2, 2).text)
    }

    @Test fun enterContinuesOrEndsAList() {
        fun enter(old: String, at: Int) = NoteFormat.continueList(old, old.substring(0, at) + "\n" + old.substring(at), at + 1)
        assertEquals("- [ ] milk\n- [ ] ", enter("- [ ] milk", 10)!!.text)
        assertEquals("- [x] milk\n- [ ] ", enter("- [x] milk", 10)!!.text)
        assertEquals("1. one\n2. ", enter("1. one", 6)!!.text)
        assertEquals("  - a\n  - ", enter("  - a", 5)!!.text)
        assertEquals("- milk\n", enter("- milk\n- ", 9)!!.text)          // Enter on an empty item ends the list
        assertEquals(null, enter("plain", 5))
        assertEquals(null, NoteFormat.continueList("ab", "abc", 3))
    }

    // ---------- editor undo / redo ----------
    @Test fun undoRedoMergesQuickTyping() {
        val h = EditHistory("" to "", mergeMs = 1000)
        h.push("" to "h", now = 10_000)
        h.push("" to "he", now = 10_200)            // merged with the step before
        h.push("" to "hey", now = 12_000)
        assertEquals("" to "he", h.undo())
        assertEquals("" to "", h.undo())
        assertFalse(h.canUndo)
        assertEquals("" to "he", h.redo())
        h.push("T" to "he", now = 20_000)           // a new edit drops the redo branch
        assertFalse(h.canRedo)
        h.push("T" to "AI text", now = 20_100, merge = false)   // an AI result is always its own step
        assertEquals("T" to "he", h.undo())
    }

    // ---------- AI summary block ----------
    @Test fun summaryGoesOnTopAndIsReplacedNotStacked() {
        val once = NotePrompts.withSummary("Body text\n- a", "Short.")
        assertEquals("AI summary: Short.\n\nBody text\n- a", once)
        assertEquals("AI summary: Newer.\n\nBody text\n- a", NotePrompts.withSummary(once, "Newer."))
        assertEquals("Body text\n- a", NotePrompts.withoutSummary(once))
        assertEquals("AI summary: Only.", NotePrompts.withSummary("", "Only."))
    }
}
