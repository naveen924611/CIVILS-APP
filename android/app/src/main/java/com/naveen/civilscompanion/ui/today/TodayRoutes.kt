package com.naveen.civilscompanion.ui.today

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Today / Planner / Focus / Exams and Storage screens (M5, M7, M11). Routes: today, planner, focus, exams, storage. */
fun NavGraphBuilder.todayRoutes(nav: NavHostController) {
    composable(Routes.TODAY) { PlaceholderScreen(Destination.Today) }
    composable(Routes.FOCUS) { PlaceholderScreen(Destination.Focus) }
}
