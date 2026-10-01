// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.data.ThemeMode

/** Compose colors for the [Palette] in use. Lime is only for primary actions, never for text. */
@Immutable
data class QuestColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val lime: Color,
    val onLime: Color,
    val violet: Color,
    val violetTint: Color,
    val onViolet: Color,
    val isDark: Boolean,
)

private fun Palette.toColors(dark: Boolean) = QuestColors(
    Color(background), Color(surface), Color(surfaceAlt), Color(border), Color(text), Color(textSecondary),
    Color(lime), Color(onLime), Color(violet), Color(violetTint), Color(onViolet), dark,
)

val LocalQuestColors = staticCompositionLocalOf { Tokens.Light.toColors(false) }

object Quest {
    val colors: QuestColors @Composable get() = LocalQuestColors.current
}

val QuestShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun QuestTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = (if (dark) Tokens.Dark else Tokens.Light).toColors(dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.violet, onPrimary = c.onViolet, primaryContainer = c.violetTint, onPrimaryContainer = c.text,
            secondary = c.violet, onSecondary = c.onViolet, secondaryContainer = c.violetTint, onSecondaryContainer = c.text,
            tertiary = c.lime, onTertiary = c.onLime,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textSecondary, outline = c.border, outlineVariant = c.border,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surfaceAlt,
            surfaceContainerHighest = c.surfaceAlt, surfaceContainerLowest = c.background,
        )
    } else {
        lightColorScheme(
            primary = c.violet, onPrimary = c.onViolet, primaryContainer = c.violetTint, onPrimaryContainer = c.text,
            secondary = c.violet, onSecondary = c.onViolet, secondaryContainer = c.violetTint, onSecondaryContainer = c.text,
            tertiary = c.lime, onTertiary = c.onLime,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surfaceAlt, onSurfaceVariant = c.textSecondary, outline = c.border, outlineVariant = c.border,
            surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surfaceAlt,
            surfaceContainerHighest = c.surfaceAlt, surfaceContainerLowest = c.background,
        )
    }
    CompositionLocalProvider(LocalQuestColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = QuestTypography, shapes = QuestShapes, content = content)
    }
}
