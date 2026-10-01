// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import androidx.compose.ui.test.assertIsDisplayed
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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acceptance: fresh install → capture → track → done → next step works offline, fast.
 * Drives the real activity (no network anywhere) and checks the event log afterwards.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CoreLoopUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        compose.waitUntil(timeoutMs) {
            // Before the first frame is attached there is no compose hierarchy yet: keep waiting.
            try {
                compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
            } catch (e: IllegalStateException) {
                false
            }
        }
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

        // Board → Track → first step.
        compose.onNodeWithText("Open Board").performClick()
        waitForText("Write the talk")
        compose.onNodeWithText("Track").performClick()
        waitForText("First step")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Outline three points")
        compose.onNode(hasText("Track") and hasAnyAncestor(isDialog())).performClick()

        // Today shows the single next step; Done → What's next? → set it.
        compose.onNode(hasText("Today") and hasClickAction()).performClick()
        waitForText("Outline three points")
        compose.onNodeWithText("Done · +15 XP").performClick()
        waitForText("What's next?")
        compose.onNode(hasSetTextAction()).performTextInput("Draft the intro")
        compose.onNodeWithText("Set next step").performClick()
        waitForText("Draft the intro")
        compose.onNodeWithText("Draft the intro").assertIsDisplayed()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsedMs < 30_000, "core loop took ${elapsedMs}ms")

        val state = runBlocking { (compose.activity.application as QuestApp).container.repository.current() }
        val quest = state.quests.values.single()
        assertEquals(QuestStatus.TRACKED, quest.status)
        assertEquals("Draft the intro", state.effectiveStep(quest.id)!!.text)
        assertEquals(15, state.xp)
    }
}
