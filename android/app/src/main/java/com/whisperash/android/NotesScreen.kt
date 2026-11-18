package com.whisperash.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val NAccent = Color(0xFFFF8A50)
private val NCard = Color(0xFF1C1C23)
private val NMuted = Color(0xFFA0A0AE)
private val NRed = Color(0xFFFF4D5E)

private sealed class NotesMode {
    object List : NotesMode()
    data class Recording(val target: Note?) : NotesMode()
    data class Typing(val target: Note?) : NotesMode()
    data class Working(val message: String) : NotesMode()
    data class Detail(val id: String) : NotesMode()
}

/**
 * The Notes tab: speak or type, get a titled note in bullet points, edit it, add more by voice,
 * regenerate / undo, copy / share. Notes sync with the WhisperAsh account.
 */
@Composable
fun NotesTab(
    app: WhisperAshApp,
    version: Int,
    hasMic: () -> Boolean,
    askMic: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf<NotesMode>(NotesMode.List) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun ready(): Boolean {
        if (!hasMic()) { askMic(); return false }
        if (!app.canDictate()) { message = if (app.useAccount()) "Sign in on the Account tab first." else "Add your Groq key in Settings first."; return false }
        return true
    }

    /** Runs a note job off the main thread, then opens the note. */
    fun runJob(label: String, job: () -> Note) {
        mode = NotesMode.Working(label)
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { job() }
                app.sync.requestSync(SyncClient.Reason.CHANGE)
                refresh++
                mode = NotesMode.Detail(n.id)
            } catch (e: Exception) {
                message = when (e) {
                    is NoSpeechException -> "Didn't catch any speech. Try again."
                    else -> e.message ?: "Something went wrong"
                }
                mode = NotesMode.List
            }
        }
    }

    when (val m = mode) {
        is NotesMode.List -> NotesList(app, version + refresh, message,
            onOpen = { message = ""; mode = NotesMode.Detail(it) },
            onSpeak = { message = ""; if (ready()) mode = NotesMode.Recording(null) },
            onType = { message = ""; mode = NotesMode.Typing(null) })
        is NotesMode.Recording -> RecordingPanel(app,
            appending = m.target != null,
            onCancel = { mode = if (m.target != null) NotesMode.Detail(m.target.id) else NotesMode.List },
            onDone = { rec ->
                runJob(if (m.target != null) "Adding to your note…" else "Making your note…") {
                    val raw = app.engine.process(rec, null, System.currentTimeMillis(), skipCleanup = true).rawText
                    if (m.target != null) app.notes.append(app.db.note(m.target.id) ?: m.target, raw) else app.notes.create(raw)
                }
            },
            onError = { message = it; mode = NotesMode.List })
        is NotesMode.Typing -> TypingPanel(
            appending = m.target != null,
            onCancel = { mode = if (m.target != null) NotesMode.Detail(m.target.id) else NotesMode.List },
            onPointers = { text ->
                runJob("Making pointers…") {
                    if (m.target != null) app.notes.append(app.db.note(m.target.id) ?: m.target, text) else app.notes.create(text)
                }
            },
            onSaveAsIs = { text ->
                runJob("Saving…") {
                    if (m.target != null) {
                        val n = app.db.note(m.target.id) ?: m.target
                        app.db.saveNote(n.copy(body = n.body.trimEnd() + "\n" + text.trim(), raw = (n.raw + "\n\n" + text).trim()))
                    } else {
                        val now = System.currentTimeMillis()
                        app.db.saveNote(Note(java.util.UUID.randomUUID().toString(), NotePrompts.fallbackTitle(text), text.trim(), text.trim(), null, false, now, now))
                    }
                }
            })
        is NotesMode.Working -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(color = NAccent)
                Spacer(Modifier.height(16.dp))
                Text(m.message, color = NMuted)
            }
        }
        is NotesMode.Detail -> {
            val note = remember(m.id, refresh, version) { app.db.note(m.id) }
            if (note == null) { mode = NotesMode.List } else NoteDetail(app, context, note,
                onBack = { refresh++; mode = NotesMode.List },
                onSpeakMore = { if (ready()) mode = NotesMode.Recording(note) },
                onTypeMore = { mode = NotesMode.Typing(note) },
                onRegenerate = { runJob("Rebuilding the pointers…") { app.notes.regenerate(note) } },
                onChanged = { refresh++; app.sync.requestSync(SyncClient.Reason.CHANGE) },
                onDeleted = { refresh++; app.sync.requestSync(SyncClient.Reason.CHANGE); mode = NotesMode.List })
        }
    }
}

