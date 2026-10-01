// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Completion
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.ThemeMode
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.ui.theme.QuestTheme
import kotlinx.coroutines.launch

/**
 * Full-screen alarm over the lock screen. It is the app's own UI, so Done here asks for
 * the next step like the in-app flow (only a notification Done may skip it).
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val stepId = intent.getStringExtra(ReminderReceiver.EXTRA_STEP_ID) ?: return finish()
        val c = (application as QuestApp).container

        fun close() {
            Notifications.cancelStep(this, stepId)
            finish()
        }

        setContent {
            QuestTheme(ThemeMode.SYSTEM) {
                val state by produceState<QuestState?>(null) { value = c.repository.current() }
                val s = state
                val step = s?.steps?.get(stepId)
                val quest = step?.let { s.quests[it.questId] }
                var askingNext by rememberSaveable { mutableStateOf(false) }
                var next by rememberSaveable { mutableStateOf("") }
                val colors = Quest.colors
                Column(
                    Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Alarm, contentDescription = null, tint = colors.violet)
                    Spacer(Modifier.height(12.dp))
                    MetaLabel(quest?.title ?: "Step")
                    Spacer(Modifier.height(8.dp))
                    Text(step?.text ?: "", style = MaterialTheme.typography.headlineMedium, color = colors.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(32.dp))
                    if (s != null && step != null && !step.isDone) {
                        val plan = Completion.plan(s, stepId, false)
                        if (!askingNext) {
                            LimeButton("Done · +${Xp.STEP} XP", large = true, modifier = Modifier.fillMaxWidth(), onClick = {
                                if (plan.nextRequired) {
                                    askingNext = true
                                } else {
                                    lifecycleScope.launch { c.actions.completeStep(stepId, null, false); close() }
                                }
                            })
                            Spacer(Modifier.height(8.dp))
                            QuietButton("Snooze 1h", modifier = Modifier.fillMaxWidth(), onClick = {
                                lifecycleScope.launch { c.actions.snooze(stepId, System.currentTimeMillis() + ReminderReceiver.SNOOZE_MS); close() }
                            })
                            TextButton(onClick = { lifecycleScope.launch { c.actions.dismissReminder(stepId); close() } }) { Text("Dismiss") }
                        } else {
                            Text("What's next?", style = MaterialTheme.typography.titleLarge, color = colors.text)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = next, onValueChange = { next = it.replace("\n", "") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(), placeholder = { Text("The very next action") },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {}),
                            )
                            Spacer(Modifier.height(12.dp))
                            LimeButton("Set next step", enabled = next.isNotBlank(), large = true, modifier = Modifier.fillMaxWidth(), onClick = {
                                lifecycleScope.launch {
                                    if (c.actions.completeStep(stepId, next, false) is CommitResult.Ok) close()
                                }
                            })
                            if (plan.canFinishOwner && plan.ownerIsRoot) {
                                QuietButton("Complete quest · +${Xp.QUEST} XP", modifier = Modifier.fillMaxWidth(), onClick = {
                                    lifecycleScope.launch { c.actions.completeStep(stepId, null, true); close() }
                                })
                            }
                        }
                    } else if (s != null) {
                        LimeButton("Close", onClick = { close() })
                    }
                }
            }
        }
    }
}
