package com.whisperash.android

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/** Cuts a piece out of an .m4a without re-encoding (AAC frames copied as they are). */
object MeetingAudio {
    fun cut(src: File, dst: File, startMs: Long, endMs: Long) {
        val ex = MediaExtractor()
        val tmp = File(dst.path + ".tmp")
        var mux: MediaMuxer? = null
        try {
            ex.setDataSource(src.path)
            require(ex.trackCount > 0) { "no audio track" }
            ex.selectTrack(0)
            val fmt = ex.getTrackFormat(0)
            mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = mux.addTrack(fmt)
            mux.start()
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            var first = -1L
            var wrote = false
            while (true) {
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val ts = ex.sampleTime
                if (ts >= endUs) break
                if (ts >= startUs) {
                    if (first < 0) first = ts
                    info.set(0, n, ts - first, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    mux.writeSampleData(track, buf, info)
                    wrote = true
                }
                ex.advance()
            }
            require(wrote) { "empty part" }
            mux.stop()
        } finally {
            try { mux?.release() } catch (_: Exception) {}
            ex.release()
        }
        if (!tmp.renameTo(dst)) throw IllegalStateException("could not save part")
    }
}

/**
 * Meeting mic capture: AAC straight into the .m4a while recording (nothing kept in memory), plus
 * loudness per second (for cutting at quiet moments) and whether each second had speech.
 */
class MeetingRecorder(private val file: File, private val maxMs: Long = MAX_MS) {
    @Volatile var paused = false
    @Volatile var level = 0f
        private set
    @Volatile var recordedMs = 0L
        private set
    @Volatile var capReached = false
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var ok = false
    val rms = ArrayList<Float>()
    val speech = ArrayList<Boolean>()

    val isRunning get() = running

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, 32_000)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone is busy or not allowed")
        }
        val enc = AacEncoder(RATE, file)
        rec.startRecording()
        running = true
        thread = Thread({
            val buf = ByteArray(3_200) // 100 ms
            var samples = 0L
            var secSum = 0.0; var secN = 0; var secSpeech = false
            var winSum = 0.0; var winN = 0; var winPeak = 0
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) throw IllegalStateException("Mic read failed ($n)")
                    if (n == 0 || paused) { level = 0f; continue }
                    enc.feed(buf, n)
                    var i = 0
                    var chunkSum = 0.0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
                        val sq = (s * s).toDouble()
                        secSum += sq; secN++; winSum += sq; winN++; chunkSum += sq
                        if (abs(s) > winPeak) winPeak = abs(s)
                        if (winN == RATE / 10) {
                            if (LevelMeter.isSpeechWindow(sqrt(winSum / winN) / 32768.0, winPeak / 32768.0)) secSpeech = true
                            winSum = 0.0; winN = 0; winPeak = 0
                        }
                        if (secN == RATE) {
                            synchronized(this) { rms += (sqrt(secSum / secN) / 32768.0).toFloat(); speech += secSpeech }
                            secSum = 0.0; secN = 0; secSpeech = false
                        }
                        i += 2
                    }
                    level = minOf(1.0, sqrt(chunkSum / maxOf(1, n / 2)) / 32768.0 * 6).toFloat()
                    samples += n / 2
                    recordedMs = samples * 1000 / RATE
                    if (recordedMs >= maxMs) { capReached = true; running = false }
                }
                if (secN > 0) synchronized(this) { rms += (sqrt(secSum / secN) / 32768.0).toFloat(); speech += secSpeech }
                rec.stop()
                ok = enc.finish()
                if (!ok) failure = "Nothing was recorded"
            } catch (e: Exception) {
                Log.w(MeetingChain.TAG, "meeting recording failed: ${e.message}")
                failure = e.message ?: "Recording failed"
                // Keep what was recorded so far, if the encoder can still close the file.
                ok = try { enc.finish() } catch (_: Exception) { false }
                running = false
            } finally {
                rec.release()
                level = 0f
            }
        }, "whisperash-meeting-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Stops and closes the file. True when there is audio to transcribe. */
    fun stop(): Boolean {
        running = false
        thread?.join(10_000)
        thread = null
        return ok && file.length() > 0
    }

    fun cancel() {
        stop()
        file.delete()
    }

    @Synchronized fun energy(): Pair<FloatArray, BooleanArray> = rms.toFloatArray() to speech.toBooleanArray()

    companion object {
        const val RATE = 16_000
        const val MAX_MS = 3 * 60 * 60 * 1000L
    }
}

/** What the Meetings screen shows live. */
data class MeetingLive(
    val recordingId: String? = null,
    val recordingKind: RecordingKind = RecordingKind.MEETING,
    val recordingOwner: String = "",
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val paused: Boolean = false,
    /** Meeting id → progress text, for meetings being transcribed (or waiting to be). */
    val working: Map<String, String> = emptyMap(),
    val message: String = "",
)