@Composable
private fun NotesList(app: WhisperAshApp, version: Int, message: String, onOpen: (String) -> Unit, onSpeak: () -> Unit, onType: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val notes = remember(query, version) { app.db.notes(query) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Notes", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = NAccent)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search ${notes.size} notes") })
        if (message.isNotEmpty()) Text(message, color = Color(0xFFFF8080), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (notes.isEmpty() && query.isBlank()) item {
                Text("Tap Speak and say what's on your mind, in English, Hindi or Hinglish. WhisperAsh turns it into a titled note in bullet points.",
                    color = NMuted, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(notes, key = { it.id }) { n ->
                Card(colors = CardDefaults.cardColors(containerColor = NCard), modifier = Modifier.fillMaxWidth().clickable { onOpen(n.id) }) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.pinned) Text("📌", fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(n.body, color = Color(0xFFD0D0DA), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(df.format(Date(n.updatedMs)), color = NMuted, fontSize = 11.sp)
                    }
                }
            }
        }
        // The big capture buttons, always in reach.
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSpeak, modifier = Modifier.weight(1f).height(64.dp), shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NAccent, contentColor = Color.Black)) {
                Text("🎤  Speak a note", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onType, modifier = Modifier.height(64.dp), shape = RoundedCornerShape(32.dp)) {
                Text("✏️  Type", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun RecordingPanel(app: WhisperAshApp, appending: Boolean, onCancel: () -> Unit, onDone: (Recording) -> Unit, onError: (String) -> Unit) {
    val recorder = remember { MicRecorder(app.cacheDir, maxMs = 15 * 60 * 1000L) }
    var started by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
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
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (appending) "Adding to your note" else "New note", color = NMuted)
        Spacer(Modifier.height(8.dp))
        Text("%d:%02d".format(seconds / 60, seconds % 60), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size((150 + 60 * level).dp).clip(CircleShape).background(NRed.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(NRed).clickable {
                scope.launch { finish(recorder, onDone, onError) }
            }, contentAlignment = Alignment.Center) {
                Text("■  Done", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Speak freely: it's fine to ramble, repeat or correct yourself.", color = NMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { recorder.cancel(); onCancel() }) { Text("✕  Cancel", color = NMuted, fontSize = 16.sp) }
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

@Composable
private fun TypingPanel(appending: Boolean, onCancel: () -> Unit, onPointers: (String) -> Unit, onSaveAsIs: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(if (appending) "Add to your note" else "Type a note", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text("Write anything; WhisperAsh turns it into pointers. You can also use the WhisperAsh keyboard's mic here.") })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = text.isNotBlank(), onClick = { onPointers(text) },
                colors = ButtonDefaults.buttonColors(containerColor = NAccent, contentColor = Color.Black)) { Text("Make pointers") }
            OutlinedButton(enabled = text.isNotBlank(), onClick = { onSaveAsIs(text) }) { Text("Save as typed") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun NoteDetail(
    app: WhisperAshApp, context: Context, note: Note,
    onBack: () -> Unit, onSpeakMore: () -> Unit, onTypeMore: () -> Unit, onRegenerate: () -> Unit,
    onChanged: () -> Unit, onDeleted: () -> Unit,
) {
    var title by remember(note.id, note.updatedMs) { mutableStateOf(note.title) }
    var body by remember(note.id, note.updatedMs) { mutableStateOf(note.body) }
    var showOriginal by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val edited = title != note.title || body != note.body
    fun saveEdits() { if (edited) { app.db.saveNote(note.copy(title = title.ifBlank { "Note" }, body = body)); onChanged() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { saveEdits(); onBack() }) { Text("‹ Notes", color = NAccent) }
            Spacer(Modifier.weight(1f))
            if (edited) Button(onClick = { saveEdits() }, colors = ButtonDefaults.buttonColors(containerColor = NAccent, contentColor = Color.Black)) { Text("Save") }
        }
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(body, { body = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Color(0xFFE8E8EE), lineHeight = 22.sp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { saveEdits(); onSpeakMore() }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = NAccent, contentColor = Color.Black)) { Text("🎤 Add more") }
            OutlinedButton(onClick = { saveEdits(); onTypeMore() }, modifier = Modifier.weight(1f)) { Text("✏️ Type more") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { saveEdits(); onRegenerate() }) { Text("↻ Regenerate") }
            if (note.prevBody != null) TextButton(onClick = { app.notes.undo(note); onChanged() }) { Text("↶ Undo") }
            TextButton(onClick = {
                saveEdits()
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Note", NotePrompts.shareText(note.copy(title = title, body = body))))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
            TextButton(onClick = {
                saveEdits()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, title)
                    .putExtra(Intent.EXTRA_TEXT, NotePrompts.shareText(note.copy(title = title, body = body)))
                context.startActivity(Intent.createChooser(send, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Share") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { saveEdits(); app.db.saveNote((app.db.note(note.id) ?: note).copy(pinned = !note.pinned)); onChanged() }) {
                Text(if (note.pinned) "Unpin" else "📌 Pin")
            }
            if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Color(0xFFFF8080)) }
            else TextButton(onClick = { app.db.deleteNote(note.id); onDeleted() }) { Text("Tap again to delete", color = Color(0xFFFF8080)) }
        }
        Spacer(Modifier.height(8.dp))
        Text(if (showOriginal) "▾ Your original words" else "▸ Your original words", color = NMuted,
            modifier = Modifier.clickable { showOriginal = !showOriginal }.padding(vertical = 6.dp))
        if (showOriginal) Text(note.raw, color = NMuted, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("Edited ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.updatedMs))}",
            color = NMuted, fontSize = 11.sp)
    }
}
