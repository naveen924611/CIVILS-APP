package com.naveen.civilscompanion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * True when the window is narrow (the tablet held upright, about 800 dp wide) instead of wide (landscape, about 1280 dp).
 * The app shell provides it. In narrow mode the navigation moves to a bar at the bottom, and screens that show two
 * panes side by side should stack them (list on top, details below) or use one pane at a time.
 */
val LocalCompact: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Width below which the app uses the narrow (upright) layout. */
const val COMPACT_BELOW_DP = 900

@Composable
fun isCompact(): Boolean = LocalCompact.current
