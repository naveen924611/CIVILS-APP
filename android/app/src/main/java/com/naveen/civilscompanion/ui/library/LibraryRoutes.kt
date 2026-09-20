package com.naveen.civilscompanion.ui.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Library / Reader / Capture (M3, M4). Routes: library, read, read/{docId}, capture, materials. */
fun NavGraphBuilder.libraryRoutes(nav: NavHostController) {
    composable(Routes.LIBRARY) { PlaceholderScreen(Destination.Library) }
    composable(Routes.READ) { PlaceholderScreen(Destination.Read) }
}
