package com.naveen.civilscompanion.ui.revise

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Revision + Planner + Today (M5). Routes: revise, revise/session, revise/rules. */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.reviseRoutes(nav: NavHostController) {
    composable(Routes.REVISE) { PlaceholderScreen(Destination.Revise) }
}
