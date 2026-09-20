package com.naveen.civilscompanion.ui.notes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Destination
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.placeholder.PlaceholderScreen

/** OWNER: Notes / Syllabus / Revise (M4, M5). Routes: notes, notes/{topicId}, syllabus, syllabus/{importId}, revise, revise/session, revise/rules. */
fun NavGraphBuilder.notesRoutes(nav: NavHostController) {
    composable(Routes.NOTES) { PlaceholderScreen(Destination.Notes) }
    composable(Routes.REVISE) { PlaceholderScreen(Destination.Revise) }
}
