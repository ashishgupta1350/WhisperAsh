package com.whisperash.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen quick capture (Samsung Notes / Keep style): "Speak a note" and "Type a note" open Notes ready to go. */
class QuickNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_note).apply {
            setOnClickPendingIntent(R.id.widget_speak, open(context, "speak", 10))
            setOnClickPendingIntent(R.id.widget_type, open(context, "type", 11))
        }
        for (id in ids) manager.updateAppWidget(id, views)
    }

    private fun open(context: Context, what: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTE, what)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
