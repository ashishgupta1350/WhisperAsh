package com.whisperash.android.ui

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Plays one recording at a time (note voice parts, History dictations). */
object ClipPlayer {
    val playing = MutableStateFlow<String?>(null)
    private var mp: MediaPlayer? = null

    fun toggle(path: String) {
        if (playing.value == path) { stop(); return }
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playing.value = path
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        playing.value = null
    }

    fun positionMs(): Int = try { mp?.currentPosition ?: 0 } catch (_: Exception) { 0 }
}

fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** A glass chip: play / pause, position or length, and an optional trailing action. */
@Composable
fun AudioChip(path: String, durationMs: Long, modifier: Modifier = Modifier, label: String? = null, trailing: @Composable () -> Unit = {}) {
    val playing by ClipPlayer.playing.collectAsState()
    val on = playing == path
    var pos by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) { while (on) { pos = ClipPlayer.positionMs(); delay(250) } }
    DisposableEffect(path) { onDispose { if (ClipPlayer.playing.value == path) ClipPlayer.stop() } }
    val exists = remember(path) { File(path).exists() }
    Row(modifier.glass(G.Pill, SolidColor(G.GlassStrong), shadow = false).padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (exists) G.Ai else G.NeutralBg).clickable(enabled = exists) { ClipPlayer.toggle(path) },
            contentAlignment = Alignment.Center) {
            Icon(if (on) Ic.pause else Ic.play, if (on) "Pause" else "Play", Modifier.size(16.dp), tint = if (exists) Color.White else G.Ink3)
        }
        Spacer(Modifier.width(8.dp))
        Text((label?.let { "$it · " } ?: "") + if (on) "${clockText(pos.toLong())} / ${clockText(durationMs)}" else clockText(durationMs),
            style = G.Sub.copy(fontWeight = FontWeight.SemiBold, color = G.Ink2, fontSize = 13.5.sp))
        trailing()
    }
}
