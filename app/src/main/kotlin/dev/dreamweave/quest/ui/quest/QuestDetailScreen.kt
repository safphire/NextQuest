// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.quest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.core.Completion
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.Step
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.ChipText
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.ProgressBar
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.components.SectionTitle
import dev.dreamweave.quest.ui.sheets.ConfirmDialog
import dev.dreamweave.quest.ui.sheets.DueDatePicker
import dev.dreamweave.quest.ui.sheets.SwapDialog
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import dev.dreamweave.quest.ui.sheets.rememberSavedDialog
import kotlinx.serialization.Serializable
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Practices
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.RhythmKind
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.PlexMono
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format
import androidx.compose.material.icons.rounded.SearchOff

/** Every prompt this screen can show, one at a time. */
@Serializable
internal sealed interface Prompt {
    @Serializable
    data object Rename : Prompt
    @Serializable
    data object Why : Prompt
    @Serializable
    data class SetStep(val questId: String) : Prompt
    @Serializable
    data class EditStep(val stepId: String, val text: String) : Prompt
    @Serializable
    data class Schedule(val stepId: String, val due: Long?, val alarm: Boolean) : Prompt
    @Serializable
    data object AddSubquest : Prompt
    /** Completing or dropping [questId] leaves [ownerId] without a step: ask for one. */
    @Serializable
    data class LeaveNeedsStep(val questId: String, val drop: Boolean, val ownerId: String) : Prompt
    @Serializable
    data class FocusNeedsStep(val childId: String?) : Prompt
    @Serializable
    data object TrackNeedsStep : Prompt
    @Serializable
    data class Swap(val firstStep: String?) : Prompt
    @Serializable
    data object ConfirmDrop : Prompt

    // v1.1
    @Serializable
    data class EditRhythm(val rhythm: Rhythm?) : Prompt

    @Serializable
    data object Obstacle : Prompt

    @Serializable
    data object Plan : Prompt

    @Serializable
    data class Cue(val stepId: String, val cue: String) : Prompt
}

