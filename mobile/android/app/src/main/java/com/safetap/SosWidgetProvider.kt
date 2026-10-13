package com.safetap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// Home-screen SOS widget. RemoteViews can only fire a PendingIntent on tap,
// so the tap opens SosActivity, where the 3-second press-and-hold arming
// and the 5-second cancel countdown live.
class SosWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_sos)
            val intent = Intent(context, SosActivity::class.java)
            val pi = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.sos_button, pi)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
