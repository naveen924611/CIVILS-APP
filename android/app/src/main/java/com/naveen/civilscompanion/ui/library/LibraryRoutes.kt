package com.naveen.civilscompanion.ui.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.capture.CaptureScreen
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.read.ReadHomeScreen
import com.naveen.civilscompanion.ui.read.ReadScreen

/** OWNER: Library + Reader + Capture (M3, M4). Routes: library, read, read/{docId}, capture. */
fun NavGraphBuilder.libraryRoutes(nav: NavHostController) {
    composable(Routes.LIBRARY) { LibraryScreen(nav) }
    composable(Routes.READ) { ReadHomeScreen(nav) }
    composable(Routes.READ_DOC) { entry -> ReadScreen(nav, entry.arguments?.getString("docId").orEmpty()) }
    composable(Routes.CAPTURE) { CaptureScreen(nav) }
}
