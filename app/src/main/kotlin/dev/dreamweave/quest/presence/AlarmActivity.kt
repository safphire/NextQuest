// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.presence

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.dreamweave.quest.AppContainer
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Completion
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.util.ActionOutcome
import dev.dreamweave.quest.util.Calm
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
    /** The step on screen. singleInstance: a newer alarm arrives through [onNewIntent]. */
    private var shownStepId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        shownStepId = intent.getStringExtra(ReminderReceiver.EXTRA_STEP_ID) ?: return finish()
        val c = (application as QuestApp).container
        // Back stops the ringing but keeps the reminder: a silent notification with
        // Done / Snooze / Skip stays in the shade.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    shownStepId?.let { quiet(c, it) } ?: finish()
                }
            },
        )

        setContent {
            QuestTheme(ThemeMode.SYSTEM) {
                val id = shownStepId
                // key(): a new step starts with fresh "What's next?" input.
                if (id != null) key(id) { AlarmScreen(c, id) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(ReminderReceiver.EXTRA_STEP_ID)?.let { shownStepId = it }
    }

    /** Silences the alarm and leaves a normal, silent reminder for the step (if it is still open). */
    internal fun quiet(c: AppContainer, stepId: String) {
        val state = c.repository.state.value
        val step = state?.steps?.get(stepId)
        val quest = step?.let { state.quests[it.questId] }
        if (state != null && step != null && quest != null && ReminderPlan.isOpenStep(state, stepId)) {
            runCatching { Notifications.quietStep(this, quest, step) }.onFailure { Notifications.cancelStep(this, stepId) }
        } else {
            Notifications.cancelStep(this, stepId)
        }
        finish()
    }

    private fun close(stepId: String) {
        Notifications.cancelStep(this, stepId)
        finish()
    }

    /**
     * Shown over the lock screen: completing a step is the user's own action, so it waits
     * until the keyguard is dismissed (snooze and dismiss don't need an unlock, like any alarm).
     */
    private fun whenUnlocked(action: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            action()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    action()
                }
            },
        )
    }

    @Composable
    private fun AlarmScreen(c: AppContainer, stepId: String) {
        LaunchedEffect(Unit) { Calm.attempt(TAG, "loading the log") { c.repository.load() } }
        val state by c.repository.state.collectAsState()
        val s = state
        val step = s?.steps?.get(stepId)
        val quest = if (s != null && step != null) s.quests[step.questId] else null
        var askingNext by rememberSaveable { mutableStateOf(false) }
        var next by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        val colors = Quest.colors

        fun complete(nextText: String?, finishOwner: Boolean) {
            if (busy) return
            whenUnlocked {
                busy = true
                error = null
                lifecycleScope.launch {
                    when (val r = Calm.commit(TAG, "alarm Done") { c.actions.completeStep(stepId, nextText, finishOwner) }) {
                        is ActionOutcome.Done -> close(stepId)
                        is ActionOutcome.Refused -> {
                            error = r.message
                            busy = false
                        }
                        is ActionOutcome.Failed -> {
                            error = r.message
                            busy = false
                        }
                    }
                }
            }
        }

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
            if (s != null && ReminderPlan.isOpenStep(s, stepId)) {
                val plan = Completion.plan(s, stepId, false)
                if (!askingNext) {
                    LimeButton("Done · +${Xp.STEP} XP", large = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        if (plan.nextRequired) {
                            askingNext = true
                        } else {
                            complete(null, false)
                        }
                    })
                    Spacer(Modifier.height(8.dp))
                    QuietButton("Snooze 1h", modifier = Modifier.fillMaxWidth(), onClick = {
                        lifecycleScope.launch {
                            val r = Calm.commit(TAG, "alarm Snooze") { c.actions.snooze(stepId, System.currentTimeMillis() + ReminderReceiver.SNOOZE_MS) }
                            if (r is ActionOutcome.Failed) error = r.message else close(stepId)
                        }
                    })
                    TextButton(onClick = {
                        lifecycleScope.launch {
                            val r = Calm.commit(TAG, "alarm Dismiss") { c.actions.dismissReminder(stepId) }
                            if (r is ActionOutcome.Failed) error = r.message else close(stepId)
                        }
                    }) { Text("Dismiss") }
                } else {
                    Text("What's next?", style = MaterialTheme.typography.titleLarge, color = colors.text)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = next,
                        onValueChange = {
                            next = it.replace("\n", "")
                            error = null
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(), placeholder = { Text("The very next action") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (next.isNotBlank()) complete(next, false) }),
                    )
                    Spacer(Modifier.height(12.dp))
                    LimeButton("Set next step", enabled = next.isNotBlank() && !busy, large = true, modifier = Modifier.fillMaxWidth(), onClick = {
                        complete(next, false)
                    })
                    if (plan.canFinishOwner && plan.ownerIsRoot) {
                        QuietButton("Complete quest · +${Xp.QUEST} XP", enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                            complete(null, true)
                        })
                    }
                    TextButton(onClick = {
                        askingNext = false
                        error = null
                    }) { Text("Cancel") }
                }
                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
            } else if (s != null) {
                // Done, replaced or removed elsewhere: nothing left to act on.
                LimeButton("Close", onClick = { close(stepId) })
            }
        }
    }

    companion object {
        private const val TAG = "QuestAlarm"
    }
}
