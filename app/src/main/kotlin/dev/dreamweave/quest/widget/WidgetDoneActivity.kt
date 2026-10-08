// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.data.ThemeMode
import dev.dreamweave.quest.presence.ReminderPlan
import dev.dreamweave.quest.ui.sheets.WhatsNextSheet
import dev.dreamweave.quest.ui.theme.QuestTheme
import dev.dreamweave.quest.util.ActionOutcome
import dev.dreamweave.quest.util.Calm
import kotlinx.coroutines.launch

/**
 * Tap-to-done from the widget: a translucent activity that shows the same "What's next?"
 * sheet as the app over the home screen, so widget completions keep their next step.
 */
class WidgetDoneActivity : ComponentActivity() {
    /** Set once the user confirmed, so the state change that follows doesn't read as "stale". */
    private var committing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stepId = intent.getStringExtra(EXTRA_STEP_ID) ?: return finish()
        val c = (application as QuestApp).container
        setContent {
            QuestTheme(ThemeMode.SYSTEM) {
                LaunchedEffect(Unit) {
                    if (Calm.attempt(TAG, "loading the log") { c.repository.load() } == null) {
                        Toast.makeText(this@WidgetDoneActivity, Calm.MESSAGE, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                val state by c.repository.state.collectAsState()
                val s = state ?: return@QuestTheme
                if (!committing && !ReminderPlan.isOpenStep(s, stepId)) {
                    // The widget was showing an old step (done or replaced elsewhere): just close.
                    LaunchedEffect(stepId) {
                        Toast.makeText(this@WidgetDoneActivity, "That step has already moved on.", Toast.LENGTH_SHORT).show()
                        c.appScope.launch { c.presence.refresh() }
                        finish()
                    }
                    return@QuestTheme
                }
                WhatsNextSheet(
                    state = s,
                    stepId = stepId,
                    onCancel = { finish() },
                    onConfirm = { next, finishOwner ->
                        if (!committing) {
                            committing = true
                            lifecycleScope.launch {
                                val r = Calm.commit(TAG, "widget Done") { c.actions.completeStepFromWidget(stepId, next, finishOwner) }
                                val text = when (r) {
                                    is ActionOutcome.Done -> "+${r.change.after.xp - r.change.before.xp} XP"
                                    is ActionOutcome.Refused -> r.message
                                    is ActionOutcome.Failed -> r.message
                                }
                                Toast.makeText(this@WidgetDoneActivity, text, if (r is ActionOutcome.Done) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_STEP_ID = "dev.dreamweave.quest.extra.WIDGET_STEP_ID"
        private const val TAG = "QuestWidget"
    }
}
