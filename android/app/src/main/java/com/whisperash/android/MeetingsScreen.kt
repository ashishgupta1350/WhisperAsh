package com.whisperash.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.whisperash.android.ui.AiLabel
import com.whisperash.android.ui.BackBar
import com.whisperash.android.ui.Btn
import com.whisperash.android.ui.BtnKind
import com.whisperash.android.ui.Chip
import com.whisperash.android.ui.G
import com.whisperash.android.ui.GlassCard
import com.whisperash.android.ui.Gutter
import com.whisperash.android.ui.Ic
import com.whisperash.android.ui.IconSquare
import com.whisperash.android.ui.LargeTitle
import com.whisperash.android.ui.Meta
import com.whisperash.android.ui.Meter
import com.whisperash.android.ui.NoteBox
import com.whisperash.android.ui.RoundGlass
import com.whisperash.android.ui.SectionTitle
import com.whisperash.android.ui.Spinner
import com.whisperash.android.ui.Subtitle
import com.whisperash.android.ui.Tone
import com.whisperash.android.ui.glass
import com.whisperash.android.ui.GlassSheet
import com.whisperash.android.ui.SheetRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Meetings tab: record a meeting (keeps going with the screen off), then a speaker-labelled Roman
 * Hinglish transcript + summary. Recording and transcription run in [MeetingRecorderService].
 * [autoRecord]: Home's "Record meeting" asked to start right away.
 */
@Composable
fun MeetingsTab(
    app: WhisperAshApp,
    version: Int,
    hasMic: () -> Boolean,
    askMic: () -> Unit,
    askNotificationsOnce: () -> Unit,
    autoRecord: Boolean = false,
    onAutoRecordHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val live by MeetingRecorderService.state.collectAsState()
    var openId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }

    fun record() {
        message = ""
        MeetingRecorderService.clearMessage()
        if (!hasMic()) { askMic(); return }
        if (!app.canRecordMeetings()) {
            message = "Sign in under Settings → Account, or add a Gemini, Deepgram or Groq key in Settings (My own keys)."
            return
        }
        askNotificationsOnce()
        MeetingRecorderService.send(context, MeetingRecorderService.ACTION_START)
    }

    LaunchedEffect(autoRecord) {
        if (autoRecord) {
            if (live.recordingId == null) record()
            onAutoRecordHandled()
        }
    }
    BackHandler(enabled = openId != null && live.recordingId == null) { openId = null; refresh++ }

    val id = openId
    when {
        live.recordingId != null -> RecordingView(live,
            onPause = { MeetingRecorderService.send(context, MeetingRecorderService.ACTION_PAUSE) },
            onResume = { MeetingRecorderService.send(context, MeetingRecorderService.ACTION_RESUME) },
            onStop = {
                openId = live.recordingId
                MeetingRecorderService.send(context, MeetingRecorderService.ACTION_STOP)
            },
            onCancel = { MeetingRecorderService.send(context, MeetingRecorderService.ACTION_CANCEL) })
        id != null -> {
            val m = remember(id, version, refresh, live.working[id]) { app.db.meeting(id) }
            if (m == null) {
                // Still being saved right after Stop: wait for it; otherwise it was deleted.
                if (live.working.containsKey(id)) Busy(live.working[id] ?: "Saving…") else LaunchedEffect(id) { openId = null }
            } else MeetingDetail(app, context, m, live.working[m.id],
                onBack = { openId = null; refresh++ },
                onChanged = { refresh++ },
                onDeleted = { openId = null; refresh++ })
        }
        else -> MeetingList(app, version + refresh, live, live.message.ifEmpty { message },
            onOpen = { openId = it }, onRecord = { record() })
    }
}

@Composable
private fun Busy(text: String) {
    Box(Modifier.fillMaxSize().padding(Gutter), contentAlignment = Alignment.Center) {
        GlassCard(fill = G.TintTeal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(); Spacer(Modifier.width(14.dp)); Text(text, style = G.ItemTitle)
            }
        }
    }
}

private fun statusChip(m: Meeting, progress: String?): Pair<String, Tone>? = when {
    progress != null -> "Working" to Tone.Ai
    m.status == "failed" -> "Not transcribed" to Tone.Bad
    m.status == "done" && !m.error.isNullOrBlank() -> "No summary" to Tone.Wait
    else -> null
}

