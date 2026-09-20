package com.naveen.civilscompanion.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Design tokens from section 5 of the spec. Dark hues are the same family, darkened. */
@Immutable
data class CcColors(
    val background: Color,
    val surface: Color,
    val rail: Color,
    val border: Color,
    val ink: Color,
    val muted: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryTint: Color,
    val onPrimaryTint: Color,
    val accentTint: Color,
    val onAccentTint: Color,
    val dangerTint: Color,
    val onDangerTint: Color,
    val playerBar: Color,
    val playerProgress: Color,
    val isDark: Boolean,
)

val LightCcColors = CcColors(
    background = Color(0xFFF6F3EC),
    surface = Color(0xFFFFFDF8),
    rail = Color(0xFFEFEADF),
    border = Color(0xFFE2DACB),
    ink = Color(0xFF1C1B19),
    muted = Color(0xFF5E5A52),
    primary = Color(0xFF0F5E5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryTint = Color(0xFFDCEBE8),
    onPrimaryTint = Color(0xFF0A4441),
    accentTint = Color(0xFFF6E7D2),
    onAccentTint = Color(0xFF7A4A0E),
    dangerTint = Color(0xFFF4D9D2),
    onDangerTint = Color(0xFF6E2515),
    playerBar = Color(0xFF1C1B19),
    playerProgress = Color(0xFF7FC4B8),
    isDark = false,
)

val DarkCcColors = CcColors(
    background = Color(0xFF14130F),
    surface = Color(0xFF1E1C17),
    rail = Color(0xFF191813),
    border = Color(0xFF34302A),
    ink = Color(0xFFEDE9DF),
    muted = Color(0xFFA8A296),
    primary = Color(0xFF7FC4B8),
    onPrimary = Color(0xFF06302D),
    primaryTint = Color(0xFF17403D),
    onPrimaryTint = Color(0xFFBFE5DE),
    accentTint = Color(0xFF3D2E14),
    onAccentTint = Color(0xFFF0CE9A),
    dangerTint = Color(0xFF4A211A),
    onDangerTint = Color(0xFFF2B8AA),
    playerBar = Color(0xFF0C0B09),
    playerProgress = Color(0xFF7FC4B8),
    isDark = true,
)
