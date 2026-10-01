// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.dreamweave.quest.R

/** Titles. */
val Bricolage = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
)

/** Body. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/** Meta labels and numbers. */
val PlexMono = FontFamily(
    Font(R.font.plexmono_regular, FontWeight.Normal),
    Font(R.font.plexmono_medium, FontWeight.Medium),
    Font(R.font.plexmono_semibold, FontWeight.SemiBold),
)

val QuestTypography = Typography(
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

/** Mono numbers (XP, counts, dates). */
val MonoNumber = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
