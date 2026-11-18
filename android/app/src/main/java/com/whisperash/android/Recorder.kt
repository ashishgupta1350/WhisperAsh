package com.whisperash.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Encodes 16-bit mono PCM to AAC-LC in an .m4a while it arrives, so stopping only has
 * to flush the last frame (~tens of ms). 32 kbit/s: a 10 s clip is ~40 KB to upload.
 */
class AacEncoder(private val sampleRate: Int, val out: File) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samples = 0L
    private var wroteAny = false

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun feed(pcm: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val n = minOf(buf.remaining(), len - off) and 1.inv()
                buf.put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n, samples * 1_000_000L / sampleRate, 0)
                samples += n / 2
                off += n
            }
            drain(false)
        }
    }

    /** Flushes and closes the file. Returns false when nothing was encoded. */
    fun finish(): Boolean {
        try {
            var queued = false
            while (!queued) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else drain(false)
            }
            drain(true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
        return wroteAny
    }

    fun abort() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxing) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        out.delete()
    }

    private fun drain(untilEos: Boolean) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        wroteAny = true
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

/** A finished recording. `pcm` (16-bit mono at `rate`) is kept so long recordings can be split into pieces. */
class Recording(
    val file: File, val mime: String, val durationMs: Long, val peak: Double, val speechMs: Long,
    val pcm: ShortArray? = null, val rate: Int = 16_000,
)

fun pcmToShorts(bytes: ByteArray, len: Int = bytes.size): ShortArray {
    val out = ShortArray(len / 2)
    ByteBuffer.wrap(bytes, 0, len).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
    return out
}

/** Mic capture on its own thread; level() feeds the keyboard's ring. */
class MicRecorder(private val cacheDir: File, private val maxMs: Long = MAX_MS) {
    @Volatile private var running = false
    @Volatile var level = 0.0
        private set
    private var thread: Thread? = null
    private var result: Recording? = null
    private var error: Exception? = null
    @Volatile var startedAt = 0L
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8_000)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone is busy or not allowed")
        }
        val file = File(cacheDir, "dictation-${System.currentTimeMillis()}.m4a")
        val enc = AacEncoder(rate, file)
        rec.startRecording()
        running = true
        startedAt = System.currentTimeMillis()
        result = null
        error = null
        thread = Thread({
            val buf = ByteArray(3_200) // 100 ms
            val pcmOut = java.io.ByteArrayOutputStream(rate * 2 * 30)
            val meter = LevelMeter(rate)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        pcmOut.write(buf, 0, n)
                        meter.add(buf, n)
                        level = meter.lastLevel
                        enc.feed(buf, n)
                    } else if (n < 0) throw IllegalStateException("Mic read failed ($n)")
                    if (System.currentTimeMillis() - startedAt > maxMs) running = false
                }
                rec.stop()
                val ok = enc.finish()
                if (!ok) throw IllegalStateException("Nothing was recorded")
                result = Recording(file, "audio/mp4", meter.durationMs, meter.peak, meter.speechMs, pcmToShorts(pcmOut.toByteArray()), rate)
            } catch (e: Exception) {
                error = e
                enc.abort()
            } finally {
                rec.release()
                level = 0.0
            }
        }, "whisperash-mic").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    val isRunning get() = running

    /** Stops and waits for the encoder flush. */
    fun stop(): Recording {
        running = false
        thread?.join(3_000)
        thread = null
        error?.let { throw it }
        return result ?: throw IllegalStateException("Recording did not finish")
    }

    fun cancel() {
        running = false
        thread?.join(3_000)
        thread = null
        result?.file?.delete()
        result = null
    }

    companion object {
        const val MAX_MS = 6 * 60 * 1000L
    }
}

/** Loudness tracking: drives the UI and tells silence from speech. */
class LevelMeter(private val rate: Int) {
    var peak = 0.0
        private set
    var lastLevel = 0.0
        private set
    var durationMs = 0L
        private set
    var speechMs = 0L
        private set

