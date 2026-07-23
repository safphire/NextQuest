package com.nextquest.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// v0 visual identity (SPEC.md §3). Dark-only — there is no light scheme.
val Ink = Color(0xFF0E0C09)
val Gold = Color(0xFFE6B455)
val OnGold = Color(0xFF1A1409)
val Amber = Color(0xFFF59E0B) // "define the next step" state
val Parchment = Color(0xFFEDE4D4)
val Muted = Color(0xFF9A8F7D)
val SurfaceInk = Color(0xFF17130E)
val SurfaceRaised = Color(0xFF201A13)

private val InkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = OnGold,
    secondary = Amber,
    onSecondary = OnGold,
    background = Ink,
    onBackground = Parchment,
    surface = SurfaceInk,
    onSurface = Parchment,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = Muted,
    outline = Muted,
)

// TODO(M1 polish): bundle Chakra Petch (display) / IBM Plex Sans (body) /
// IBM Plex Mono (meta) per the spec; system faces stand in for M0.
private val NextQuestTypography = Typography(
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
    ),
)

@Composable
fun NextQuestTheme(content: @Composable () -> Unit) {
    // Deliberately ignores the system theme: the quest log is always night.
    MaterialTheme(
        colorScheme = InkScheme,
        typography = NextQuestTypography,
        content = content,
    )
}
