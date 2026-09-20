package com.naveen.civilscompanion.ui.today

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.exams.ExamsScreen
import com.naveen.civilscompanion.ui.nav.Routes

/** OWNER: Today + Planner + exam dates (M5). Routes: today, planner, exams. (materials belongs to the Library.) */
fun NavGraphBuilder.todayRoutes(nav: NavHostController) {
    composable(Routes.TODAY) { TodayScreen(nav) }
    composable(Routes.PLANNER) { PlannerScreen(nav) }
    composable(Routes.EXAMS) { ExamsScreen(nav) }
}
