@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.whisperash.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperash.android.ui.AiLabel
import com.whisperash.android.ui.AudioChip
import com.whisperash.android.ui.Btn
import com.whisperash.android.ui.BtnKind
import com.whisperash.android.ui.Chip
import com.whisperash.android.ui.G
import com.whisperash.android.ui.GlassCard
import com.whisperash.android.ui.GlassField
import com.whisperash.android.ui.GlassList
import com.whisperash.android.ui.GlassSheet
import com.whisperash.android.ui.Gutter
import com.whisperash.android.ui.Ic
import com.whisperash.android.ui.LargeTitle
import com.whisperash.android.ui.ListRow
import com.whisperash.android.ui.Meta
import com.whisperash.android.ui.NoteBox
import com.whisperash.android.ui.RoundGlass
import com.whisperash.android.ui.SearchBox
import com.whisperash.android.ui.SheetRow
import com.whisperash.android.ui.Spinner
import com.whisperash.android.ui.Subtitle
import com.whisperash.android.ui.ToggleRow
import com.whisperash.android.ui.Tone
import com.whisperash.android.ui.glass
import com.whisperash.android.ui.reducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Folder colours: soft tints that sit well on the glass (key → colour). */
val FolderColors = listOf(
    "peach" to Color(0xFFF2B99B), "blue" to Color(0xFFA9C8F0), "green" to Color(0xFFAFD9B4), "teal" to Color(0xFF9AD3CA),
    "lilac" to Color(0xFFC8C0F0), "yellow" to Color(0xFFEFD38C), "pink" to Color(0xFFF0B5C8), "grey" to Color(0xFFBAC3CB),
)
fun folderColor(key: String?): Color = FolderColors.firstOrNull { it.first == key }?.second ?: Color(0xFFBAC3CB)

private fun viewTitle(view: NoteView, folders: List<Folder>) = when (view) {
    NoteView.All -> "All notes"
    NoteView.Favourites -> "Favourites"
    NoteView.Bin -> "Recycle bin"
    is NoteView.InFolder -> folders.firstOrNull { it.id == view.id }?.name ?: "Folder"
}

/**
 * The Notes tab, modelled on Samsung Notes: side menu with All notes / Favourites / folders / Recycle bin,
 * Grid / List / Simple list, sort and date groups, multi-select, lockable notes, voice parts kept in the note.
 * Notes are written the way they were said (paragraphs, lists, checklists).
 * [start] = "speak" / "type" / "checklist" / "list" when Home or the widget opened the tab for that.
 */
