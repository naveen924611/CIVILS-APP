package com.naveen.civilscompanion.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.naveen.civilscompanion.AppLinks
import com.naveen.civilscompanion.theme.LightCcColors
import com.naveen.civilscompanion.ui.nav.Routes

/**
 * Home-screen widget: today's next task with a Start tap, tasks left, cards due for revision, study time today and the
 * next brief. It always uses the light colours of the app (a widget cannot follow the app's own theme setting).
 * Kept small on purpose; it reads the tablet's own data, so it works offline.
 */
class CivilsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = WidgetData.load(context)
        provideContent { WidgetContent(context, model) }
    }
}

private fun paint(color: Color) = ColorProvider(color)

private fun style(color: Color, size: Int, bold: Boolean = false) =
    TextStyle(color = paint(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)

@Composable
private fun WidgetContent(context: Context, m: WidgetModel) {
    val c = LightCcColors
    val openApp = actionStartActivity(AppLinks.activityIntent(context, AppLinks.ACTION_OPEN_ROUTE, route = Routes.TODAY))
    val startTask = actionStartActivity(AppLinks.activityIntent(context, AppLinks.ACTION_OPEN_ROUTE, route = m.nextRoute))
    val revise = actionStartActivity(AppLinks.activityIntent(context, AppLinks.ACTION_OPEN_ROUTE, route = Routes.REVISE_SESSION))
    Column(
        modifier = GlanceModifier.fillMaxSize().background(paint(c.background)).padding(12.dp).clickable(openApp),
    ) {
        Text("Today", style = style(c.primary, 13, bold = true))
        Text(WidgetLogic.planLine(m), style = style(c.muted, 12))
        Spacer(GlanceModifier.height(6.dp))
        if (m.tasksLeft > 0) {
            Column(
                modifier = GlanceModifier.fillMaxWidth().background(paint(c.primaryTint)).padding(8.dp).clickable(startTask),
            ) {
                Text("Next · ${m.nextTime}", style = style(c.onPrimaryTint, 12))
                Text(m.nextTitle, style = style(c.ink, 15, bold = true), maxLines = 2)
                Text("Tap to start", style = style(c.primary, 12, bold = true))
            }
            Spacer(GlanceModifier.height(6.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Column(modifier = GlanceModifier.clickable(revise).padding(end = 16.dp)) {
                Text("${m.cardsDue}", style = style(c.ink, 18, bold = true))
                Text("cards to revise", style = style(c.muted, 11))
            }
            Column {
                Text(m.studiedText, style = style(c.ink, 18, bold = true))
                Text("studied today", style = style(c.muted, 11))
            }
        }
        if (m.nextBrief.isNotBlank()) {
            Spacer(GlanceModifier.height(6.dp))
            Text("Next brief: ${m.nextBrief}", style = style(c.muted, 11))
        }
    }
}