@Composable
private fun MeetingList(app: WhisperAshApp, version: Int, live: MeetingLive, message: String, onOpen: (String) -> Unit, onRecord: () -> Unit) {
    val meetings = remember(version, live.working.keys) { app.db.meetings() }
    var minutes by remember { mutableStateOf(app.sync.lastPlan?.meetingSummary()) }
    LaunchedEffect(version, app.sync.signedIn) {
        if (app.sync.signedIn) minutes = withContext(Dispatchers.IO) { app.sync.fetchPlan() }?.meetingSummary() ?: minutes
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        ) {
            item {
                LargeTitle("Meetings")
                Subtitle(listOfNotNull(
                    if (meetings.isEmpty()) "Transcript with speakers and a summary" else counted(meetings.size, "meeting"),
                    minutes?.takeIf { app.sync.signedIn },
                ).joinToString(" · "))
            }
            if (message.isNotEmpty()) item { NoteBox(message, Tone.Bad) }
            if (meetings.isEmpty()) item {
                GlassCard {
                    Text("No meetings yet", style = G.ItemTitle)
                    Meta("Put the phone on the table and tap Record meeting. It keeps recording with the screen off, up to 3 hours, in Hindi, English or Hinglish.",
                        Modifier.padding(top = 4.dp))
                }
            }
            items(meetings, key = { it.id }) { m ->
                val progress = live.working[m.id]
                GlassCard(padding = PaddingValues(16.dp), onClick = { onOpen(m.id) }) {
                    Row(verticalAlignment = Alignment.Top) {
                        IconSquare(Ic.users, if (m.status == "failed") Tone.Bad else Tone.Info)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = G.ItemTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Meta(MeetingText.metaLine(m), maxLines = 1)
                        }
                        statusChip(m, progress)?.let { (t, tone) -> Spacer(Modifier.width(8.dp)); Chip(t, tone) }
                    }
                    when {
                        progress != null -> Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spinner(Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(progress, style = G.Sub.copy(color = G.Ai))
                        }
                        m.summary.isNotBlank() -> Text(
                            MeetingText.preview(m.summary),
                            Modifier.padding(top = 8.dp), style = G.Sub.copy(color = G.Ink2), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Btn("Record meeting", onRecord, Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(top = 8.dp, bottom = bottomClearance() - 4.dp),
            BtnKind.Primary, Ic.wave, big = true)
    }
}

@Composable
private fun RecordingView(live: MeetingLive, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onCancel: () -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Gutter).padding(bottom = bottomClearance()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Chip(if (live.paused) "Paused" else "Recording", if (live.paused) Tone.Wait else Tone.Bad)
        Spacer(Modifier.height(10.dp))
        Text(MeetingText.clock(live.elapsedMs), style = G.LargeTitle.copy(fontSize = 56.sp, lineHeight = 60.sp))
        Spacer(Modifier.height(20.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
            Meta(if (live.paused) "Nothing is being recorded while paused." else "Sound level")
            Spacer(Modifier.height(8.dp))
            Meter(if (live.paused) 0f else live.level.coerceIn(0.03f, 1f), color = G.Ai)
        }
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier.size(132.dp).glass(CircleShape, SolidColor(G.Accent)).clickable { onStop() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Ic.stop, null, Modifier.size(30.dp), tint = Color.White)
                Text("Stop", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White))
            }
        }
        Spacer(Modifier.height(20.dp))
        Btn(if (live.paused) "Resume" else "Pause", { if (live.paused) onResume() else onPause() }, kind = BtnKind.Glass,
            icon = if (live.paused) Ic.play else Ic.pause)
        Spacer(Modifier.height(14.dp))
        Meta("Keeps recording with the screen off or in another app. Stops by itself at 3 hours.")
        Spacer(Modifier.height(8.dp))
        if (!confirmCancel) Btn("Cancel", { confirmCancel = true }, kind = BtnKind.Plain, icon = Ic.close)
        else Btn("Tap again to throw it away", onCancel, kind = BtnKind.Danger, icon = Ic.trash)
    }
}

