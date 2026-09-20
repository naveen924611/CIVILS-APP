package com.naveen.civilscompanion.ui.syllabus

import androidx.compose.runtime.Composable

/**
 * OWNER: Syllabus + Notes (M4). First-run setup step "Approve your syllabus" (spec 6.19 and 6.20).
 * The Setup screen (owned by Settings) shows this composable in its own page; call onNext when the owner presses Continue
 * (or skips: the syllabus can be approved later from Notes). Until built it calls onNext straight away.
 */
@Composable
fun SyllabusSetupStep(onNext: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onNext() }
}
