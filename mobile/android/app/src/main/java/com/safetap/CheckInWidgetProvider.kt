package com.safetap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// Second widget: one tap starts a check-in timer. The tap goes to
// CheckInReceiver, which talks to the backend and arms the alarm.
class CheckInWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_checkin)
            val intent = Intent(context, CheckInReceiver::class.java).apply {
                action = CheckInReceiver.ACTION_START_CHECKIN
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.checkin_button, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