@Composable
private fun MeetingDetail(
    app: WhisperAshApp, context: Context, m: Meeting, progress: String?,
    onBack: () -> Unit, onChanged: () -> Unit, onDeleted: () -> Unit,
) {
    var title by remember(m.id, m.updatedMs) { mutableStateOf(m.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val edited = title.isNotBlank() && title != m.title
    fun saveTitle() {
        if (!edited) return
        val cur = app.db.meeting(m.id) ?: return
        app.db.saveMeeting(cur.copy(title = title.trim()))
        app.sync.requestSync(SyncClient.Reason.CHANGE)
        onChanged()
    }
    val shown = m.copy(title = title)
    val canRetry = progress == null && m.status != "recording" && m.audioPath?.let { java.io.File(it).exists() } == true

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter).padding(bottom = bottomClearance())) {
        BackBar("Meetings", { saveTitle(); onBack() }) {
            statusChip(m, progress)?.let { (t, tone) -> Chip(t, tone) }
            if (edited) { Spacer(Modifier.width(8.dp)); Btn("Save", { saveTitle() }, kind = BtnKind.Tint, icon = Ic.check) }
        }
        BasicTextField(title, { title = it }, Modifier.fillMaxWidth(), textStyle = G.LargeTitle.copy(fontSize = 28.sp, lineHeight = 32.sp),
            cursorBrush = SolidColor(G.Accent))
        Meta(MeetingText.metaLine(m), Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(12.dp))

        if (progress != null) {
            GlassCard(fill = G.TintTeal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(); Spacer(Modifier.width(12.dp))
                    Column { AiLabel("WhisperAsh"); Text(progress, style = G.ItemTitle) }
                }
            }
            Spacer(Modifier.height(10.dp))
            NoteBox("You can leave this screen: the transcript is made in the background.", Tone.Wait)
            Spacer(Modifier.height(12.dp))
        }
        if (progress == null && !m.error.isNullOrBlank()) {
            NoteBox(m.error, if (m.status == "failed") Tone.Bad else Tone.Wait)
            Spacer(Modifier.height(12.dp))
        }

        // Undo for "Re-transcribe (better model)", while the old text is still kept.
        if (progress == null && m.prevTranscript != null) {
            GlassCard(fill = G.TintTeal, padding = PaddingValues(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiLabel("Re-transcribed with ${GeminiMeetingClient.BETTER_LABEL}", Modifier.weight(1f))
                    Btn("Undo", { app.db.restoreMeeting(m.id); app.sync.requestSync(SyncClient.Reason.CHANGE); onChanged() }, kind = BtnKind.Plain, icon = Ic.undo)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canRetry && m.status == "failed") Btn("Retry", {
                saveTitle()
                MeetingRecorderService.send(context, MeetingRecorderService.ACTION_RETRY, m.id, fresh = false)
            }, kind = BtnKind.Primary, icon = Ic.redo)
            Spacer(Modifier.weight(1f))
            if (m.transcript.isNotBlank()) {
                RoundGlass(Ic.copy, "Copy", {
                    saveTitle()
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Meeting", MeetingText.shareText(shown)))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                })
                RoundGlass(Ic.share, "Share", {
                    saveTitle()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, shown.title).putExtra(Intent.EXTRA_TEXT, MeetingText.shareText(shown))
                    context.startActivity(Intent.createChooser(send, "Share meeting notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                })
            }
            if (progress == null) RoundGlass(Ic.more, "More", { menu = true })
        }
        if (menu) GlassSheet(title.ifBlank { "Meeting" }, { menu = false }) {
            // Hidden when the recording is no longer on this phone.
            if (progress == null && Retranscribe.canRun(m.audioPath)) SheetRow("Re-transcribe (better model)", Ic.spark, Tone.Ai,
                sub = "${GeminiMeetingClient.BETTER_LABEL}, with speakers. The current transcript is kept for Undo.") {
                menu = false; saveTitle()
                MeetingRecorderService.send(context, MeetingRecorderService.ACTION_RETRY, m.id, fresh = true, better = true)
            }
            if (m.prevTranscript != null) SheetRow("Restore previous transcript", Ic.undo, sub = "The text from before the re-transcribe") {
                menu = false; app.db.restoreMeeting(m.id); app.sync.requestSync(SyncClient.Reason.CHANGE); onChanged()
            }
            SheetRow("Delete meeting", Ic.trash, Tone.Bad) { menu = false; confirmDelete = true }
        }
        if (confirmDelete) {
            Spacer(Modifier.height(10.dp))
            GlassCard(fill = SolidColor(Color(0xCCFFFFFF))) {
                Text("Delete this meeting?", style = G.ItemTitle)
                Meta("The transcript, summary and recording are removed from this phone, and the text from your other devices.", Modifier.padding(top = 2.dp, bottom = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("Cancel", { confirmDelete = false }, kind = BtnKind.Glass)
                    Btn("Delete", {
                        m.audioPath?.let { MeetingFiles.deleteFor(it) }
                        app.db.deleteMeeting(m.id)
                        app.sync.requestSync(SyncClient.Reason.CHANGE)
                        onDeleted()
                    }, kind = BtnKind.Danger, icon = Ic.trash)
                }
            }
        }

        if (m.summary.isNotBlank()) {
            SectionTitle("Summary")
            GlassCard(fill = G.TintTeal) {
                AiLabel("Written by AI from the transcript")
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(m.summary.trim(), style = G.Body.copy(fontSize = 16.sp, lineHeight = 23.sp)) }
            }
        }
        if (m.transcript.isNotBlank()) {
            SectionTitle("Transcript")
            GlassCard {
                SelectionContainer { Text(m.transcript, style = G.Body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = G.Ink2)) }
            }
        }
        if (m.status == "done" && m.audioPath != null) {
            Meta("The recording stays on this phone (not synced) so you can redo the transcript; Delete removes it.", Modifier.padding(top = 12.dp))
        }
    }
}
