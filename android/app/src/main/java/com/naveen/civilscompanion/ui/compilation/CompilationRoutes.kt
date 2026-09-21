package com.naveen.civilscompanion.ui.compilation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes

/** Route `compilation` (Routes.COMPILATION). Registered from teluguRoutes, because MainActivity only calls that. */
fun NavGraphBuilder.compilationRoutes(nav: NavHostController) {
    composable(Routes.COMPILATION) { CompilationScreen(nav) }
}