@Composable
fun QuestDetailScreen(vm: QuestViewModel, state: QuestState, questId: String, actions: RootActions) {
    val c = Quest.colors
    val now by vm.now.collectAsStateWithLifecycle()
    var prompt by rememberSavedDialog(Prompt.serializer())
    var menu by remember { mutableStateOf(false) }
    var notesOpen by rememberSaveable { mutableStateOf(false) }
    val quest = state.quests[questId]

    if (quest == null) {
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.text) }
            EmptyState(Icons.Rounded.SearchOff, "Quest not found", "It may have been removed on another device.")
        }
        return
    }
    val isRoot = quest.isRoot
    val step: Step? = if (isRoot) state.effectiveStep(questId) else state.openStep(questId)
    val stepOwner = if (isRoot) state.effectiveStepOwner(questId) ?: quest else quest
    val children = remember(state, questId) { state.children(questId).filter { it.status != QuestStatus.DROPPED } }
    val progress = remember(state, questId) { Views.progress(state, questId) }
    val history = remember(state, questId) { state.historyOf(state.rootOf(questId) ?: questId, 10).reversed() }
    val open = quest.status.isOpen

    fun leaveChild(childId: String, drop: Boolean) {
        val plan = Completion.planLeave(state, childId)
        if (plan.nextRequired && plan.nextOwnerId != null) {
            prompt = Prompt.LeaveNeedsStep(childId, drop, plan.nextOwnerId!!)
        } else if (drop) {
            vm.run { drop(childId) }
        } else {
            vm.run { completeQuest(childId) }
        }
    }

    fun focusOn(childId: String?) {
        val target = childId ?: questId
        if (quest.status == QuestStatus.TRACKED && state.openStep(target) == null) {
            prompt = Prompt.FocusNeedsStep(childId)
        } else {
            vm.run { focus(questId, childId) }
        }
    }

    fun startTrack() {
        val hasStep = state.effectiveStep(questId) != null
        when {
            !hasStep -> prompt = Prompt.TrackNeedsStep
            state.freeSlots == 0 -> prompt = Prompt.Swap(null)
            else -> vm.run { track(questId) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.text) }
                Spacer(Modifier.weight(1f))
                MetaLabel(quest.status.name.lowercase(), secondary = quest.status != QuestStatus.TRACKED)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More actions", tint = c.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (isRoot && open && quest.status != QuestStatus.TRACKED) {
                            DropdownMenuItem(text = { Text("Track") }, onClick = { menu = false; startTrack() })
                        }
                        if (isRoot && open && quest.status != QuestStatus.LATER) {
                            DropdownMenuItem(text = { Text("Park for later") }, onClick = { menu = false; vm.run { park(questId) } })
                        }
                        if (isRoot && open && quest.status != QuestStatus.INBOX) {
                            DropdownMenuItem(text = { Text("Move to inbox") }, onClick = { menu = false; vm.run { toInbox(questId) } })
                        }
                        if (isRoot && open && children.none { it.status.isOpen }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (quest.isPractice) R.string.practice_change_rhythm else R.string.practice_make)) },
                                onClick = { menu = false; prompt = Prompt.EditRhythm(quest.rhythm ?: Rhythm(RhythmKind.DAILY)) },
                            )
                        }
                        if (open && !quest.isPractice) {
                            DropdownMenuItem(
                                text = { Text(if (isRoot) "Complete quest · +${Xp.QUEST} XP" else "Complete subquest · +${Xp.SUBQUEST} XP") },
                                onClick = {
                                    menu = false
                                    if (isRoot) vm.run { completeQuest(questId) } else leaveChild(questId, drop = false)
                                },
                            )
                        }
                        if (open) {
                            DropdownMenuItem(text = { Text("Drop") }, onClick = { menu = false; prompt = Prompt.ConfirmDrop })
                        }
                        if (quest.status == QuestStatus.DROPPED) {
                            DropdownMenuItem(text = { Text("Restore to inbox") }, onClick = { menu = false; vm.run { restore(questId) } })
                        }
                    }
                }
            }
        }
        item {
            if (!isRoot) {
                quest.parentId?.let { state.quests[it] }?.let { parent ->
                    Text(
                        "Subquest of ${parent.title}", style = MaterialTheme.typography.labelMedium, color = c.violet,
                        modifier = Modifier.clickable(role = Role.Button) { actions.openQuest(parent.id) }.heightIn(min = 48.dp).padding(vertical = 14.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    quest.title, style = MaterialTheme.typography.headlineSmall, color = c.text,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = { prompt = Prompt.Rename }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = c.textSecondary) }
            }
            Text(
                quest.why ?: "Why does this matter? (optional)",
                style = MaterialTheme.typography.bodyMedium,
                color = if (quest.why == null) c.textSecondary else c.text,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Edit why it matters") { prompt = Prompt.Why }.padding(vertical = 12.dp),
            )
            // Mental contrasting (C4): collapsed under "Why it matters" until opened or filled.
            if (isRoot) {
                val hasNotes = quest.obstacle != null || quest.plan != null
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.obstacle_section)) { notesOpen = !notesOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaLabel(stringResource(R.string.obstacle_section), secondary = !hasNotes, modifier = Modifier.weight(1f))
                    Icon(if (notesOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = c.textSecondary)
                }
                if (notesOpen) {
                    NoteLine(stringResource(R.string.obstacle_title), quest.obstacle, stringResource(R.string.obstacle_placeholder)) { prompt = Prompt.Obstacle }
                    NoteLine(stringResource(R.string.plan_title), quest.plan, stringResource(R.string.plan_placeholder)) { prompt = Prompt.Plan }
                }
            }
            quest.rhythm?.let { r ->
                val status = Practices.status(state, quest, Format.dateOf(now, vm.zone))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaLabel("${stringResource(R.string.practice_label)} · ${Practices.label(r)}", modifier = Modifier.weight(1f))
                    status?.let { ChipText(stringResource(R.string.practice_this_week, it.week.done, it.week.target), tinted = true) }
                }
                Text(stringResource(R.string.practice_never_complete), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            if (progress.total > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${progress.done}/${progress.total}", style = MonoNumber, color = c.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    ProgressBar(progress.fraction, Modifier.weight(1f))
                }
            }
        }
        if (open) {
            item {
                QuestCard(dashed = step == null && quest.status == QuestStatus.TRACKED) {
                    Column {
                        MetaLabel(if (stepOwner.id != questId) "Next · ${stepOwner.title}" else "Next")
                        Spacer(Modifier.height(4.dp))
                        if (step == null) {
                            Text("No next step yet.", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                            Spacer(Modifier.height(12.dp))
                            LimeButton("Set step", onClick = { prompt = Prompt.SetStep(stepOwner.id) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(
                                step.text, style = MaterialTheme.typography.bodyLarge, color = c.text,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(onClickLabel = "Edit step") { prompt = Prompt.EditStep(step.id, step.text) }
                                    .wrapContentHeight(Alignment.CenterVertically).padding(vertical = 4.dp),
                            )
                            Text(
                                step.cue ?: stringResource(R.string.cue_title),
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(onClickLabel = stringResource(R.string.cue_edit)) { prompt = Prompt.Cue(step.id, step.cue.orEmpty()) }
                                    .wrapContentHeight(Alignment.CenterVertically),
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.clickable(role = Role.Button, onClickLabel = "Set date and time") {
                                    prompt = Prompt.Schedule(step.id, step.dueAt, step.alarm)
                                }.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                    ChipText(step.dueAt?.let { Format.due(it, now, vm.zone) } ?: "Add date & time", icon = Icons.Rounded.Event)
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Rounded.Alarm, contentDescription = null, tint = c.textSecondary)
                                Spacer(Modifier.width(6.dp))
                                Text("Alarm", style = MaterialTheme.typography.bodyMedium, color = c.text)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = step.alarm,
                                    onCheckedChange = { on ->
                                        if (on && step.dueAt == null) prompt = Prompt.Schedule(step.id, null, true)
                                        else {
                                            if (on) actions.reminderSet()
                                            vm.run { schedule(step.id, step.dueAt, on) }
                                        }
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = c.violet, checkedThumbColor = c.onViolet),
                                    modifier = Modifier.semantics { contentDescription = "Alarm" },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            val focusLabel = stringResource(R.string.focus_button_a11y, step.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LimeButton(
                                    "Done · +${Xp.STEP} XP", onClick = { actions.done(step.id) }, large = true,
                                    modifier = Modifier.weight(1f),
                                )
                                QuietButton(
                                    stringResource(R.string.focus_button), onClick = { actions.focus(step.id, null) }, icon = Icons.Rounded.Timer,
                                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = focusLabel },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (open) {
            item {
                TextButton(onClick = { actions.askAi(questId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.violet)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ask_ai), color = c.text)
                }
            }
        }
        if (isRoot && !quest.isPractice) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Subquests", Modifier.weight(1f))
                    if (open) {
                        QuietButton("Add", onClick = { prompt = Prompt.AddSubquest }, icon = Icons.Rounded.Add)
                    }
                }
                if (children.isEmpty()) {
                    Text(
                        "Break a big quest into parts. The focused one supplies the next step.",
                        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                    )
                }
            }
            items(children, key = { it.id }) { child ->
                val focused = quest.focusedChildId == child.id
                val done = child.status == QuestStatus.DONE
                val childStep = state.openStep(child.id)
                var childMenu by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(onClickLabel = "Open subquest") { actions.openQuest(child.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { if (!done && open) leaveChild(child.id, drop = false) },
                        enabled = !done && open,
                        modifier = Modifier.semantics { stateDescription = if (done) "Done" else "Not done" },
                    ) {
                        Icon(
                            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (done) "Completed" else "Complete “${child.title}”",
                            tint = if (done) c.violet else c.textSecondary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                child.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (done) c.textSecondary else c.text,
                                textDecoration = if (done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (focused) {
                                Spacer(Modifier.width(8.dp))
                                MetaLabel("Focused")
                            }
                        }
                        if (childStep != null && !done) {
                            Text("→ ${childStep.text}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                    if (!done && open) {
                        Box {
                            IconButton(onClick = { childMenu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Subquest actions", tint = c.textSecondary) }
                            DropdownMenu(expanded = childMenu, onDismissRequest = { childMenu = false }) {
                                if (!focused) DropdownMenuItem(text = { Text("Focus") }, onClick = { childMenu = false; focusOn(child.id) })
                                if (focused) DropdownMenuItem(text = { Text("Unfocus") }, onClick = { childMenu = false; focusOn(null) })
                                DropdownMenuItem(text = { Text(if (childStep == null) "Set step" else "Change step") }, onClick = { childMenu = false; prompt = Prompt.SetStep(child.id) })
                                DropdownMenuItem(text = { Text("Drop") }, onClick = { childMenu = false; leaveChild(child.id, drop = true) })
                            }
                        }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle("History") }
            items(history, key = { it.eventId + it.text }) { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(Format.historyStamp(h.ts, vm.zone), style = MaterialTheme.typography.labelSmall.copy(fontFamily = PlexMono), color = c.textSecondary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        h.text + if (h.xp > 0) "  +${h.xp} XP" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (h.xp > 0 || h.text.startsWith("Accepted")) c.violet else c.text,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    when (val p = prompt) {
        null -> Unit
        Prompt.Rename -> TextPromptDialog(
            title = "Rename", confirmLabel = "Save", initial = quest.title,
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { edit(questId, title = t) } },
        )
        Prompt.Why -> TextPromptDialog(
            title = "Why it matters", confirmLabel = "Save", initial = quest.why.orEmpty(), allowEmpty = true,
            placeholder = "One line you'll want to read on a hard day",
            onDismiss = { prompt = null },
            onConfirm = { t -> prompt = null; vm.run { edit(questId, why = t, clearWhy = t.isBlank()) } },
        )
        is Prompt.SetStep -> TextPromptDialog(
            title = "Next step", confirmLabel = "Set step",
            message = state.quests[p.questId]?.let { "For “${it.title}”" },
            placeholder = "The very next physical action",
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { setStep(p.questId, t) } },
        )
        is Prompt.EditStep -> TextPromptDialog(
            title = "Edit step", confirmLabel = "Save", initial = p.text,
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { editStepText(p.stepId, t) } },
        )
        is Prompt.Schedule -> DueDatePicker(
            initial = p.due, now = now, zone = vm.zone,
            onDismiss = { prompt = null },
            onPicked = { at ->
                prompt = null
                actions.reminderSet()
                vm.run { schedule(p.stepId, at, p.alarm) }
            },
            onClear = { prompt = null; vm.run { schedule(p.stepId, null, false) } },
        )
        Prompt.AddSubquest -> TextPromptDialog(
            title = "Add subquest", confirmLabel = "Add", placeholder = "A part of “${quest.title}”",
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { addSubquest(questId, t) } },
        )
        is Prompt.LeaveNeedsStep -> TextPromptDialog(
            title = "What's next?",
            message = "“${state.quests[p.ownerId]?.title}” is up next. What's its first step?",
            confirmLabel = if (p.drop) "Drop" else "Complete",
            onDismiss = { prompt = null },
            onConfirm = { t ->
                prompt = null
                if (p.drop) vm.run { drop(p.questId, t, p.ownerId) } else vm.run { completeQuest(p.questId, t, p.ownerId) }
            },
        )
        is Prompt.FocusNeedsStep -> TextPromptDialog(
            title = "First step",
            message = "“${state.quests[p.childId ?: questId]?.title}” will supply the next step. What is it?",
            confirmLabel = "Focus",
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { focus(questId, p.childId, t) } },
        )
        Prompt.TrackNeedsStep -> TextPromptDialog(
            title = "First step",
            message = "A tracked quest always shows its next step. What's the first one?",
            confirmLabel = if (state.freeSlots == 0) "Next" else "Track",
            onDismiss = { prompt = null },
            onConfirm = { t ->
                if (state.freeSlots == 0) {
                    prompt = Prompt.Swap(t)
                } else {
                    prompt = null
                    vm.run { track(questId, firstStep = t) }
                }
            },
        )
        is Prompt.Swap -> SwapDialog(
            tracked = state.tracked, cap = state.slotCap, onDismiss = { prompt = null },
            onPark = { parkId -> prompt = null; vm.run { track(questId, firstStep = p.firstStep, parkQuestId = parkId) } },
        )
        is Prompt.EditRhythm -> RhythmDialog(
            initial = p.rhythm ?: Rhythm(RhythmKind.DAILY),
            isPractice = quest.isPractice,
            onDismiss = { prompt = null },
            onSave = { r ->
                prompt = null
                if (r.reminderMinute != null) actions.reminderSet()
                vm.run { setRhythm(questId, r) }
            },
            onMakeQuest = {
                prompt = null
                vm.run { setRhythm(questId, null) }
            },
        )
        Prompt.Obstacle -> TextPromptDialog(
            title = stringResource(R.string.obstacle_title), confirmLabel = "Save", initial = quest.obstacle.orEmpty(), allowEmpty = true,
            placeholder = stringResource(R.string.obstacle_placeholder),
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { editObstacle(questId, obstacle = t) } },
        )
        Prompt.Plan -> TextPromptDialog(
            title = stringResource(R.string.plan_title), confirmLabel = "Save", initial = quest.plan.orEmpty(), allowEmpty = true,
            placeholder = stringResource(R.string.plan_placeholder),
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { editObstacle(questId, plan = t) } },
        )
        is Prompt.Cue -> TextPromptDialog(
            title = stringResource(R.string.cue_title), confirmLabel = "Save", initial = p.cue, allowEmpty = true,
            placeholder = stringResource(R.string.cue_placeholder),
            onDismiss = { prompt = null }, onConfirm = { t -> prompt = null; vm.run { setCue(p.stepId, t) } },
        )
        Prompt.ConfirmDrop -> ConfirmDialog(
            title = "Drop “${quest.title}”?",
            body = "It leaves your lists. Your XP stays, and you can restore it from this screen.",
            confirmLabel = "Drop",
            onDismiss = { prompt = null },
            onConfirm = {
                prompt = null
                if (isRoot) vm.run(onOk = actions.back) { drop(questId) } else leaveChild(questId, drop = true)
            },
        )
    }
}

/** One line of the obstacle-and-plan section: its label, the text (or a hint), tap to edit. */
@Composable
private fun NoteLine(label: String, value: String?, hint: String, onEdit: () -> Unit) {
    val c = Quest.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = label, onClick = onEdit).padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.violet)
        Text(value ?: hint, style = MaterialTheme.typography.bodyMedium, color = if (value == null) c.textSecondary else c.text)
    }
}
