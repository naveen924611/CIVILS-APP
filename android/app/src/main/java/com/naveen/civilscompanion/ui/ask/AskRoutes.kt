package com.naveen.civilscompanion.ui.ask

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.answers.AnswerScreen
import com.naveen.civilscompanion.ui.answers.AnswersScreen
import com.naveen.civilscompanion.ui.explain.ExplainPickerScreen
import com.naveen.civilscompanion.ui.explain.ExplainScreen
import com.naveen.civilscompanion.ui.nav.Routes

/** OWNER: Ask + voice + Explain-back + Answer writing (M6, M9). Routes: ask, explain, explain/{topicId}, answers, answer/{id}. */
fun NavGraphBuilder.askRoutes(nav: NavHostController) {
    composable(Routes.ASK) { AskScreen(nav) }
    composable(Routes.EXPLAIN) { ExplainPickerScreen(nav) }
    composable(Routes.EXPLAIN_TOPIC) { e -> ExplainScreen(nav, e.arguments?.getString("topicId").orEmpty()) }
    composable(Routes.ANSWERS) { AnswersScreen(nav) }
    composable(Routes.ANSWER) { e -> AnswerScreen(nav, e.arguments?.getString("id").orEmpty()) }
}
