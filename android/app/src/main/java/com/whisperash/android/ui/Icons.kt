package com.whisperash.android.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * One stroke icon style for the whole app (Lucide-style 24×24 outlines, 2 px stroke, round caps), as in the
 * glass design system. Drawn from path data, so no icon font or extra dependency. Tinted by Icon(tint=…).
 */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) b.addPath(
            pathData = addPathNodes(d),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        return b.build()
    }

    val home = icon("home", "M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8", "M3 10a2 2 0 0 1 .71-1.53l7-6a2 2 0 0 1 2.58 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val note = icon("note", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M16 13H8", "M16 17H8", "M10 9H8")
    val users = icon("users", "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", circle(9f, 7f, 4f), "M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75")
    val gear = icon("gear", circle(12f, 12f, 3f), "M12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1")
    val mic = icon("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3")
    val keyboard = icon("keyboard", "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M6 8h.01M10 8h.01M14 8h.01M18 8h.01M8 12h.01M12 12h.01M16 12h.01M7 16h10")
    val history = icon("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val type = icon("type", "M4 7V4h16v3", "M9 20h6", "M12 4v16")
    val user = icon("user", circle(12f, 8f, 5f), "M20 21a8 8 0 0 0-16 0")
    val key = icon("key", circle(7.5f, 15.5f, 5.5f), "m21 2-9.6 9.6", "m15.5 7.5 3 3L22 7l-3-3")
    val globe = icon("globe", circle(12f, 12f, 10f), "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val spark = icon("spark", "M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.14-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.14a.5.5 0 0 1 .96 0L14.06 8.5A2 2 0 0 0 15.5 9.94l6.14 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.14a.5.5 0 0 1-.96 0z")
    val check = icon("check", "M20 6 9 17l-5-5")
    val close = icon("close", "M18 6 6 18", "m6 6 12 12")
    val back = icon("back", "m15 18-6-6 6-6")
    val chev = icon("chev", "m9 18 6-6-6-6")
    val plus = icon("plus", "M5 12h14M12 5v14")
    val search = icon("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val pen = icon("pen", "M21.17 6.81a1 1 0 0 0-3.99-3.99L3.84 16.17a2 2 0 0 0-.5.83l-1.32 4.35a.5.5 0 0 0 .62.62l4.35-1.32a2 2 0 0 0 .83-.5zM15 5l4 4")
    val trash = icon("trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val copy = icon("copy", "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", "m16 6-4-4-4 4", "M12 2v13")
    val pin = icon("pin", "M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z")
    val pause = icon("pause", "M7 4h3v16H7z", "M14 4h3v16h-3z")
    val play = icon("play", "M7 4l13 8-13 8V4z")
    val stop = icon("stop", "M7 7h10v10H7z", filled = true)
    val redo = icon("redo", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    val undo = icon("undo", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5 5.5 5.5 0 0 1-5.5 5.5H11")
    val box = icon("box", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z")
    val boxChecked = icon("box-checked", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "m8 12 3 3 5-6")
    val cloud = icon("cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    val listChecks = icon("list-checks", "M22 11.08V12a10 10 0 1 1-5.93-9.14", "m9 11 3 3L22 4")
    val logout = icon("logout", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "m16 17 5-5-5-5", "M21 12H9")
    val wave = icon("wave", "M2 10v3", "M6 6v11", "M10 3v18", "M14 8v7", "M18 5v13", "M22 10v3")
    val server = icon("server", "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z", "M6 6h.01M6 18h.01")
    val menu = icon("menu", "M4 6h16M4 12h16M4 18h16")
    val more = icon("more", circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f))
    private const val FOLDER = "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"
    val folder = icon("folder", FOLDER)
    val folderPlus = icon("folder-plus", FOLDER, "M12 10v6M9 13h6")
    val folderMove = icon("folder-move", FOLDER, "M8 13h8", "m13 10 3 3-3 3")
    private const val STAR = "M11.525 2.295a.53.53 0 0 1 .95 0l2.31 4.679a2.123 2.123 0 0 0 1.595 1.16l5.166.756a.53.53 0 0 1 .294.904l-3.736 3.638a2.123 2.123 0 0 0-.611 1.878l.882 5.14a.53.53 0 0 1-.771.56l-4.618-2.428a2.122 2.122 0 0 0-1.973 0L6.396 21.01a.53.53 0 0 1-.77-.56l.881-5.139a2.122 2.122 0 0 0-.611-1.879L2.16 9.795a.53.53 0 0 1 .294-.906l5.165-.755a2.122 2.122 0 0 0 1.597-1.16z"
    val star = icon("star", STAR)
    val starFilled = icon("star-filled", STAR, filled = true)
    private const val LOCK_BOX = "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z"
    val lock = icon("lock", LOCK_BOX, "M7 11V7a5 5 0 0 1 10 0v4")
    val unlock = icon("unlock", LOCK_BOX, "M7 11V7a5 5 0 0 1 9.9-1")
    val bold = icon("bold", "M6 12h9a4 4 0 0 1 0 8H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h7a4 4 0 0 1 0 8")
    val listBullet = icon("list-bullet", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    val listNumber = icon("list-number", "M10 12h11M10 18h11M10 6h11M4 10h2M4 6h1v4M6 18H4c0-1 2-2 2-3s-1-1.5-2-1")
    val listCheck = icon("list-check", "M13 5h8M13 12h8M13 19h8", "m3 17 2 2 4-4", "M4 3h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z")
    val grid = icon("grid", "M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z")
    val palette = icon("palette", circle(12f, 12f, 10f), "M8 9h.01M12 7h.01M16 9h.01M15 14a3 3 0 0 1-6 0")
}