    fun add(pcm: ByteArray, len: Int) {
        val bb = ByteBuffer.wrap(pcm, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var n = 0
        var p = 0
        while (bb.remaining() >= 2) {
            val s = bb.short.toInt()
            sum += (s * s).toDouble()
            if (abs(s) > p) p = abs(s)
            n++
        }
        if (n == 0) return
        val rms = sqrt(sum / n) / 32768.0
        lastLevel = minOf(1.0, rms * 6)
        if (p / 32768.0 > peak) peak = p / 32768.0
        val ms = n * 1000L / rate
        durationMs += ms
        if (isSpeechWindow(rms, p / 32768.0)) speechMs += ms
    }

    companion object {
        // Same rule as the desktop's localSpeechGate.js: quiet laptop and phone mics speak at
        // RMS 0.003–0.01, so a higher bar threw away real dictations (PC ids 43, 55, 66).
        const val SPEECH_WINDOW_RMS = 0.003
        const val SPEECH_WINDOW_PEAK = 0.02

        fun isSpeechWindow(rms: Double, peak: Double) = rms >= SPEECH_WINDOW_RMS && peak >= SPEECH_WINDOW_PEAK

        /** Any 100 ms window of speech is enough; silence goes nowhere, so Whisper can't invent "Thank you." */
        fun hasSpeech(peak: Double, speechMs: Long) = peak >= SPEECH_WINDOW_PEAK && speechMs > 0
    }
}

/** Reads a 16-bit PCM WAV (debug test input). */
object Wav {
    data class Pcm(val rate: Int, val channels: Int, val data: ByteArray)

    fun read(file: File): Pcm {
        RandomAccessFile(file, "r").use { f ->
            val all = ByteArray(f.length().toInt())
            f.readFully(all)
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            require(String(all, 0, 4) == "RIFF" && String(all, 8, 4) == "WAVE") { "Not a WAV file" }
            var pos = 12
            var rate = 16_000
            var channels = 1
            var bits = 16
            while (pos + 8 <= all.size) {
                val id = String(all, pos, 4)
                val size = bb.getInt(pos + 4)
                if (id == "fmt ") {
                    channels = bb.getShort(pos + 10).toInt()
                    rate = bb.getInt(pos + 12)
                    bits = bb.getShort(pos + 22).toInt()
                } else if (id == "data") {
                    require(bits == 16) { "Only 16-bit WAV is supported" }
                    val end = minOf(all.size, pos + 8 + size)
                    var data = all.copyOfRange(pos + 8, end)
                    if (channels == 2) {
                        val mono = ByteArray(data.size / 2)
                        var i = 0
                        while (i + 3 < data.size) {
                            mono[i / 2] = data[i]; mono[i / 2 + 1] = data[i + 1]; i += 4
                        }
                        data = mono
                    }
                    return Pcm(rate, 1, data)
                }
                pos += 8 + size + (size and 1)
            }
            throw IllegalArgumentException("WAV has no data chunk")
        }
    }

    /** Encodes a WAV through the same AAC path as the mic, so tests exercise the real upload format. */
    fun toRecording(wav: File, cacheDir: File): Recording {
        val pcm = read(wav)
        val out = File(cacheDir, "test-${System.currentTimeMillis()}.m4a")
        val enc = AacEncoder(pcm.rate, out)
        val meter = LevelMeter(pcm.rate)
        var off = 0
        while (off < pcm.data.size) {
            val n = minOf(3_200, pcm.data.size - off)
            val chunk = pcm.data.copyOfRange(off, off + n)
            meter.add(chunk, n)
            enc.feed(chunk, n)
            off += n
        }
        enc.finish()
        return Recording(out, "audio/mp4", meter.durationMs, meter.peak, meter.speechMs, pcmToShorts(pcm.data), pcm.rate)
    }
}
