// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.lifecycleScope
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.ThemeMode
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.sheets.WhatsNextSheet
import dev.dreamweave.quest.ui.theme.QuestTheme
import android.widget.Toast
import kotlinx.coroutines.launch

/**
 * Tap-to-done from the widget: a translucent activity that shows the same "What's next?"
 * sheet as the app over the home screen, so widget completions keep their next step.
 */
class WidgetDoneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stepId = intent.getStringExtra(EXTRA_STEP_ID) ?: return finish()
        val c = (application as QuestApp).container
        setContent {
            QuestTheme(ThemeMode.SYSTEM) {
                val state by produceState<QuestState?>(null) { value = c.repository.current() }
                val s = state ?: return@QuestTheme
                WhatsNextSheet(
                    state = s,
                    stepId = stepId,
                    onCancel = { finish() },
                    onConfirm = { next, finishOwner ->
                        lifecycleScope.launch {
                            when (val r = c.actions.completeStepFromWidget(stepId, next, finishOwner)) {
                                is CommitResult.Ok -> {
                                    val xp = r.change.after.xp - r.change.before.xp
                                    Toast.makeText(this@WidgetDoneActivity, "+$xp XP", Toast.LENGTH_SHORT).show()
                                }
                                is CommitResult.Rejected ->
                                    Toast.makeText(this@WidgetDoneActivity, QuestViewModel.humanize(r.reason), Toast.LENGTH_LONG).show()
                            }
                            finish()
                        }
                    },
                )
            }
        }
    }

    companion object {
        const val EXTRA_STEP_ID = "dev.dreamweave.quest.extra.WIDGET_STEP_ID"
    }
}
