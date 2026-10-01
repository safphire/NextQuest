// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.core.QuestStatus
import android.Manifest
import android.os.Build
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance: fresh install → capture → track → done → next step works offline, fast.
 * Drives the real activity (no network anywhere) and checks the event log afterwards.
 */
@RunWith(AndroidJUnit4::class)
class CoreLoopUiTest {
    // Granted up front: the system permission dialog would cover the app (D-302 asks after first launch).
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val repository get() = (compose.activity.application as QuestApp).container.repository

    /** Like waitUntil, but a timeout fails with the whole UI tree (CI logs are all we get). */
    private fun waitFor(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs) {
                // Before the first frame is attached there is no compose hierarchy yet: keep waiting.
                try {
                    condition()
                } catch (e: IllegalStateException) {
                    false
                }
            }
        } catch (e: ComposeTimeoutException) {
            val tree = runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }.getOrElse { "<no tree: $it>" }
            val quests = repository.state.value?.quests?.values?.map { "${it.title}/${it.status}" }
            throw AssertionError("Timed out waiting for $what. Quests: $quests\n$tree", e)
        }
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) = waitFor("\"$text\"", timeoutMs) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    /** The single on-screen node matching [matcher] (a closing sheet may still be composed off-screen). */
    private fun onScreen(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val height = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        val ids = compose.onAllNodes(matcher).fetchSemanticsNodes().filter { it.boundsInRoot.top < height }.map { it.id }
        val id = ids.singleOrNull() ?: throw AssertionError("Expected one on-screen ${matcher.description}, found ${ids.size}")
        return compose.onNode(matcher and SemanticsMatcher("node $id") { it.id == id })
    }

    @Test
    fun freshInstall_capture_track_done_nextStep_offlineUnder30s() {
        val started = System.nanoTime()

        // First launch: skip the brain dump.
        waitForText("Empty your head")
        compose.onNodeWithText("Skip — start empty").performClick()
        waitForText("Nothing tracked yet")

        // Capture from the FAB: one field, Enter saves.
        compose.onNodeWithContentDescription("Capture").performClick()
        waitForText("Capture")
        compose.onNode(hasSetTextAction()).performTextInput("Write the talk")
        compose.onNode(hasSetTextAction()).performImeAction()
        // Never runBlocking on the main thread here: a commit started from the UI holds the
        // repository lock and resumes on this very thread (deadlock). Read the state flow.
        waitFor("the capture to reach the event log") { repository.state.value?.quests?.isNotEmpty() == true }
        // (The closed sheet animates away; on-screen lookups below ignore it meanwhile.)

        // Board → Track → first step.
        compose.onNodeWithText("Open Board").performClick()
        waitForText("Write the talk")
        compose.onNodeWithText("Track").performClick()
        waitForText("First step")
        onScreen(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Outline three points")
        compose.onNode(hasText("Track") and hasAnyAncestor(isDialog())).performClick()

        // Today shows the single next step; Done → What's next? → set it.
        compose.onNode(hasText("Today") and hasClickAction()).performClick()
        waitForText("Outline three points")
        compose.onNodeWithText("Done · +15 XP").performClick()
        waitForText("What's next?")
        onScreen(hasSetTextAction()).performTextInput("Draft the intro")
        compose.onNodeWithText("Set next step").performClick()
        waitForText("Draft the intro")
        compose.onNodeWithText("Draft the intro").assertIsDisplayed()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 30_000, "core loop took ${elapsedMs}ms")

        compose.waitForIdle()
        val state = repository.state.value!!
        val quest = state.quests.values.single()
        assertEquals(QuestStatus.TRACKED, quest.status)
        assertEquals("Draft the intro", state.effectiveStep(quest.id)!!.text)
        assertEquals(15, state.xp)
    }
}