/**
 * Foreground service (type microphone while recording, dataSync while transcribing) so a meeting keeps
 * recording with the screen off or the app in the background, and the transcript is made even if the
 * user leaves the app. Notification: timer + Pause / Stop.
 */
class MeetingRecorderService : Service() {
    private val app get() = application as WhisperAshApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobLock = Mutex()
    @Volatile private var recorder: MeetingRecorder? = null
    private var meeting: Meeting? = null
    private var ticker: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNotified = ""
    private var notificationKind = RecordingKind.MEETING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Meeting recorder", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperAsh:meeting").apply { acquire(4 * 3_600_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val kind = RecordingKind.fromWire(intent?.getStringExtra(EXTRA_KIND))
        if (action == ACTION_START && recorder == null) notificationKind = kind
        if (action == ACTION_RETRY && recorder == null) {
            intent?.getStringExtra(EXTRA_ID)?.let { id -> app.db.meeting(id)?.kind }?.let { notificationKind = it }
        }
        // START / RETRY come through startForegroundService: answer with startForeground right away.
        if ((action == ACTION_START || action == ACTION_RETRY) && !goForeground(action == ACTION_START)) {
            if (recorder == null && live.value.working.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startRecording(kind)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> stopRecording()
            ACTION_CANCEL -> cancelRecording()
            ACTION_RETRY -> {
                val id = intent?.getStringExtra(EXTRA_ID)
                if (id != null) enqueue(id, intent?.getBooleanExtra(EXTRA_FRESH, false) == true, intent?.getBooleanExtra(EXTRA_BETTER, false) == true)
            }
        }
        maybeStop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recorder?.let { r -> r.stop() }
        scope.cancel()
        try { wake?.release() } catch (_: Exception) {}
        live.update { it.copy(recordingId = null, working = emptyMap(), level = 0f) }
        super.onDestroy()
    }

    // ---------- recording ----------
    private fun startRecording(kind: RecordingKind) {
        if (recorder != null) return
        val id = UUID.randomUUID().toString()
        val dir = MeetingFiles.dir(filesDir, id).apply { mkdirs() }
        val file = File(dir, "audio.m4a")
        val rec = MeetingRecorder(file)
        try {
            rec.start()
        } catch (e: Exception) {
            dir.deleteRecursively()
            live.update { it.copy(message = e.message ?: "Microphone unavailable") }
            return
        }
        val now = System.currentTimeMillis()
        recorder = rec
        val owner = if (kind.isDiary) app.settings.userId else ""
        meeting = app.db.saveMeeting(Meeting(id, kind.defaultTitle(now), "", "", "", 0, now, now,
            file.path, "recording", null, kind = kind, diaryOwner = owner))
        live.update { it.copy(recordingId = id, recordingKind = kind, recordingOwner = owner,
            elapsedMs = 0, paused = false, message = "") }
        updateNotification()
        ticker = scope.launch {
            while (isActive) {
                val r = recorder ?: break
                live.update { it.copy(elapsedMs = r.recordedMs, level = r.level, paused = r.paused) }
                if (!r.isRunning) { // 3-hour cap or mic failure: keep what was recorded
                    launch(Dispatchers.Main) { stopRecording() }
                    break
                }
                delay(150)
            }
        }
    }

    private fun setPaused(p: Boolean) {
        val r = recorder ?: return
        r.paused = p
        live.update { it.copy(paused = p) }
        updateNotification()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        val m = meeting ?: return
        recorder = null; meeting = null
        ticker?.cancel()
        // Into "working" first, so the meeting never looks abandoned on screen.
        live.update { it.copy(recordingId = null, level = 0f, working = it.working + (m.id to "Saving the recording…")) }
        updateNotification()
        scope.launch {
            val ok = r.stop()
            val dir = File(m.audioPath!!).parentFile!!
            if (!ok) {
                dir.deleteRecursively()
                app.db.dropMeeting(m.id)
                live.update { it.copy(working = it.working - m.id, message = r.failure ?: "Nothing was recorded") }
                app.sync.dataVersion.value++
                launch(Dispatchers.Main) { maybeStop() }
                return@launch
            }
            val (rms, speech) = r.energy()
            MeetingFiles.writeEnergy(dir, rms, speech)
            app.db.saveMeeting(m.copy(durationMs = r.recordedMs, status = "transcribing"))
            if (r.capReached) live.update { it.copy(message = "Stopped at the 3-hour limit.") }
            else r.failure?.let { f -> live.update { it.copy(message = "Recording stopped: $f") } }
            app.sync.dataVersion.value++
            launch(Dispatchers.Main) { goForeground(false); enqueue(m.id, fresh = false) }
        }
    }

    private fun cancelRecording() {
        val r = recorder ?: return
        val m = meeting
        recorder = null; meeting = null
        ticker?.cancel()
        live.update { it.copy(recordingId = null, level = 0f) }
        // Done here, not in the background: the service may stop right after, and the mic must be off.
        r.cancel()
        m?.let { app.db.dropMeeting(it.id); MeetingFiles.dir(filesDir, it.id).deleteRecursively() }
        app.sync.dataVersion.value++
    }

    // ---------- transcription ----------
    private fun enqueue(id: String, fresh: Boolean, better: Boolean = false) {
        val w = live.value.working
        if (w.containsKey(id) && w[id] != "Saving the recording…") return
        setWorking(id, "Waiting…")
        scope.launch {
            jobLock.withLock {
                try {
                    MeetingJob(app).run(id, fresh, better) { setWorking(id, it) }
                } catch (e: Exception) {
                    Log.w(MeetingChain.TAG, "meeting job failed: ${e.message}")
                    app.db.meeting(id)?.let { app.db.saveMeeting(it.copy(status = "failed", error = e.message ?: "Transcription failed")) }
                }
            }
            live.update { it.copy(working = it.working - id) }
            app.sync.dataVersion.value++
            launch(Dispatchers.Main) { maybeStop() }
        }
    }

    private fun setWorking(id: String, text: String) {
        live.update { it.copy(working = it.working + (id to text)) }
        updateNotification()
    }

    private fun maybeStop() {
        if (recorder == null && live.value.working.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (recorder == null) goForeground(false)
    }

    // ---------- notification ----------
    private fun goForeground(starting: Boolean): Boolean {
        val mic = starting || recorder != null
        val type = if (Build.VERSION.SDK_INT >= 30 && mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            // Switching an already-running service to dataSync may be refused: it then keeps its type.
            Log.w(MeetingChain.TAG, "startForeground failed: ${e.message}")
            live.update { it.copy(message = "Android did not allow the meeting recorder to run: ${e.message}") }
            false
        }
    }

    private fun updateNotification() {
        val key = "${recorder != null}|${recorder?.paused}|${live.value.working.values.joinToString()}"
        if (key == lastNotified) return
        lastNotified = key
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun buildNotification(): android.app.Notification {
        val kind = if (live.value.recordingId != null) live.value.recordingKind else notificationKind
        val tab = if (kind.isDiary) MainActivity.TAB_DIARIES else MainActivity.TAB_MEETINGS
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val r = recorder
        if (r != null || live.value.recordingId != null) {
            val paused = r?.paused == true
            val elapsed = r?.recordedMs ?: 0
            b.setContentTitle(if (paused) "${kind.label.replaceFirstChar { it.uppercase() }} paused" else "Recording ${kind.label}")
            if (paused) b.setContentText("Paused at ${MeetingText.clock(elapsed)}").setUsesChronometer(false).setShowWhen(false)
            else b.setContentText("WhisperAsh is recording").setUsesChronometer(true).setShowWhen(true)
                .setWhen(System.currentTimeMillis() - elapsed)
            b.addAction(0, if (paused) "Resume" else "Pause", action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            b.addAction(0, "Stop", action(ACTION_STOP, 2))
        } else {
            b.setContentTitle("${kind.label.replaceFirstChar { it.uppercase() }} transcript")
            b.setContentText(live.value.working.values.firstOrNull() ?: "Working…")
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, MeetingRecorderService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "meetings"
        private const val NOTIFICATION_ID = 41
        const val ACTION_START = "com.whisperash.android.meeting.START"
        const val ACTION_PAUSE = "com.whisperash.android.meeting.PAUSE"
        const val ACTION_RESUME = "com.whisperash.android.meeting.RESUME"
        const val ACTION_STOP = "com.whisperash.android.meeting.STOP"
        const val ACTION_CANCEL = "com.whisperash.android.meeting.CANCEL"
        const val ACTION_RETRY = "com.whisperash.android.meeting.RETRY"
        const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        const val EXTRA_FRESH = "fresh"
        /** "Re-transcribe (better model)": Gemini [GeminiMeetingClient.BETTER_MODEL] for the audio, old text kept for Undo. */
        const val EXTRA_BETTER = "better"

        /** Live state for the Meetings screen (this process only). */
        val live = MutableStateFlow(MeetingLive())
        val state: StateFlow<MeetingLive> get() = live

        /** From the app's own screen (in the foreground): start the service or control a recording. */
        fun send(context: Context, action: String, id: String? = null, fresh: Boolean = false, better: Boolean = false,
            kind: RecordingKind = RecordingKind.MEETING) {
            val i = Intent(context, MeetingRecorderService::class.java).setAction(action)
                .putExtra(EXTRA_KIND, kind.wireName)
            if (id != null) i.putExtra(EXTRA_ID, id).putExtra(EXTRA_FRESH, fresh).putExtra(EXTRA_BETTER, better)
            if (action == ACTION_START || action == ACTION_RETRY) ContextCompat.startForegroundService(context, i)
            else context.startService(i)
        }

        fun clearMessage() { live.update { it.copy(message = "") } }
    }
}