@Composable
fun NotesTab(
    app: WhisperAshApp,
    version: Int,
    hasMic: () -> Boolean,
    askMic: () -> Unit,
    start: String? = null,
    onStartHandled: () -> Unit = {},
    unlock: (String, () -> Unit) -> Unit = { _, ok -> ok() },
    canLock: () -> Boolean = { false },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var view by remember { mutableStateOf<NoteView>(NoteView.All) }
    var openId by remember { mutableStateOf<String?>(null) }
    var editFirst by remember { mutableStateOf(false) }
    var recordInto by remember { mutableStateOf<String?>(null) }   // "new" or a note id
    var unlocked by remember { mutableStateOf(setOf<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }

    fun ready(): Boolean {
        if (!hasMic()) { askMic(); return false }
        if (!app.canDictate()) { message = if (app.useAccount()) "Sign in under Settings → Account first." else "Add your Groq key in Settings first."; return false }
        return true
    }
    fun newNote(body: String) {
        val now = System.currentTimeMillis()
        val n = app.db.saveNote(Note(UUID.randomUUID().toString(), "", body, "", null, false, now, now, folderId = (view as? NoteView.InFolder)?.id))
        editFirst = true; openId = n.id; refresh++
    }
    fun startVoice(into: String?) { message = ""; if (ready()) recordInto = into ?: "new" }
    fun open(n: Note) {
        if (n.locked && n.id !in unlocked) unlock("Unlock “${n.title.ifBlank { "note" }}”") { unlocked = unlocked + n.id; editFirst = false; openId = n.id }
        else { editFirst = false; openId = n.id }
    }

    LaunchedEffect(start) {
        when (start) {
            null -> return@LaunchedEffect
            "speak" -> startVoice(null)
            "type" -> newNote("")
            "checklist" -> newNote("- [ ] ")
        }
        onStartHandled()
    }

    val into = recordInto
    val id = openId
    when {
        into != null -> RecordingPanel(app, appending = into != "new",
            onCancel = { recordInto = null },
            onDone = { rec ->
                recordInto = null
                val noteId = if (into == "new") {
                    val now = System.currentTimeMillis()
                    app.db.saveNote(Note(UUID.randomUUID().toString(), "", "", "", null, false, now, now, folderId = (view as? NoteView.InFolder)?.id)).id
                } else into
                editFirst = false; openId = noteId
                NoteJobs.start(noteId, if (into == "new") "Writing your note…" else "Adding what you said…")
                app.scope.launch {
                    val err = try { app.notes.addVoice(noteId, rec); null } catch (e: Exception) {
                        if (e is NoSpeechException) "Didn't catch any speech. The recording is not kept." else e.message ?: "Something went wrong"
                    }
                    NoteJobs.done(noteId, err)
                    app.sync.requestSync(SyncClient.Reason.CHANGE)
                    app.sync.dataVersion.value++
                }
            },
            onError = { message = it; recordInto = null })
        id != null -> NoteEditor(app, context, id, editFirst, version + refresh,
            onBack = { openId = null; refresh++ },
            onAddVoice = { startVoice(id) },
            unlock = unlock, canLock = canLock)
        else -> NotesHome(app, version + refresh, view, message,
            onView = { view = it },
            onOpen = { open(it) },
            onSpeak = { startVoice(null) },
            onNew = { kind -> if (kind == "voice") startVoice(null) else newNote(if (kind == "checklist") "- [ ] " else "") },
            unlock = unlock, canLock = canLock,
            onChanged = { refresh++; app.sync.requestSync(SyncClient.Reason.CHANGE) })
    }
}

// ======================================================================================================
// List screen
// ======================================================================================================

@Composable
private fun NotesHome(
    app: WhisperAshApp, version: Int, view: NoteView, message: String,
    onView: (NoteView) -> Unit, onOpen: (Note) -> Unit, onSpeak: () -> Unit, onNew: (String) -> Unit,
    unlock: (String, () -> Unit) -> Unit, canLock: () -> Boolean, onChanged: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val s = app.settings
    var query by remember { mutableStateOf("") }
    var layout by remember { mutableStateOf(runCatching { NoteLayout.valueOf(s.notesLayout) }.getOrDefault(NoteLayout.Grid)) }
    var sort by remember { mutableStateOf(runCatching { NoteSort.valueOf(s.notesSort) }.getOrDefault(NoteSort.Modified)) }
    var grouped by remember { mutableStateOf(s.notesGroupByDate) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var drawer by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }    // "view", "move", "emptyBin", "createMenu"
    var r by remember { mutableIntStateOf(0) }
    val all = remember(version, r) { app.db.allNotes() }
    val folders = remember(version, r) { app.db.folders() }
    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    val notes = remember(all, view, query, sort, folderNames) { NoteQuery.apply(all, view, query, sort, folderNames) }
    val now = System.currentTimeMillis()
    val sections: List<Pair<String?, List<Note>>> = remember(notes, grouped, sort) {
        if (grouped && view != NoteView.Bin) NoteQuery.groups(notes, sort, now).map { it.first to it.second } else listOf(null to notes)
    }
    fun changed() { r++; onChanged() }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    BackHandler(enabled = !selecting && view != NoteView.All) { onView(NoteView.All) }

    fun tapNote(n: Note) { if (selecting) selected = if (n.id in selected) selected - n.id else selected + n.id else onOpen(n) }
    fun longNote(n: Note) { selected = selected + n.id }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar: menu, title, view options (or the selection bar) ----
            if (selecting) SelectionBar(app, context, view, selected.mapNotNull { id -> all.firstOrNull { it.id == id } },
                onClear = { selected = emptySet() }, onMove = { sheet = "move" }, unlock = unlock, canLock = canLock,
                onDone = { selected = emptySet(); changed() })
            else Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundGlass(Ic.menu, "Folders and views", { drawer = true })
                Spacer(Modifier.weight(1f))
                RoundGlass(Ic.grid, "View options", { sheet = "view" })
            }
            val content: @Composable (Modifier) -> Unit = { mod ->
                NotesContent(app, mod, view, notes, sections, layout, sort, folders, selected, query, message,
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LargeTitle(viewTitle(view, folders))
                            Subtitle(
                                if (view == NoteView.Bin) "Deleted notes are kept ${NoteQuery.BIN_DAYS} days, then removed for good."
                                else counted(notes.size, "note") + if (view is NoteView.InFolder) " · " + FolderTree.path(folders, view.id) else ""
                            )
                            SearchBox(query, { query = it }, "Search notes and folders")
                            if (view is NoteView.InFolder) {
                                val subs = folders.filter { it.parentId == view.id }
                                if (subs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    subs.forEach { f -> FolderPill(f, all.count { it.folderId == f.id && it.binnedAt == null }) { onView(NoteView.InFolder(f.id)) } }
                                }
                            }
                            if (view == NoteView.Bin && notes.isNotEmpty()) Btn("Empty recycle bin", { sheet = "emptyBin" }, kind = BtnKind.Danger, icon = Ic.trash)
                            if (message.isNotEmpty()) NoteBox(message, Tone.Bad)
                        }
                    },
                    onTap = ::tapNote, onLong = ::longNote)
            }
            content(Modifier.weight(1f))
            // ---- create: rust "Speak a note" (our main action) + glass "+" (tap: type; hold: more ways) ----
            if (!selecting && view != NoteView.Bin) Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp, bottom = bottomClearance() - 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn("Speak a note", onSpeak, Modifier.weight(1f), BtnKind.Primary, Ic.mic, big = true)
                Box(
                    Modifier.size(54.dp).glass(CircleShape, SolidColor(G.GlassStrong))
                        .combinedClickable(onClick = { onNew("type") }, onLongClick = { sheet = "createMenu" }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Ic.plus, "New note (hold for more)", Modifier.size(26.dp), tint = G.Ink) }
            } else Spacer(Modifier.height(bottomClearance() - 40.dp))
        }

        // ---- side menu ----
        val still = reducedMotion()
        AnimatedVisibility(drawer, enter = fadeIn(tween(if (still) 0 else 200)), exit = fadeOut(tween(if (still) 0 else 200))) {
            Box(Modifier.fillMaxSize().background(Color(0x4D141E28)).clickable(remember { MutableInteractionSource() }, null) { drawer = false })
        }
        AnimatedVisibility(drawer,
            enter = slideInHorizontally(tween(if (still) 0 else 300, easing = G.Ease)) { -it },
            exit = slideOutHorizontally(tween(if (still) 0 else 250, easing = G.Ease)) { -it }) {
            NotesDrawer(app, all, folders, view,
                onPick = { onView(it); drawer = false; selected = emptySet() },
                onChanged = { changed() }, onClose = { drawer = false })
        }

        when (sheet) {
            "view" -> GlassSheet("View", { sheet = null }) {
                com.whisperash.android.ui.Eyebrow("Layout")
                NoteLayout.entries.forEach { l -> SheetRow(l.label, selected = layout == l) { layout = l; s.notesLayout = l.name } }
                Spacer(Modifier.height(8.dp))
                com.whisperash.android.ui.Eyebrow("Sort by")
                NoteSort.entries.forEach { o -> SheetRow(o.label, selected = sort == o) { sort = o; s.notesSort = o.name } }
                Spacer(Modifier.height(4.dp))
                ToggleRow("Group by date", grouped, "Today, Yesterday, Previous 7 days…", divider = false) { grouped = it; s.notesGroupByDate = it }
            }
            "move" -> MoveSheet(app, folders, null, onDismiss = { sheet = null }) { folderId ->
                selected.forEach { id -> app.db.note(id)?.let { app.db.saveNote(it.copy(folderId = folderId), edited = false) } }
                selected = emptySet(); sheet = null; changed()
            }
            "emptyBin" -> GlassSheet("Empty the recycle bin?", { sheet = null }) {
                Meta("${counted(notes.size, "note")} and their recordings are removed for good, here and on your other devices.")
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { sheet = null }, kind = BtnKind.Glass)
                    Btn("Empty", { notes.forEach { app.db.deleteNote(it.id) }; sheet = null; changed() }, kind = BtnKind.Danger, icon = Ic.trash)
                }
            }
            "createMenu" -> GlassSheet("New note", { sheet = null }) {
                SheetRow("Voice note", Ic.mic, Tone.Accent, sub = "Speak; it's written down the way you said it") { sheet = null; onNew("voice") }
                SheetRow("Checklist", Ic.listCheck, Tone.Info, sub = "Boxes you can tick") { sheet = null; onNew("checklist") }
                SheetRow("Type a note", Ic.pen, Tone.Neutral) { sheet = null; onNew("type") }
            }
        }
    }
}

