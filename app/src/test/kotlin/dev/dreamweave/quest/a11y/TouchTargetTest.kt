// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.a11y

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.MainActivity
import dev.dreamweave.quest.QuestApp
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Acceptance: every touch target is at least 48×48dp. Walks the main screens of a
 * phone-sized (360dp wide) app with some data and checks each clickable node's touch
 * bounds (which include Material's minimum-interactive-size padding).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class TouchTargetTest {
    init {
        org.robolectric.shadows.ShadowLog.stream = System.out
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = (compose.activity.application as QuestApp).container

    private fun waitForText(text: String) = compose.waitUntil(15_000) {
        runCatching { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
    }

    /** Clickable nodes fully inside the window that are smaller than 48dp either way. */
    private fun tooSmall(): List<String> {
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.boundsInRoot.width * it.boundsInRoot.height }
        val minPx = with(compose.density) { 47.5f * density }
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.inside(root) }
            .filter { it.touchBoundsInRoot.width < minPx || it.touchBoundsInRoot.height < minPx }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    ?: "#${n.id}"
                val w = n.touchBoundsInRoot.width / compose.density.density
                val h = n.touchBoundsInRoot.height / compose.density.density
                "“$label” ${"%.0f".format(w)}×${"%.0f".format(h)}dp"
            }
    }

    private fun SemanticsNode.inside(root: SemanticsNode): Boolean {
        val b = boundsInRoot
        val r = root.boundsInRoot
        return b.left >= r.left && b.top >= r.top && b.right <= r.right && b.bottom <= r.bottom && b.width > 0 && b.height > 0
    }

    @Test
    fun mainScreens_haveNoTouchTargetUnder48dp() {
        // Off the main thread, then pumped: blocking the main thread would deadlock with the
        // view model, whose coroutines resume on it while holding the repository lock.
        val setup = container.appScope.launch {
            val actions = container.actions
            actions.capture("Write the talk")
            actions.capture("Fix the bike")
            val talk = container.repository.current().quests.values.first { it.title == "Write the talk" }.id
            actions.track(talk, firstStep = "Outline three points")
            container.settings.update { it.copy(onboardingDone = true) }
        }
        compose.waitUntil(15_000) { setup.isCompleted }
        val problems = mutableMapOf<String, List<String>>()

        waitForText("Outline three points")
        problems["Today"] = tooSmall()
        for (tab in listOf("Board", "Calendar", "Trophies")) {
            compose.onNode(hasText(tab) and hasClickAction()).performClick()
            compose.waitForIdle()
            problems[tab] = tooSmall()
        }
        compose.onNode(hasText("Board") and hasClickAction()).performClick()
        waitForText("Fix the bike")
        compose.onNode(hasText("Fix the bike") and hasClickAction(), useUnmergedTree = false).performClick()
        compose.waitForIdle()
        problems["Quest"] = tooSmall()

        val failures = problems.filterValues { it.isNotEmpty() }
        if (failures.isNotEmpty()) fail("Touch targets under 48dp:\n" + failures.entries.joinToString("\n") { (k, v) -> "$k: ${v.joinToString()}" })
        assertTrue(problems.size >= 5)
    }
}
