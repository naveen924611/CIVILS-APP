package com.naveen.civilscompanion.ui.goals

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes

/** SI (Civil) goal screen. Opened from Exams, from Today and from a plan block whose ref is "goals". Not a rail item. */
fun NavGraphBuilder.goalsRoutes(nav: NavHostController) {
    composable(Routes.GOALS) { GoalsScreen(nav) }
}
