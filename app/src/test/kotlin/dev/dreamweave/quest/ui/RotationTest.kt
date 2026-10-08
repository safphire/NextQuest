// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.core.EventFactory
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Fold
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.NextStep
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.QuestStatusSet
import dev.dreamweave.quest.ui.sheets.CaptureSheet
import dev.dreamweave.quest.ui.sheets.SwapDialog
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import dev.dreamweave.quest.ui.sheets.WhatsNextSheet
import dev.dreamweave.quest.ui.sheets.rememberSavedDialog
import dev.dreamweave.quest.ui.theme.QuestTheme
import dev.dreamweave.quest.data.ThemeMode
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Open dialogs and sheets (capture, What's next, track's first step, swap) come back after a
 * configuration change or process death — open, with what was typed. [StateRestorationTester]
 * saves the instance state, throws the composition away and restores it, as rotation does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RotationTest {
    @get:Rule
    val compose = createComposeRule()

    @Serializable
    data class Flow(val questId: String, val step: String? = null)

    @Test
    fun trackFlow_firstStepThenSwap_survivesRestore() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            QuestTheme(ThemeMode.LIGHT) {
                var flow by rememberSavedDialog(Flow.serializer())
                val f = flow
                when {
                    f == null -> androidx.compose.material3.TextButton(onClick = { flow = Flow("q1") }) { Text("Track it") }
                    f.step == null -> TextPromptDialog(
                        title = "First step", confirmLabel = "Next", onDismiss = { flow = null },
                        onConfirm = { flow = f.copy(step = it) },
                    )
                    else -> SwapDialog(tracked = emptyList(), cap = 5, onDismiss = { flow = null }, onPark = {})
                }
            }
        }
        compose.onNodeWithText("Track it").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Buy a tube")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("First step").assertExists()
        compose.onNodeWithText("Buy a tube").assertExists()

        compose.onNodeWithText("Next").performClick()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("All 5 slots are full").assertExists()
    }

    @Test
    fun captureSheet_keepsTypedText() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            QuestTheme(ThemeMode.LIGHT) {
                var open by rememberSaveable { mutableStateOf(true) }
                if (open) CaptureSheet(onDismiss = { open = false }, onCapture = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Call the plumber")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Call the plumber").assertExists()
    }

    @Test
    fun whatsNextSheet_keepsTypedText() {
        val now = 1_790_000_000_000L
        val f = EventFactory(Ids.newId(now), clock = { now })
        val quest = Ids.newId(now)
        val step = Ids.newId(now)
        val state = Fold.of(
            f.createAll(
                listOf(
                    Events.questCreated(QuestCreated(quest, "Fix the bike")),
                    Events.questStatusSet(QuestStatusSet(quest, QuestStatus.TRACKED, NextStep(step, quest, "Buy a tube"))),
                ),
            ),
            TimeZone.UTC,
        )
        val restore = StateRestorationTester(compose)
        restore.setContent {
            QuestTheme(ThemeMode.LIGHT) {
                WhatsNextSheet(state = state, stepId = step, onCancel = {}, onConfirm = { _, _ -> })
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Fit the tube")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Fit the tube").assertExists()
    }
}
