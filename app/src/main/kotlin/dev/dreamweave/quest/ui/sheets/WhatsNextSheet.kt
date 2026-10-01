// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.core.Completion
import dev.dreamweave.quest.core.Limits
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.theme.Quest

/**
 * "What's next?" — shown right after Done. A tracked quest must leave with a new next
 * step (principle 4) unless the quest itself is finished. Back and outside taps do nothing;
 * "Not yet" keeps the step open instead.
 */
@Composable
fun WhatsNextSheet(
    state: QuestState,
    stepId: String,
    onCancel: () -> Unit,
    onConfirm: (nextStep: String?, finishOwner: Boolean) -> Unit,
) {
    val c = Quest.colors
    var finishingSaved by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    if (state.steps[stepId] == null) {
        LaunchedEffect(stepId) { onCancel() }
        return
    }
    val finishPlan = remember(state, stepId) { Completion.plan(state, stepId, true) }
    // "Finish the subquest, then name the next step" only applies to subquests; a restored flag
    // whose step now belongs to a top-level quest is ignored (that one completes directly).
    val finishing = finishingSaved && !finishPlan.ownerIsRoot
    val plan = remember(state, stepId, finishing) { Completion.plan(state, stepId, finishing) }
    val stepText = state.steps.getValue(stepId).text
    // nextOwnerId is null only when finishing a top-level quest, which this panel completes directly.
    val nextOwner = state.quests.getValue(plan.nextOwnerId ?: plan.rootId)
    val canSave = text.isNotBlank() || !plan.nextRequired

    var closing by remember { mutableStateOf(false) }
    // Each action runs once, even if a second tap lands before the panel leaves composition.
    fun closeThen(action: () -> Unit) {
        if (closing) return
        closing = true
        action()
    }

    fun confirm() {
        if (canSave) {
            val next = text.trim().ifEmpty { null }
            val finish = finishing
            closeThen { onConfirm(next, finish) }
        }
    }

    BottomPanel(title = "What's next?", onDismissRequest = {}, dismissible = false) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            MetaLabel("Done · +${Xp.STEP} XP")
            Text(stepText, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Text("What's next?", style = MaterialTheme.typography.titleLarge, color = c.text)
            if (nextOwner.id != plan.rootId || finishing) {
                Text("Next step for “${nextOwner.title}”", style = MaterialTheme.typography.bodyMedium, color = c.violet)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "").take(Limits.MAX_TEXT) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("The very next physical action") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
            Spacer(Modifier.height(12.dp))
            LimeButton(
                if (plan.nextRequired || text.isNotBlank()) "Set next step" else "Done",
                icon = Icons.Rounded.Check, large = true, enabled = canSave,
                onClick = { confirm() }, modifier = Modifier.fillMaxWidth(),
            )
            if (!finishing && finishPlan.canFinishOwner) {
                Spacer(Modifier.height(8.dp))
                QuietButton(
                    if (finishPlan.ownerIsRoot) "Complete quest · +${Xp.QUEST} XP" else "Complete subquest · +${Xp.SUBQUEST} XP",
                    onClick = {
                        if (finishPlan.ownerIsRoot || !finishPlan.nextRequired) {
                            closeThen { onConfirm(null, true) }
                        } else {
                            finishingSaved = true
                            text = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = { closeThen(onCancel) }, modifier = Modifier.fillMaxWidth()) { Text("Not yet — keep the step open") }
            Spacer(Modifier.height(12.dp))
        }
        LaunchedEffect(finishing) { runCatching { focus.requestFocus() } }
    }
}
