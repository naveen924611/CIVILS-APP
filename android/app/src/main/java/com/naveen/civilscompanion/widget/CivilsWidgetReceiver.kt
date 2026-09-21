package com.naveen.civilscompanion.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

/** The Android side of the widget (registered in the manifest). */
class CivilsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CivilsWidget()
}

/** Asks every placed widget to draw itself again with fresh data. Safe to call when no widget is placed. */
object WidgetUpdater {
    suspend fun refresh(context: Context) {
        try {
            CivilsWidget().updateAll(context)
        } catch (e: Exception) {
            // no widget on the home screen, or the launcher is busy: nothing to do
        }
    }
}
