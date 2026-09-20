package com.naveen.civilscompanion.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalCcColors = staticCompositionLocalOf { LightCcColors }

/** Shortcut so screens can write `Cc.colors.rail`. */
object Cc {
    val colors: CcColors
        @Composable get() = LocalCcColors.current
}

private val CcShapes = Shapes(
    small = RoundedCornerShape(10.dp),   // buttons
    medium = RoundedCornerShape(12.dp),  // cards
    large = RoundedCornerShape(16.dp),   // big cards
)

@Composable
fun CivilsTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (darkTheme) DarkCcColors else LightCcColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryTint, onPrimaryContainer = c.onPrimaryTint,
            secondaryContainer = c.accentTint, onSecondaryContainer = c.onAccentTint,
            errorContainer = c.dangerTint, onErrorContainer = c.onDangerTint,
            background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.rail, onSurfaceVariant = c.muted,
            outline = c.border,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary,
            primaryContainer = c.primaryTint, onPrimaryContainer = c.onPrimaryTint,
            secondaryContainer = c.accentTint, onSecondaryContainer = c.onAccentTint,
            errorContainer = c.dangerTint, onErrorContainer = c.onDangerTint,
            background = c.background, onBackground = c.ink,
            surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.rail, onSurfaceVariant = c.muted,
            outline = c.border,
        )
    }
    CompositionLocalProvider(LocalCcColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = CcTypography, shapes = CcShapes, content = content)
    }
}
