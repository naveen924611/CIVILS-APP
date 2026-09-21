package com.naveen.civilscompanion.ui.telugu

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.compilation.compilationRoutes
import com.naveen.civilscompanion.ui.nav.Routes

/** OWNER: Telugu practice + monthly compilation + polish (M12). Routes: telugu, compilation (the screens live in ui/compilation). */
fun NavGraphBuilder.teluguRoutes(nav: NavHostController) {
    composable(Routes.TELUGU) { TeluguScreen(nav) }
    compilationRoutes(nav) // MainActivity only calls teluguRoutes
}
