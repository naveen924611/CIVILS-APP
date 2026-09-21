package com.naveen.civilscompanion.ui.sheets

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.report.reportRoutes

/** OWNER: Revision sheets + weekly report + last-month mode (M10). Routes: sheets, sheet/{topicId}, report. */
fun NavGraphBuilder.sheetsRoutes(nav: NavHostController) {
    composable(Routes.SHEETS) { SheetsScreen(nav) }
    composable(Routes.SHEET) { e -> SheetScreen(nav, e.arguments?.getString("topicId").orEmpty()) }
    reportRoutes(nav) // the weekly report screen lives in ui/report (MainActivity only calls sheetsRoutes)
}
