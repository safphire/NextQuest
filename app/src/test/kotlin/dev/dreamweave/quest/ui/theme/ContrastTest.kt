// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.theme

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Brief §4: text contrast ≥ 4.5:1 in both themes, for every pair the UI draws. */
class ContrastTest {
    @Test
    fun everyTextPairPassesAA_inBothThemes() {
        val failures = listOf("light" to Tokens.Light, "dark" to Tokens.Dark).flatMap { (name, palette) ->
            Tokens.textPairs(palette).mapNotNull { (pair, fg, bg) ->
                val ratio = Tokens.contrast(fg, bg)
                if (ratio < 4.5) "$name: $pair = ${"%.2f".format(ratio)}" else null
            }
        }
        assertTrue(failures.isEmpty(), "Contrast below 4.5:1 → $failures")
    }

    @Test
    fun contrastMathMatchesWcagReferenceValues() {
        assertEquals(21.0, Tokens.contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Tokens.contrast(0xFF777777, 0xFF777777), 0.001)
        assertEquals(4.48, Tokens.contrast(0xFF777777, 0xFFFFFFFF), 0.01)
    }

    @Test
    fun limeIsNeverUsedAsTextColor() {
        for (p in listOf(Tokens.Light, Tokens.Dark)) {
            assertTrue(Tokens.textPairs(p).none { (_, fg, _) -> fg == p.lime }, "lime must not be a text color")
        }
    }

    @Test
    fun tokensMatchTheBrief() {
        assertEquals(0xFFF3F6F1, Tokens.Light.background)
        assertEquals(0xFF0F1513, Tokens.Dark.background)
        assertEquals(0xFFC8F135, Tokens.Light.lime)
        assertEquals(0xFFC8F135, Tokens.Dark.lime)
        assertEquals(0xFF5B4BFF, Tokens.Light.violet)
        assertEquals(0xFF8C80FF, Tokens.Dark.violet)
        assertEquals(48, Tokens.MIN_TOUCH_DP)
    }
}
