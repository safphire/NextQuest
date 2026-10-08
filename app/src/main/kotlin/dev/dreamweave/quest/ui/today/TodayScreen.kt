// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.today

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Practices
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Returning
import dev.dreamweave.quest.core.TodayCard
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.core.Xp
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.ChipText
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.LevelBadge
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.ProgressBar
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.components.SlotPips
import dev.dreamweave.quest.ui.components.StreakChip
import dev.dreamweave.quest.ui.reset.ResetMode
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import kotlinx.datetime.LocalDate
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

@Composable
fun TodayScreen(vm: QuestViewModel, state: QuestState, settings: AppSettings, actions: RootActions) {
    val now by vm.now.collectAsStateWithLifecycle()
    val view = remember(state, now, settings.stuckDays) { Views.today(state, now, vm.zone, settings.stuckDays) }
    val c = Quest.colors
    var settingStepFor by rememberSaveable { mutableStateOf<String?>(null) }
    var stuckFor by rememberSaveable { mutableStateOf<String?>(null) }
    val today = remember(now) { Format.dateOf(now, vm.zone) }
    val todayIso = today.toString()

    // The first open of a day decides whether a fresh start is on offer (C2).
    LaunchedEffect(todayIso) {
        if (settings.lastOpenDay != todayIso) {
            val reason = Returning.freshStartOffer(
                lastOpenDay = settings.lastOpenDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                today = today,
                daysAway = Views.daysAway(state, now, vm.zone),
                pausedUntil = settings.freshStartPausedUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
            vm.updateSettings {
                it.copy(
                    lastOpenDay = todayIso,
                    freshStartOfferDay = if (reason != null) todayIso else null,
                    freshStartReason = reason?.name,
                )
            }
        }
    }
    val offer = settings.freshStartReason?.takeIf { settings.freshStartOfferDay == todayIso }
        ?.let { runCatching { Returning.FreshStartReason.valueOf(it) }.getOrNull() }
    val away = view.resumeAfterDays != null && settings.resumeDismissedAt < (state.lastUserActivityAt ?: 0)
    val showResume = offer != null || away
    val showForgiveness = !showResume &&
        Returning.forgivenessDue(state, now, vm.zone, settings.forgivenessDismissedOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() })

    fun dismissResume() {
        vm.updateSettings {
            if (offer != null) {
                val (n, pause) = Returning.afterDismissal(it.freshStartDismissals, today)
                it.copy(resumeDismissedAt = now, freshStartOfferDay = null, freshStartDismissals = n, freshStartPausedUntil = pause?.toString() ?: it.freshStartPausedUntil)
            } else {
                it.copy(resumeDismissedAt = now)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(view.level.level, onClick = actions.openSettings)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Level ${view.level.level}", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${view.level.xpIntoLevel}/${view.level.xpForNextLevel} XP",
                            style = MonoNumber, color = c.violet,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(view.level.progress, Modifier.semantics {
                        contentDescription = "${view.level.xpIntoLevel} of ${view.level.xpForNextLevel} XP to level ${view.level.level + 1}"
                    })
                }
                Spacer(Modifier.width(10.dp))
                StreakChip(view.streak.current)
                IconButton(onClick = actions.openProposals) {
                    BadgedBox(badge = {
                        if (view.pendingProposals > 0) Badge(containerColor = c.violet, contentColor = c.onViolet) { Text("${view.pendingProposals}") }
                    }) {
                        Icon(
                            Icons.Rounded.AutoAwesome,
                            contentDescription = if (view.pendingProposals > 0) "${view.pendingProposals} proposals waiting" else "Proposals",
                            tint = c.text,
                        )
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Today", style = MaterialTheme.typography.headlineMedium, color = c.text,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                val resetLabel = stringResource(R.string.reset_button_a11y)
                TextButton(
                    onClick = { actions.reset(ResetMode.MENU, null) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = resetLabel },
                ) {
                    Icon(Icons.Rounded.SelfImprovement, contentDescription = null, tint = c.violet)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reset_button), color = c.text, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(4.dp))
                SlotPips(view.slotsUsed, view.slotCap)
            }
        }
        if (showResume) {
            item {
                ResumeCard(
                    days = view.resumeAfterDays,
                    reason = offer,
                    onPickUp = { dismissResume() },
                    onFreshStart = { actions.reset(ResetMode.FRESH, null) },
                )
            }
        }
        if (showForgiveness) {
            item {
                val lines = stringArrayResource(R.array.forgiveness_lines)
                ForgivenessCard(
                    line = lines[Returning.variant(today, lines.size)],
                    onSetStep = {
                        if (view.cards.any { it.step != null }) actions.reset(ResetMode.SHRINK, null) else actions.openBoard()
                    },
                    onDismiss = { vm.updateSettings { it.copy(forgivenessDismissedOn = todayIso) } },
                )
            }
        }
        if (view.cards.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Rounded.Flag,
                    title = "Nothing tracked yet",
                    body = "Capture anything with +. When you're ready, track up to ${view.slotCap} quests from the Board — each shows just its next step here.",
                    action = { LimeButton("Open Board", onClick = actions.openBoard) },
                )
            }
        }
        items(view.cards, key = { it.quest.id }) { card ->
            if (card.needsNextStep) {
                NeedsStepCard(card, onSetStep = { settingStepFor = card.stepOwner.id }, onOpen = { actions.openQuest(card.quest.id) })
            } else {
                TrackedCard(
                    card, now, vm,
                    onOpen = { actions.openQuest(card.quest.id) },
                    onDone = { card.step?.let { actions.done(it.id) } },
                    onFocus = { card.step?.let { actions.focus(it.id, null) } },
                    onStuck = { stuckFor = card.quest.id },
                )
            }
        }
    }

    settingStepFor?.let { questId ->
        val title = state.quests[questId]?.title.orEmpty()
        TextPromptDialog(
            title = "Next step",
            message = "What's the very next action for “$title”?",
            confirmLabel = "Set step",
            placeholder = "e.g. Email Sam about the venue",
            onDismiss = { settingStepFor = null },
            onConfirm = { text ->
                settingStepFor = null
                vm.run { setStep(questId, text) }
            },
        )
    }

    stuckFor?.let { questId ->
        val quest = state.quests[questId]
        val step = state.effectiveStep(questId)
        if (quest == null || step == null) {
            LaunchedEffect(questId) { stuckFor = null }
        } else {
            StuckSheet(
                quest = quest, step = step,
                onDismiss = { stuckFor = null },
                onShrink = {
                    stuckFor = null
                    actions.reset(ResetMode.SHRINK, questId)
                },
                onFocus10 = {
                    stuckFor = null
                    actions.focus(step.id, 10)
                },
                onPark = {
                    stuckFor = null
                    vm.run(onOk = { vm.message("Parked “${quest.title}” for later. It's on the Board when you want it.") }) { park(questId) }
                },
                onAskAi = {
                    stuckFor = null
                    actions.askAi(questId)
                },
            )
        }
    }
}

