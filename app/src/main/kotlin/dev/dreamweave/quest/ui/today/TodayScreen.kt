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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.core.QuestState
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
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

@Composable
fun TodayScreen(vm: QuestViewModel, state: QuestState, settings: AppSettings, actions: RootActions) {
    val now by vm.now.collectAsStateWithLifecycle()
    val view = remember(state, now) { Views.today(state, now, vm.zone) }
    val c = Quest.colors
    var settingStepFor by rememberSaveable { mutableStateOf<String?>(null) }
    val showResume = view.resumeAfterDays != null && settings.resumeDismissedAt < (state.lastUserActivityAt ?: 0)

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
                SlotPips(view.slotsUsed, view.slotCap)
            }
        }
        if (showResume) {
            item {
                ResumeCard(days = view.resumeAfterDays!!, onDismiss = { vm.updateSettings { it.copy(resumeDismissedAt = now) } })
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
                TrackedCard(card, now, vm, onOpen = { actions.openQuest(card.quest.id) }, onDone = { card.step?.let { actions.done(it.id) } })
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
}

@Composable
private fun TrackedCard(card: TodayCard, now: Long, vm: QuestViewModel, onOpen: () -> Unit, onDone: () -> Unit) {
    val c = Quest.colors
    val step = card.step ?: return
    QuestCard(onClick = onOpen, onClickLabel = "Open quest") {
        Column {
            Text(card.quest.title, style = MaterialTheme.typography.titleMedium, color = c.text)
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
            MetaLabel(if (card.stepOwner.id != card.quest.id) "Next · ${card.stepOwner.title}" else "Next")
            Spacer(Modifier.height(4.dp))
            Text(step.text, style = MaterialTheme.typography.bodyLarge, color = c.text)
            step.dueAt?.let { due ->
                Spacer(Modifier.height(8.dp))
                ChipText(Format.due(due, now, vm.zone), icon = Icons.Rounded.Schedule)
            }
            Spacer(Modifier.height(14.dp))
            LimeButton(
                "Done · +${Xp.STEP} XP", onClick = onDone, icon = Icons.Rounded.Check, large = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

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

/** Re-entry without guilt: no backlog, no red — just a way back in. */
@Composable
private fun ResumeCard(days: Int, onDismiss: () -> Unit) {
    val c = Quest.colors
    QuestCard {
        Column {
            MetaLabel("Welcome back")
            Spacer(Modifier.height(4.dp))
            Text("It's been $days days. Nothing to catch up on.", style = MaterialTheme.typography.titleMedium, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick any one step below and start there. Your quests waited for you.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            QuietButton("Let's go", onClick = onDismiss)
        }
    }
}
