package com.naveen.civilscompanion.ui.notes

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.syllabus.SyllabusReviewScreen
import com.naveen.civilscompanion.ui.syllabus.SyllabusScreen

/** OWNER: Syllabus + Notes (M4). Routes: notes, notes/{topicId}, syllabus, syllabus/{importId}. */
fun NavGraphBuilder.notesRoutes(nav: NavHostController) {
    composable(Routes.NOTES) { NotesScreen(nav, null) }
    composable(Routes.NOTE_TOPIC) { e -> NotesScreen(nav, e.arguments?.getString("topicId")) }
    composable(Routes.SYLLABUS) { SyllabusScreen(nav) }
    composable(Routes.SYLLABUS_REVIEW) { e -> SyllabusReviewScreen(nav, e.arguments?.getString("importId").orEmpty()) }
}
