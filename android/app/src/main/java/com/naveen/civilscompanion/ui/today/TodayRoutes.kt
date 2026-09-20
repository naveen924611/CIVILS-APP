package com.naveen.civilscompanion.ui.today

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Today + Planner + exam dates + materials (M5). Routes: today, planner, exams, materials. */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.todayRoutes(nav: NavHostController) {
    composable(Routes.TODAY) { PlaceholderScreen(Destination.Today) }
}
