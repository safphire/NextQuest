// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.theme

/**
 * Design tokens from the brief (docs/SPEC.md §4) as plain ARGB values, so the contrast
 * test can check them on the JVM without Android. `onViolet` is the one addition:
 * text placed *on* a violet fill (the level badge), see docs/DECISIONS.md D-201.
 */
data class Palette(
    val background: Long,
    val surface: Long,
    val surfaceAlt: Long,
    val border: Long,
    val text: Long,
    val textSecondary: Long,
    val lime: Long,
    val onLime: Long,
    val violet: Long,
    val violetTint: Long,
    val onViolet: Long,
)

object Tokens {
    val Light = Palette(
        background = 0xFFF3F6F1,
        surface = 0xFFFFFFFF,
        surfaceAlt = 0xFFEEF3EF,
        border = 0xFFE1E8E2,
        text = 0xFF12201B,
        textSecondary = 0xFF56675F,
        lime = 0xFFC8F135,
        onLime = 0xFF12201B,
        violet = 0xFF5B4BFF,
        violetTint = 0xFFEEEBFF,
        onViolet = 0xFFFFFFFF,
    )

    val Dark = Palette(
        background = 0xFF0F1513,
        surface = 0xFF18201D,
        surfaceAlt = 0xFF212B27,
        border = 0xFF2C3833,
        text = 0xFFEAF2EC,
        textSecondary = 0xFFA3B3AB,
        lime = 0xFFC8F135,
        onLime = 0xFF12201B,
        violet = 0xFF8C80FF,
        violetTint = 0xFF252046,
        onViolet = 0xFF0F1513,
    )

    /** Every (foreground, background) pair the UI actually draws text with. */
    fun textPairs(p: Palette): List<Triple<String, Long, Long>> = buildList {
        for ((bgName, bg) in listOf("background" to p.background, "surface" to p.surface, "surfaceAlt" to p.surfaceAlt, "violetTint" to p.violetTint)) {
            add(Triple("text on $bgName", p.text, bg))
            add(Triple("textSecondary on $bgName", p.textSecondary, bg))
            add(Triple("violet on $bgName", p.violet, bg))
        }
        add(Triple("onLime on lime", p.onLime, p.lime))
        add(Triple("onViolet on violet", p.onViolet, p.violet))
    }

    /** WCAG 2.x contrast ratio of two opaque ARGB colors. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    const val MIN_TOUCH_DP = 48
}