@Composable
private fun FolderPill(f: Folder, count: Int, onClick: () -> Unit) {
    Row(Modifier.glass(G.Pill, SolidColor(G.GlassStrong), shadow = false).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(folderColor(f.color)))
        Spacer(Modifier.width(8.dp))
        Text("${f.name} · $count", style = G.Sub.copy(color = G.Ink, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun NotesContent(
    app: WhisperAshApp, modifier: Modifier, view: NoteView, notes: List<Note>, sections: List<Pair<String?, List<Note>>>,
    layout: NoteLayout, sort: NoteSort, folders: List<Folder>, selected: Set<String>, query: String, message: String,
    header: @Composable () -> Unit, onTap: (Note) -> Unit, onLong: (Note) -> Unit,
) {
    val cols = if (layout == NoteLayout.Grid) 2 else 1
    val now = System.currentTimeMillis()
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(cols),
        modifier = modifier.padding(horizontal = Gutter - 4.dp),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = if (layout == NoteLayout.Simple) 0.dp else 10.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) { header() }
        if (notes.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
            GlassCard {
                Text(when {
                    query.isNotBlank() -> "Nothing matches “$query”"
                    view == NoteView.Bin -> "The recycle bin is empty"
                    view == NoteView.Favourites -> "No favourites yet"
                    else -> "No notes here yet"
                }, style = G.ItemTitle)
                Meta(when {
                    query.isNotBlank() -> "Search looks in titles, notes, checklists and folder names."
                    view == NoteView.Favourites -> "Tap ☆ in a note to keep it here."
                    view == NoteView.Bin -> "Deleted notes wait here for ${NoteQuery.BIN_DAYS} days."
                    else -> "Tap Speak a note and talk: a thought becomes a paragraph, a list becomes a list, and to-dos become boxes you can tick."
                }, Modifier.padding(top = 4.dp))
            }
        }
        for ((label, list) in sections) {
            if (label != null && list.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                Text(label, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), style = G.Sub.copy(fontWeight = FontWeight.Bold, color = G.Ink2))
            }
            items(list, key = { it.id }) { n ->
                val folder = folders.firstOrNull { it.id == n.folderId }
                val sel = n.id in selected
                when (layout) {
                    NoteLayout.Simple -> SimpleRow(n, sort, folder, sel, view == NoteView.Bin, now, { onTap(n) }, { onLong(n) })
                    else -> NoteCard(n, sort, folder, sel, view == NoteView.Bin, now, if (layout == NoteLayout.Grid) 7 else 4, { onTap(n) }, { onLong(n) })
                }
            }
        }
    }
}

private fun dateText(ms: Long, now: Long): String {
    val d = now - ms
    return if (d in 0 until 86_400_000L && DateFormat.getDateInstance().format(Date(ms)) == DateFormat.getDateInstance().format(Date(now)))
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
}

@Composable
private fun NoteCard(n: Note, sort: NoteSort, folder: Folder?, selected: Boolean, inBin: Boolean, now: Long, maxBlocks: Int, onTap: () -> Unit, onLong: () -> Unit) {
    val (done, total) = remember(n.body) { NoteBlocks.counts(n.body) }
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().glass(shape, if (selected) SolidColor(G.AccentSel) else SolidColor(G.Glass))
            .combinedClickable(onClick = onTap, onLongClick = onLong).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (folder != null) { Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(folderColor(folder.color))); Spacer(Modifier.width(6.dp)) }
            Text(n.title.ifBlank { "Untitled" }, Modifier.weight(1f), style = G.ItemTitle.copy(color = if (n.title.isBlank()) G.Ink3 else G.Ink),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Ic.check, "Selected", Modifier.padding(start = 4.dp).size(18.dp), tint = G.Accent)
        }
        Spacer(Modifier.height(6.dp))
        if (n.locked) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.lock, null, Modifier.size(15.dp), tint = G.Ink3); Spacer(Modifier.width(6.dp)); Meta("Locked")
        } else NoteBodyView(n.body, fontSize = G.Sub.fontSize, maxBlocks = maxBlocks)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Meta(if (inBin) counted(NoteQuery.daysLeftInBin(n, now), "day") + " left" else dateText(NoteQuery.dateOf(n, sort), now), Modifier.weight(1f), maxLines = 1)
            if (n.audio.isNotEmpty()) Icon(Ic.mic, "Has recordings", Modifier.padding(start = 4.dp).size(14.dp), tint = G.Ai)
            if (n.locked) Icon(Ic.lock, "Locked", Modifier.padding(start = 4.dp).size(14.dp), tint = G.Ink3)
            if (n.favourite) Icon(Ic.starFilled, "Favourite", Modifier.padding(start = 4.dp).size(14.dp), tint = G.Accent)
            if (total > 0 && !n.locked) { Spacer(Modifier.width(6.dp)); Chip("$done/$total", if (done == total) Tone.Ok else Tone.Neutral) }
        }
    }
}