@Composable
private fun TrackedCard(
    card: TodayCard,
    now: Long,
    vm: QuestViewModel,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onFocus: () -> Unit,
    onStuck: () -> Unit,
) {
    val c = Quest.colors
    val step = card.step ?: return
    val practice = card.practice
    QuestCard(onClick = onOpen, onClickLabel = "Open quest") {
        Column {
            Text(card.quest.title, style = MaterialTheme.typography.titleMedium, color = c.text)
            if (practice != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetaLabel("${stringResource(R.string.practice_label)} · ${Practices.label(practice.rhythm)}", modifier = Modifier.weight(1f))
                    // Informational only: missed sessions never pile up.
                    ChipText(stringResource(R.string.practice_this_week, practice.week.done, practice.week.target), tinted = true)
                }
            }
            if (card.progress.total > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${card.progress.done}/${card.progress.total}", style = MonoNumber, color = c.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    ProgressBar(card.progress.fraction, Modifier.weight(1f).semantics {
                        contentDescription = "${card.progress.done} of ${card.progress.total} subquests done"
                    })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaLabel(if (card.stepOwner.id != card.quest.id) "Next · ${card.stepOwner.title}" else "Next", modifier = Modifier.weight(1f))
                if (card.stuck) {
                    val stuckLabel = stringResource(R.string.stuck_chip_a11y)
                    AssistChip(
                        onClick = onStuck,
                        label = { Text(stringResource(R.string.stuck_chip)) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = stuckLabel },
                        colors = AssistChipDefaults.assistChipColors(labelColor = c.textSecondary),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(step.text, style = MaterialTheme.typography.bodyLarge, color = c.text)
            step.cue?.let { cue ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, contentDescription = null, tint = c.textSecondary, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(cue, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
            step.dueAt?.let { due ->
                Spacer(Modifier.height(8.dp))
                ChipText(Format.due(due, now, vm.zone), icon = Icons.Rounded.Schedule)
            }
            Spacer(Modifier.height(14.dp))
            if (practice != null && !practice.dueToday) {
                // Resting until the next session day: calm, nothing to do.
                val next = stringResource(R.string.practice_next_session, sessionDay(practice.nextSessionDay, Format.dateOf(now, vm.zone)))
                Text(
                    if (practice.doneToday) stringResource(R.string.practice_done_today) + " " + next else next,
                    style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                )
            } else {
                val focusLabel = stringResource(R.string.focus_button_a11y, step.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimeButton(
                        "Done · +${Xp.STEP} XP", onClick = onDone, icon = Icons.Rounded.Check, large = true,
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton(
                        stringResource(R.string.focus_button), onClick = onFocus, icon = Icons.Rounded.Timer,
                        modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = focusLabel },
                    )
                }
            }
        }
    }
}

/** "Tomorrow" or "Thu 12 Mar" for a practice's next session. */
private fun sessionDay(day: LocalDate, today: LocalDate): String =
    if (day.toEpochDays() - today.toEpochDays() == 1) "tomorrow" else Format.shortDate(day)

@Composable
private fun NeedsStepCard(card: TodayCard, onSetStep: () -> Unit, onOpen: () -> Unit) {
    val c = Quest.colors
    QuestCard(onClick = onOpen, dashed = true, onClickLabel = "Open quest") {
        Column {
            Text(card.quest.title, style = MaterialTheme.typography.titleMedium, color = c.text)
            Spacer(Modifier.height(6.dp))
            MetaLabel("Needs next step")
            Spacer(Modifier.height(4.dp))
            Text(
                "Nice work. What's the next small action?",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            LimeButton("Set step", onClick = onSetStep, icon = Icons.Rounded.EditNote, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Re-entry without guilt (C2): after time away, or on the first open of a new week or month,
 * offer a clean slate next to "pick up where you left off". No backlog, no red.
 */
@Composable
private fun ResumeCard(days: Int?, reason: Returning.FreshStartReason?, onPickUp: () -> Unit, onFreshStart: () -> Unit) {
    val c = Quest.colors
    QuestCard {
        Column {
            MetaLabel(stringResource(R.string.resume_label))
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    reason == Returning.FreshStartReason.NEW_WEEK -> stringResource(R.string.resume_new_week)
                    reason == Returning.FreshStartReason.NEW_MONTH -> stringResource(R.string.resume_new_month)
                    else -> stringResource(R.string.resume_away, days ?: Views.RESUME_AFTER_DAYS)
                },
                style = MaterialTheme.typography.titleMedium, color = c.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.resume_body), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            QuietButton(stringResource(R.string.resume_pick_up), onClick = onPickUp, modifier = Modifier.fillMaxWidth())
            if (reason != null) {
                Spacer(Modifier.height(8.dp))
                QuietButton(stringResource(R.string.resume_fresh), onClick = onFreshStart, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Forgiveness first, the next step second (C5). One calm line; never about streaks. */
@Composable
private fun ForgivenessCard(line: String, onSetStep: () -> Unit, onDismiss: () -> Unit) {
    val c = Quest.colors
    QuestCard {
        Column {
            Text(line, style = MaterialTheme.typography.bodyLarge, color = c.text)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton(stringResource(R.string.forgiveness_set_step), onClick = onSetStep)
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.forgiveness_dismiss)) }
            }
        }
    }
}
