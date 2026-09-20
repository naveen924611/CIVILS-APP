package com.naveen.civilscompanion.ui.exams

import androidx.compose.runtime.Composable

/**
 * OWNER: Revision + Planner (M5). First-run setup step "Exams, dates, priority and study hours" (spec 6.19).
 * The Setup screen (owned by Settings) shows this composable in its own page; call onNext when the owner presses Continue.
 * Until built it calls onNext straight away.
 */
@Composable
fun ExamSetupStep(onNext: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
}