@Composable
private fun SimpleRow(n: Note, sort: NoteSort, folder: Folder?, selected: Boolean, inBin: Boolean, now: Long, onTap: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) G.AccentSel else Color(0x80FFFFFF))
        .combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (folder != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(folderColor(folder.color))); Spacer(Modifier.width(8.dp)) }
            Text(n.title.ifBlank { "Untitled" }, Modifier.weight(1f), style = G.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (n.audio.isNotEmpty()) Icon(Ic.mic, null, Modifier.padding(start = 6.dp).size(14.dp), tint = G.Ai)
            if (n.locked) Icon(Ic.lock, null, Modifier.padding(start = 6.dp).size(14.dp), tint = G.Ink3)
            if (n.favourite) Icon(Ic.starFilled, null, Modifier.padding(start = 6.dp).size(14.dp), tint = G.Accent)
            Spacer(Modifier.width(8.dp))
            Meta(if (inBin) "${NoteQuery.daysLeftInBin(n, now)} d left" else dateText(NoteQuery.dateOf(n, sort), now))
        }
    }
    Spacer(Modifier.height(1.dp))
}

@Composable
private fun SelectionBar(
    app: WhisperAshApp, context: Context, view: NoteView, notes: List<Note>, onClear: () -> Unit, onMove: () -> Unit,
    unlock: (String, () -> Unit) -> Unit, canLock: () -> Boolean, onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp)
            .glass(RoundedCornerShape(24.dp), SolidColor(Color(0xF7FFFFFF))).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundGlass(Ic.close, "Cancel selection", onClear)
            Text("${notes.size} selected", Modifier.weight(1f).padding(horizontal = 8.dp),
                style = G.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            if (view == NoteView.Bin) {
                RoundGlass(Ic.undo, "Restore", { notes.forEach { app.db.restoreNote(it.id) }; onDone() })
                RoundGlass(Ic.trash, "Delete for good", { notes.forEach { app.db.deleteNote(it.id) }; onDone() }, tint = G.Bad)
                return@Row
            }
            RoundGlass(Ic.folderMove, "Move to folder", onMove)
            val allFav = notes.all { it.favourite }
            RoundGlass(if (allFav) Ic.starFilled else Ic.star, if (allFav) "Remove from favourites" else "Add to favourites", {
                notes.forEach { app.db.saveNote(it.copy(pinned = !allFav), edited = false) }; onDone()
            }, tint = G.Accent)
            val allLocked = notes.all { it.locked }
            RoundGlass(if (allLocked) Ic.unlock else Ic.lock, if (allLocked) "Unlock" else "Lock", {
                if (allLocked) unlock("Unlock ${counted(notes.size, "note")}") { notes.forEach { app.db.saveNote(it.copy(locked = false), edited = false) }; onDone() }
                else if (!canLock()) Toast.makeText(context, "Set a screen lock (PIN, pattern or fingerprint) in the phone's settings first.", Toast.LENGTH_LONG).show()
                else { notes.forEach { app.db.saveNote(it.copy(locked = true), edited = false) }; onDone() }
            })
            RoundGlass(Ic.share, "Share", {
                val text = notes.filterNot { it.locked }.joinToString("\n\n———\n\n") { NotePrompts.shareText(it) }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share notes")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            RoundGlass(Ic.trash, "Delete", { notes.forEach { app.db.binNote(it.id) }; onDone() }, tint = G.Bad)
        }
    }
}

// ---------- side menu ----------
@Composable
private fun NotesDrawer(app: WhisperAshApp, all: List<Note>, folders: List<Folder>, view: NoteView, onPick: (NoteView) -> Unit, onChanged: () -> Unit, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<Folder?>(null) }      // folder being renamed / coloured
    var creatingIn by remember { mutableStateOf<String?>(null) }   // parent id, "" = top level
    val live = all.filter { it.binnedAt == null }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().width(316.dp).statusBarsPadding().padding(start = 10.dp, top = 6.dp, bottom = bottomClearance() - 30.dp)
                .glass(RoundedCornerShape(28.dp), SolidColor(Color(0xF2FFFFFF))).verticalScroll(rememberScrollState()).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Notes", Modifier.weight(1f), style = G.SectionTitle)
                RoundGlass(Ic.close, "Close", onClose)
            }
            Spacer(Modifier.height(8.dp))
            SheetRow("All notes", Ic.note, Tone.Accent, view == NoteView.All, "${live.size}") { onPick(NoteView.All) }
            SheetRow("Favourites", Ic.star, Tone.Wait, view == NoteView.Favourites, "${live.count { it.favourite }}") { onPick(NoteView.Favourites) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.whisperash.android.ui.Eyebrow("Folders")
                Spacer(Modifier.weight(1f))
                Btn("New folder", { creatingIn = "" }, kind = BtnKind.Plain, icon = Ic.folderPlus)
            }
            if (folders.isEmpty()) Meta("Group notes into folders (and folders inside folders).", Modifier.padding(start = 6.dp, bottom = 6.dp))
            FolderTree.rows(folders).forEach { row ->
                val f = row.folder
                Row(
                    Modifier.fillMaxWidth().padding(start = (row.depth * 16).dp).clip(RoundedCornerShape(14.dp))
                        .background(if (view == NoteView.InFolder(f.id)) G.AccentSel else Color.Transparent)
                        .combinedClickable(onClick = { onPick(NoteView.InFolder(f.id)) }, onLongClick = { editing = f })
                        .heightIn(min = 48.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.folder, null, Modifier.size(20.dp), tint = folderColor(f.color).copy(red = folderColor(f.color).red * .7f, green = folderColor(f.color).green * .7f, blue = folderColor(f.color).blue * .7f))
                    Spacer(Modifier.width(10.dp))
                    Text(f.name, Modifier.weight(1f), style = G.ItemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Meta("${live.count { it.folderId == f.id }}")
                    Icon(Ic.more, "Folder options", Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).clickable { editing = f }.padding(8.dp), tint = G.Ink3)
                }
            }
            Spacer(Modifier.height(10.dp))
            SheetRow("Recycle bin", Ic.trash, Tone.Bad, view == NoteView.Bin, "${all.count { it.binnedAt != null }} · kept ${NoteQuery.BIN_DAYS} days") { onPick(NoteView.Bin) }
        }
        val e = editing
        if (e != null) FolderSheet(app, e, folders, onDismiss = { editing = null }, onNewSub = { editing = null; creatingIn = e.id },
            onChanged = { editing = null; onChanged() }, onDeleted = { editing = null; if (view == NoteView.InFolder(e.id)) onPick(NoteView.All); onChanged() })
        val c = creatingIn
        if (c != null) NameSheet("New folder", "", onDismiss = { creatingIn = null }) { name ->
            app.db.saveFolder(Folder(UUID.randomUUID().toString(), name, c.ifEmpty { null }, FolderColors[folders.size % FolderColors.size].first))
            creatingIn = null; onChanged()
        }
    }
}

