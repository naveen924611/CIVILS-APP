package com.naveen.civilscompanion.ui.ask

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Ask + voice + Explain-back + Answer writing (M6, M9). Routes: ask, explain, explain/{topicId}, answers, answer/{id}. */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.askRoutes(nav: NavHostController) {
    composable(Routes.ASK) { PlaceholderScreen(Destination.Ask) }
}
