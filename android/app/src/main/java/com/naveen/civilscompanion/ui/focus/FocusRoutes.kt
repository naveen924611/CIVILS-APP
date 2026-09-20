package com.naveen.civilscompanion.ui.focus

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Focus + Videos + Storage/Settings/Widget (M7, M11). Routes: focus, videos, video/{id}, storage. */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.focusRoutes(nav: NavHostController) {
    composable(Routes.FOCUS) { PlaceholderScreen(Destination.Focus) }
}
