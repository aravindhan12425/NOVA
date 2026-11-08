package com.assistant.nova.services

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.assistant.nova.R
import com.assistant.nova.TalkActivity

/** Home-screen "Talk to Nova" button. */
class NovaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, TalkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_nova).apply {
                setOnClickPendingIntent(R.id.widget_root, tap)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
