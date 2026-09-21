package com.naveen.civilscompanion.ui.focus

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.naveen.civilscompanion.ui.nav.Routes
import com.naveen.civilscompanion.ui.settings.StorageScreen
import com.naveen.civilscompanion.ui.videos.VideoPlayerScreen
import com.naveen.civilscompanion.ui.videos.VideosScreen

/** OWNER: Focus + Videos + Storage/Settings/Widget (M7, M11). Routes: focus, videos, video/{id}, storage. */
fun NavGraphBuilder.focusRoutes(nav: NavHostController) {
    composable(Routes.FOCUS) { FocusScreen(nav) }
    composable(Routes.VIDEOS) { VideosScreen(nav) }
    composable(Routes.VIDEO) { e -> VideoPlayerScreen(nav, e.arguments?.getString("id").orEmpty()) }
    composable(Routes.STORAGE) { StorageScreen(nav) }
}
