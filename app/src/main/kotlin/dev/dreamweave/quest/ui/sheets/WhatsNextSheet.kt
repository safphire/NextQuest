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
import dev.dreamweave.quest.R
import dev.dreamweave.quest.util.Format
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.res.stringResource
import kotlinx.datetime.TimeZone

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
    /** [dueAt] and [cue] come from "When/where?" (C3) and only apply to a new next step. */
    onConfirm: (nextStep: String?, finishOwner: Boolean, dueAt: Long?, cue: String?) -> Unit,
    now: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val c = Quest.colors
    var finishingSaved by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var cue by rememberSaveable { mutableStateOf("") }
    var cueOpen by rememberSaveable { mutableStateOf(false) }
    var dueMinute by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
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
    val afterPrefix = stringResource(R.string.cue_after_prefix)
    val atPrefix = stringResource(R.string.cue_at_prefix)
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
            // A time cue is today at that time (tomorrow if it has already passed, D-1118).
            // "Now" is when the step is saved, not when the sheet opened.
            val due = if (next == null) null else dueMinute?.let { WhenWhere.dueAt(System.currentTimeMillis(), it, zone) }
            val cueText = if (next == null) null else WhenWhere.cleanCue(cue, afterPrefix, atPrefix)
            closeThen { onConfirm(next, finish, due, cueText) }
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
            Spacer(Modifier.height(8.dp))
            WhenWhereRow(
                cue = cue, cueOpen = cueOpen, dueMinute = dueMinute,
                onCue = { cue = it.replace("\n", "").take(Limits.MAX_CUE) },
                onOpenCue = { prefix ->
                    cueOpen = true
                    if (cue.isBlank()) cue = prefix
                },
                onPickTime = { pickingTime = true },
                onClearTime = { dueMinute = null },
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
                            closeThen { onConfirm(null, true, null, null) }
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
    if (pickingTime) {
        TimeOfDayDialog(
            title = stringResource(R.string.cue_time), minutes = dueMinute ?: (9 * 60),
            onDismiss = { pickingTime = false },
            onPicked = {
                dueMinute = it
                pickingTime = false
            },
        )
    }
}

/** When/where cues (C3): pure helpers, shared by the sheet and its tests. */
object WhenWhere {
    /** Today at [minuteOfDay]; tomorrow when that time has already passed. */
    fun dueAt(now: Long, minuteOfDay: Int, zone: TimeZone): Long {
        val today = Format.epochOf(Format.dateOf(now, zone), minuteOfDay, zone)
        return if (today > now) today else Format.tomorrowAt(now, minuteOfDay, zone)
    }

    /** The typed cue, or null when nothing was added after a chip's "after " / "at ". */
    fun cleanCue(cue: String, vararg prefixes: String): String? {
        val t = cue.trim()
        return t.takeIf { it.isNotEmpty() && prefixes.none { p -> p.trim().equals(t, ignoreCase = true) } }
    }
}

/** "When/where?": a time chip (sets today's due time), "after ___" and "at ___" chips, and a free-text cue. */
@Composable
fun WhenWhereRow(
    cue: String,
    cueOpen: Boolean,
    dueMinute: Int?,
    onCue: (String) -> Unit,
    onOpenCue: (prefix: String) -> Unit,
    onPickTime: () -> Unit,
    onClearTime: () -> Unit,
) {
    val c = Quest.colors
    val after = stringResource(R.string.cue_after_prefix)
    val at = stringResource(R.string.cue_at_prefix)
    Text(stringResource(R.string.cue_title), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = dueMinute != null,
            onClick = { if (dueMinute != null) onClearTime() else onPickTime() },
            label = { Text(dueMinute?.let { Format.minutesOfDay(it) } ?: stringResource(R.string.cue_time)) },
            modifier = Modifier.heightIn(min = 48.dp),
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.violetTint, selectedLabelColor = c.violet),
        )
        AssistChip(onClick = { onOpenCue(after) }, label = { Text(stringResource(R.string.cue_after)) }, modifier = Modifier.heightIn(min = 48.dp))
        AssistChip(onClick = { onOpenCue(at) }, label = { Text(stringResource(R.string.cue_at)) }, modifier = Modifier.heightIn(min = 48.dp))
    }
    if (cueOpen || cue.isNotBlank()) {
        OutlinedTextField(
            value = cue,
            onValueChange = onCue,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.cue_placeholder)) },
            label = { Text(stringResource(R.string.cue_title)) },
            singleLine = true,
        )
    }
}
