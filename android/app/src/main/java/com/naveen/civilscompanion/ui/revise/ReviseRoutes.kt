package com.naveen.civilscompanion.ui.revise

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes

/** OWNER: Revision + Planner + Today (M5). Routes: revise, revise/session, revise/rules. */
fun NavGraphBuilder.reviseRoutes(nav: NavHostController) {
    composable(Routes.REVISE) { ReviseScreen(nav) }
    composable(Routes.REVISE_SESSION) { ReviseSessionScreen(nav) }
    composable(Routes.REVISE_RULES) { ReviseRulesScreen(nav) }
}
