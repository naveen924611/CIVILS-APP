package com.naveen.civilscompanion.ui.voice

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naveen.civilscompanion.ui.nav.lineIcon

/** Small line icons drawn from SVG paths (there is no icon library in the app). */
object CcPaths {
    const val MIC = "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3"
    const val SPEAKER = "M4 9h4l5-4v14l-5-4H4zM16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"
    const val STOP = "M7 7h10v10H7z"
    const val CAMERA = "M4 8h3l2-3h6l2 3h3v11H4zM12 11a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z"
    const val PLUS = "M12 5v14M5 12h14"
    const val CLOSE = "M6 6l12 12M18 6L6 18"
}

@Composable
fun PathIcon(name: String, path: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val icon = remember(name, path) { lineIcon(name, path) }
    Image(imageVector = icon, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}