@Composable
private fun NameSheet(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    GlassSheet(title, onDismiss) {
        GlassField(name, { name = it.take(60) }, placeholder = "Folder name")
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Cancel", onDismiss, kind = BtnKind.Glass)
            Btn("Save", { onSave(name.trim()) }, kind = BtnKind.Primary, enabled = name.isNotBlank())
        }
    }
}

@Composable
private fun FolderSheet(app: WhisperAshApp, f: Folder, folders: List<Folder>, onDismiss: () -> Unit, onNewSub: () -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    if (renaming) { NameSheet("Rename folder", f.name, { renaming = false }) { app.db.saveFolder(f.copy(name = it)); onChanged() }; return }
    GlassSheet(f.name, onDismiss) {
        com.whisperash.android.ui.Eyebrow("Colour")
        Row(Modifier.padding(vertical = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FolderColors.forEach { (key, c) ->
                Box(Modifier.size(40.dp).clip(CircleShape).background(c).clickable { app.db.saveFolder(f.copy(color = key)); onChanged() },
                    contentAlignment = Alignment.Center) { if (f.color == key) Icon(Ic.check, key, Modifier.size(18.dp), tint = G.Ink) }
            }
        }
        SheetRow("Rename", Ic.pen) { renaming = true }
        SheetRow("New folder inside", Ic.folderPlus, Tone.Info) { onNewSub() }
        if (!confirm) SheetRow("Delete folder", Ic.trash, Tone.Bad, sub = "Its notes stay, in All notes") { confirm = true }
        else {
            val subs = FolderTree.descendants(folders, f.id).size
            NoteBox("Delete “${f.name}”" + (if (subs > 0) " and its ${counted(subs, "folder")}" else "") + "? The notes are kept and move to All notes.", Tone.Wait)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Cancel", { confirm = false }, kind = BtnKind.Glass)
                Btn("Delete folder", { app.db.deleteFolder(f.id); onDeleted() }, kind = BtnKind.Danger, icon = Ic.trash)
            }
        }
    }
}

@Composable
private fun MoveSheet(app: WhisperAshApp, folders: List<Folder>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameSheet("New folder", "", { creating = false }) { name ->
            val f = app.db.saveFolder(Folder(UUID.randomUUID().toString(), name, null, FolderColors[folders.size % FolderColors.size].first))
            onPick(f.id)
        }
        return
    }
    GlassSheet("Move to folder", onDismiss) {
        SheetRow("No folder (All notes)", Ic.note, Tone.Neutral, current == null) { onPick(null) }
        FolderTree.rows(folders).forEach { row ->
            Box(Modifier.padding(start = (row.depth * 16).dp)) {
                SheetRow(row.folder.name, Ic.folder, Tone.Info, current == row.folder.id) { onPick(row.folder.id) }
            }
        }
        SheetRow("New folder", Ic.folderPlus, Tone.Ai) { creating = true }
    }
}

// ======================================================================================================
// Rendering a note body
// ======================================================================================================

/** **bold** spans as real bold text (the markers hidden). */
fun richText(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val re = Regex("""\*\*(.+?)\*\*""")
    for (m in re.findAll(text)) {
        append(text.substring(i, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        i = m.range.last + 1
    }
    append(text.substring(i))
}

/** Paragraphs, bullets, numbers and checkboxes; the AI summary paragraph in teal. [onToggle] makes boxes tappable. */
@Composable
fun NoteBodyView(
    body: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 17.sp,
    maxBlocks: Int = Int.MAX_VALUE,
    onToggle: ((Int) -> Unit)? = null,
) {
    val blocks = remember(body) { NoteBlocks.parse(body) }
    val short = maxBlocks < Int.MAX_VALUE
    val text = TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.42f, color = G.Ink, fontFeatureSettings = "tnum")
    // Ticked items sit together at the end, as in Samsung Notes / Keep (the stored order is unchanged).
    val open = blocks.filterNot { it is NoteBlock.Todo && it.done }
    val ticked = blocks.filterIsInstance<NoteBlock.Todo>().filter { it.done }
    var showTicked by remember { mutableStateOf(true) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (fontSize >= 16.sp) 4.dp else 2.dp)) {
        val shown = if (short) blocks.take(maxBlocks) else open
        shown.forEach { b -> BlockView(b, text, short, onToggle) }
        if (!short && ticked.isNotEmpty() && open.any { it is NoteBlock.Todo }) {
            Row(Modifier.clip(G.Pill).clickable { showTicked = !showTicked }.padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (showTicked) "▾ " else "▸ ") + "${ticked.size} ticked", style = G.Sub.copy(fontWeight = FontWeight.SemiBold))
            }
            if (showTicked) ticked.forEach { b -> BlockView(b, text, false, onToggle) }
        } else if (!short) ticked.forEach { b -> BlockView(b, text, false, onToggle) }
        if (short && blocks.size > maxBlocks) Text("…", style = text.copy(color = G.Ink3))
    }
}

