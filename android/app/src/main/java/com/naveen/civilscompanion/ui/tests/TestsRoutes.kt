package com.naveen.civilscompanion.ui.tests

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes

/** OWNER: Tests + mistake book (M8). Routes: tests, test/{testId}, test/{testId}/result, mistakes. */
fun NavGraphBuilder.testsRoutes(nav: NavHostController) {
    composable(Routes.TESTS) { TestsScreen(nav) }
    composable(Routes.TEST_RUN) { e -> TestRunScreen(nav, e.arguments?.getString("testId").orEmpty()) }
    composable(Routes.TEST_RESULT) { e -> TestResultScreen(nav, e.arguments?.getString("testId").orEmpty()) }
    composable(Routes.MISTAKES) { MistakesScreen(nav) }
}