@Composable
private fun BlockView(b: NoteBlock, text: TextStyle, short: Boolean, onToggle: ((Int) -> Unit)?) {
    when (b) {
        is NoteBlock.Para ->
            if (b.text.startsWith(NotePrompts.SUMMARY_PREFIX)) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(G.AiSoft).padding(10.dp)) {
                AiLabel("AI summary")
                Text(richText(b.text.removePrefix(NotePrompts.SUMMARY_PREFIX)), Modifier.padding(top = 4.dp), style = text, maxLines = if (short) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            } else Text(richText(b.text), style = text, maxLines = if (short) 4 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        is NoteBlock.Gap -> Spacer(Modifier.height(if (text.fontSize >= 16.sp) 8.dp else 4.dp))
        is NoteBlock.Bullet -> Marked("•", b.indent, b.text, text, short)
        is NoteBlock.Numbered -> Marked(b.number + ".", b.indent, b.text, text, short)
        is NoteBlock.Todo -> Row(
            Modifier.fillMaxWidth().padding(start = (b.indent * 18).dp)
                .then(if (onToggle != null) Modifier.clip(G.Pill).clickable { onToggle(b.line) }.heightIn(min = 44.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (b.done) Ic.boxChecked else Ic.box, if (b.done) "Done" else "Not done",
                Modifier.size(if (onToggle != null) 22.dp else 16.dp), tint = if (b.done) G.Accent else G.Ink3)
            Spacer(Modifier.width(if (onToggle != null) 10.dp else 6.dp))
            Text(richText(b.text), Modifier.weight(1f), style = if (b.done) text.copy(color = G.Ink3, textDecoration = TextDecoration.LineThrough) else text,
                maxLines = if (short) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Marked(mark: String, indent: Int, body: String, style: TextStyle, short: Boolean) {
    Row(Modifier.padding(start = (indent * 18).dp)) {
        Text(mark, Modifier.width(if (mark.length > 2) 28.dp else 18.dp), style = style.copy(color = G.Ink2, fontWeight = FontWeight.SemiBold))
        Text(richText(body), Modifier.weight(1f), style = style, maxLines = if (short) 2 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

// ======================================================================================================
// Recording a voice part
// ======================================================================================================

@Composable
private fun RecordingPanel(app: WhisperAshApp, appending: Boolean, onCancel: () -> Unit, onDone: (Recording) -> Unit, onError: (String) -> Unit) {
    val recorder = remember { MicRecorder(app.cacheDir, maxMs = 15 * 60 * 1000L) }
    var started by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    BackHandler { recorder.cancel(); onCancel() }
    LaunchedEffect(Unit) {
        try {
            recorder.start()
            started = true
            app.scope.launch { app.groq.warmUp() }
        } catch (e: Exception) {
            onError(e.message ?: "Microphone unavailable"); return@LaunchedEffect
        }
        while (recorder.isRunning) {
            seconds = (System.currentTimeMillis() - recorder.startedAt) / 1000
            level = level * 0.6f + recorder.level.toFloat() * 0.4f
            delay(80)
        }
        if (started) scope.launch { finish(recorder, onDone, onError) } // hit the 15-minute cap
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter).padding(bottom = bottomClearance()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(if (appending) "Adding to your note" else "New voice note", style = G.Body.copy(color = G.Ink2, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(6.dp))
        Text("%d:%02d".format(seconds / 60, seconds % 60), style = G.LargeTitle.copy(fontSize = 52.sp, lineHeight = 56.sp))
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size((168 + 56 * level).dp).clip(CircleShape).background(G.AccentSoft), contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).glass(CircleShape, SolidColor(G.Accent)).clickable { scope.launch { finish(recorder, onDone, onError) } },
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Ic.check, null, Modifier.size(34.dp), tint = Color.White)
                    Text("Done", style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Meta("Talk freely: ramble, list things, say what to do. It's written down the way you say it, and the recording is kept in the note.")
        Spacer(Modifier.height(16.dp))
        Btn("Cancel", { recorder.cancel(); onCancel() }, kind = BtnKind.Glass, icon = Ic.close)
    }
}

private suspend fun finish(recorder: MicRecorder, onDone: (Recording) -> Unit, onError: (String) -> Unit) {
    try {
        val rec = withContext(Dispatchers.IO) { recorder.stop() }
        onDone(rec)
    } catch (e: Exception) {
        onError(e.message ?: "Recording failed")
    }
}

// ======================================================================================================
// Editor
// ======================================================================================================

/** Text undo / redo: snapshots of (title, body); quick typing is merged into one step. */
class EditHistory(first: Pair<String, String>, private val mergeMs: Long = 1_000) {
    private val stack = arrayListOf(first)
    private var at = 0
    private var lastPush = 0L

    fun push(state: Pair<String, String>, now: Long = System.currentTimeMillis(), merge: Boolean = true) {
        if (state == stack[at]) return
        while (stack.size > at + 1) stack.removeAt(stack.size - 1)
        if (merge && at > 0 && now - lastPush < mergeMs) stack[at] = state else { stack += state; at++ }
        if (stack.size > 100) { stack.removeAt(0); at-- }
        lastPush = now
    }

    val canUndo get() = at > 0
    val canRedo get() = at < stack.size - 1
    fun undo(): Pair<String, String> { if (at > 0) at--; lastPush = 0; return stack[at] }
    fun redo(): Pair<String, String> { if (at < stack.size - 1) at++; lastPush = 0; return stack[at] }
}

@Composable
private fun NoteEditor(
    app: WhisperAshApp, context: Context, id: String, editFirst: Boolean, version: Int,
    onBack: () -> Unit, onAddVoice: () -> Unit,
    unlock: (String, () -> Unit) -> Unit, canLock: () -> Boolean,
) {
    val pending by NoteJobs.pending.collectAsState()
    val errors by NoteJobs.errors.collectAsState()
    val working = pending[id]
    var reload by remember { mutableIntStateOf(0) }
    val note = remember(id, reload, working == null, version) { app.db.note(id) }
    if (note == null) { LaunchedEffect(id) { onBack() }; return }
    var title by remember(id, reload, working == null) { mutableStateOf(note.title) }
    var body by remember(id, reload, working == null) { mutableStateOf(TextFieldValue(note.body)) }
    var editing by remember(id) { mutableStateOf(editFirst) }
    val history = remember(id) { EditHistory(note.title to note.body) }
    var hv by remember { mutableIntStateOf(0) }   // recompose for undo/redo buttons
    var sheet by remember { mutableStateOf<String?>(null) }   // "more", "ai", "move"
    var aiBusy by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val folders = remember(version, reload) { app.db.folders() }

    fun save(edited: Boolean = true) {
        val cur = app.db.note(id) ?: return
        if (cur.title != title || cur.body != body.text) {
            app.db.saveNote(cur.copy(title = title, body = body.text), edited)
            app.sync.requestSync(SyncClient.Reason.CHANGE)
        }
    }
    fun setBody(v: TextFieldValue) { body = v; history.push(title to v.text); hv++ }
    fun leave() {
        save()
        val cur = app.db.note(id)
        // An empty note nobody wrote anything in is not kept.
        if (cur != null && working == null && cur.title.isBlank() && cur.body.isBlank() && cur.audio.isEmpty()) app.db.deleteNote(id)
        onBack()
    }
    // Auto-save shortly after typing stops.
    LaunchedEffect(title, body.text) { if (working == null) { delay(700); save() } }
    BackHandler { if (sheet != null) sheet = null else if (editing) { save(); editing = false } else leave() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().padding(horizontal = Gutter).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundGlass(Ic.back, "Back to notes", { leave() })
                Spacer(Modifier.weight(1f))
                RoundGlass(Ic.undo, "Undo", { if (history.canUndo) { val (t, b) = history.undo(); title = t; body = TextFieldValue(b, TextRange(b.length)); hv++ } },
                    tint = if (history.canUndo && hv >= 0) G.Ink else G.Ink3)
                Spacer(Modifier.width(6.dp))
                RoundGlass(Ic.redo, "Redo", { if (history.canRedo) { val (t, b) = history.redo(); title = t; body = TextFieldValue(b, TextRange(b.length)); hv++ } },
                    tint = if (history.canRedo && hv >= 0) G.Ink else G.Ink3)
                Spacer(Modifier.width(6.dp))
                RoundGlass(Ic.spark, "AI", { save(); sheet = "ai" }, tint = G.Ai)
                Spacer(Modifier.width(6.dp))
                RoundGlass(Ic.more, "More", { save(); sheet = "more" })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Gutter)) {
                BasicTextField(title, { title = it; history.push(it to body.text); hv++ }, Modifier.fillMaxWidth().padding(top = 4.dp),
                    textStyle = G.EntryTitle, cursorBrush = SolidColor(G.Accent),
                    decorationBox = { inner -> Box { if (title.isEmpty()) Text("Title", style = G.EntryTitle.copy(color = G.Ink3)); inner() } })
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meta("Edited " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(note.editedMs)) +
                        if (System.currentTimeMillis() - note.editedMs > 86_400_000L) ", " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(note.editedMs)) else "")
                    folders.firstOrNull { it.id == note.folderId }?.let { f -> Chip(f.name, Tone.Neutral, icon = Ic.folder) }
                    if (note.favourite) Icon(Ic.starFilled, "Favourite", Modifier.size(16.dp), tint = G.Accent)
                    if (note.locked) Icon(Ic.lock, "Locked", Modifier.size(16.dp), tint = G.Ink3)
                }
                if (note.audio.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        note.audio.forEachIndexed { i, clip ->
                            AudioChip(clip.path, clip.durationMs, label = if (note.audio.size > 1) "Part ${i + 1}" else "Recording")
                        }
                    }
                }
                if (working != null) {
                    Spacer(Modifier.height(10.dp))
                    GlassCard(fill = G.TintTeal, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(); Spacer(Modifier.width(12.dp))
                            Column { AiLabel("WhisperAsh"); Text(working, style = G.ItemTitle) }
                        }
                    }
                }
                errors[id]?.let { e ->
                    Spacer(Modifier.height(10.dp))
                    NoteBox(e, Tone.Bad, Modifier.clickable { NoteJobs.errors.value = NoteJobs.errors.value - id })
                }
                aiBusy?.let { a ->
                    Spacer(Modifier.height(10.dp))
                    GlassCard(fill = G.TintTeal, padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Spinner(); Spacer(Modifier.width(12.dp)); Text(a, style = G.ItemTitle) }
                    }
                }
                if (aiError.isNotEmpty()) { Spacer(Modifier.height(10.dp)); NoteBox(aiError, Tone.Bad) }
                Spacer(Modifier.height(12.dp))
                if (editing && working == null) {
                    BasicTextField(body, { v ->
                        val cont = NoteFormat.continueList(body.text, v.text, v.selection.start)
                        setBody(if (cont != null) TextFieldValue(cont.text, TextRange(cont.selStart, cont.selEnd)) else v)
                    }, Modifier.fillMaxWidth().heightIn(min = 240.dp), textStyle = G.Body.copy(lineHeight = 25.sp), cursorBrush = SolidColor(G.Accent),
                        decorationBox = { inner -> Box { if (body.text.isEmpty()) Text("Write, or use the bar below for lists and checklists.", style = G.Body.copy(color = G.Ink3)); inner() } })
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).clickable(enabled = working == null) { editing = true }) {
                        if (body.text.isBlank()) Text(if (working != null) "" else "Empty note. Tap to write.", style = G.Body.copy(color = G.Ink3))
                        else NoteBodyView(body.text, onToggle = if (working != null) null else { line ->
                            setBody(TextFieldValue(NoteBlocks.toggle(body.text, line))); save()
                        })
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            // ---- bottom: formatting bar while writing, else "Add recording" ----
            if (editing && working == null) Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 8.dp).glass(G.Pill, SolidColor(Color(0xEBFFFFFF))).padding(5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                fun fmt(kind: NoteFormat.Kind) {
                    val e = NoteFormat.toggleLines(body.text, body.selection.start, body.selection.end, kind)
                    setBody(TextFieldValue(e.text, TextRange(e.selStart, e.selEnd)))
                }
                FormatButton(Ic.listCheck, "Checklist") { fmt(NoteFormat.Kind.Check) }
                FormatButton(Ic.listBullet, "Bullets") { fmt(NoteFormat.Kind.Bullet) }
                FormatButton(Ic.listNumber, "Numbered list") { fmt(NoteFormat.Kind.Number) }
                FormatButton(Ic.bold, "Bold") {
                    val e = NoteFormat.toggleBold(body.text, body.selection.start, body.selection.end)
                    setBody(TextFieldValue(e.text, TextRange(e.selStart, e.selEnd)))
                }
                FormatButton(Ic.mic, "Add recording") { save(); onAddVoice() }
                Spacer(Modifier.weight(1f))
                Btn("Done", { save(); editing = false }, kind = BtnKind.Tint, icon = Ic.check)
            } else Row(
                Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = bottomClearance() - 6.dp)
                    .glass(G.Pill, SolidColor(Color(0xA6FFFFFF))).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Btn("Write", { editing = true }, kind = BtnKind.Glass, icon = Ic.pen, enabled = working == null)
                Btn("Add recording", { save(); onAddVoice() }, Modifier.weight(1f), BtnKind.Primary, Ic.mic, enabled = working == null)
            }
        }

        when (sheet) {
            "ai" -> GlassSheet("AI", { sheet = null }) {
                Meta("Changes the note; Undo (↶) brings back what was there.", Modifier.padding(bottom = 6.dp))
                NoteAi.entries.forEach { a ->
                    SheetRow(a.label, Ic.spark, Tone.Ai, sub = when (a) {
                        NoteAi.Tidy -> "Punctuation and fillers; your words stay"
                        NoteAi.Bullets -> "One point per line"
                        NoteAi.Summary -> "A short summary at the top, marked AI"
                        NoteAi.Roman -> "Hindi in Devanagari → Roman letters"
                    }) {
                        sheet = null; aiError = ""; aiBusy = a.label + "…"
                        scope.launch {
                            try {
                                val cur = app.db.note(id)!!.copy(title = title, body = body.text)
                                val out = withContext(Dispatchers.IO) { app.notes.ai(cur, a) }
                                history.push(out.title to out.body, merge = false); hv++
                                reload++
                                app.sync.requestSync(SyncClient.Reason.CHANGE)
                            } catch (e: Exception) {
                                aiError = e.message ?: "The AI could not do that right now."
                            }
                            aiBusy = null
                        }
                    }
                }
            }
            "more" -> GlassSheet(title.ifBlank { "Note" }, { sheet = null }) {
                SheetRow(if (note.favourite) "Remove from favourites" else "Add to favourites", if (note.favourite) Ic.starFilled else Ic.star, Tone.Wait) {
                    app.db.saveNote((app.db.note(id) ?: note).copy(pinned = !note.favourite), edited = false); sheet = null; reload++
                }
                SheetRow("Move to folder", Ic.folderMove, Tone.Info) { sheet = "move" }
                SheetRow(if (note.locked) "Unlock note" else "Lock note", if (note.locked) Ic.unlock else Ic.lock, Tone.Neutral,
                    sub = if (note.locked) null else "Opens with your fingerprint or screen lock") {
                    if (note.locked) unlock("Remove the lock") { app.db.saveNote((app.db.note(id) ?: note).copy(locked = false), edited = false); sheet = null; reload++ }
                    else if (!canLock()) Toast.makeText(context, "Set a screen lock (PIN, pattern or fingerprint) in the phone's settings first.", Toast.LENGTH_LONG).show()
                    else { app.db.saveNote((app.db.note(id) ?: note).copy(locked = true), edited = false); sheet = null; reload++ }
                }
                SheetRow("Share", Ic.share) {
                    sheet = null
                    val n = note.copy(title = title, body = body.text)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, n.title).putExtra(Intent.EXTRA_TEXT, NotePrompts.shareText(n)), "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                SheetRow("Copy text", Ic.copy) {
                    sheet = null
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Note", NotePrompts.shareText(note.copy(title = title, body = body.text))))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
                note.audio.forEachIndexed { i, clip ->
                    SheetRow("Delete recording" + if (note.audio.size > 1) " ${i + 1}" else "", Ic.mic, Tone.Bad, sub = "The text stays") {
                        app.notes.removeClip(app.db.note(id) ?: note, clip); sheet = null; reload++
                    }
                }
                SheetRow("Delete note", Ic.trash, Tone.Bad, sub = "Kept ${NoteQuery.BIN_DAYS} days in the recycle bin") {
                    save(); app.db.binNote(id); sheet = null
                    app.sync.requestSync(SyncClient.Reason.CHANGE); onBack()
                }
            }
            "move" -> MoveSheet(app, folders, note.folderId, onDismiss = { sheet = null }) { folderId ->
                app.db.saveNote((app.db.note(id) ?: note).copy(folderId = folderId), edited = false); sheet = null; reload++
                app.sync.requestSync(SyncClient.Reason.CHANGE)
            }
        }
    }
}

@Composable
private fun FormatButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = G.Ink)
    }
}
