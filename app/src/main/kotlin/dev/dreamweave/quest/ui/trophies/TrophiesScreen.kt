// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.trophies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Achievement
import androidx.compose.ui.res.stringResource
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.SectionTitle
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

@Composable
fun TrophiesScreen(vm: QuestViewModel, state: QuestState) {
    val c = Quest.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val stats = remember(state, now) { Views.stats(state, now, vm.zone) }
    val chronicle = remember(state) { Views.chronicle(state) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Trophies", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.semantics { heading() }) }
        item {
            QuestCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row {
                        Stat("Level", "${stats.level}", Modifier.weight(1f))
                        Stat("XP", "${stats.xp}", Modifier.weight(1f))
                        Stat("Streak", "${stats.currentStreak}", Modifier.weight(1f))
                        Stat("Best", "${stats.bestStreak}", Modifier.weight(1f))
                    }
                    Row {
                        Stat("Steps", "${stats.stepsDone}", Modifier.weight(1f))
                        Stat("Quests", "${stats.questsDone}", Modifier.weight(1f))
                        Stat("Subquests", "${stats.subquestsDone}", Modifier.weight(1f))
                        Stat("Days", "${stats.activeDays}", Modifier.weight(1f))
                    }
                    Row {
                        Stat(stringResource(R.string.trophies_focus_minutes), "${stats.focusMinutes}", Modifier.weight(1f))
                        Spacer(Modifier.weight(3f))
                    }
                }
            }
        }
        // C5: when the current chain is shorter, the best one is kept and shown, never lost.
        if (stats.bestStreak > 0 && stats.currentStreak < stats.bestStreak) {
            item {
                QuestCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = c.violet)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.trophies_best_streak, stats.bestStreak),
                            style = MaterialTheme.typography.titleSmall, color = c.text,
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("Achievements · ${stats.achievementsUnlocked}/${stats.achievementsTotal}")
        }
        items(Achievement.entries.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { a -> AchievementTile(a, state.achievements[a], vm, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item { SectionTitle("Chronicle") }
        if (chronicle.isEmpty()) {
            item { EmptyState(Icons.Rounded.AutoStories, "Your chronicle is empty", "Finished quests are remembered here, with the steps it took.") }
        }
        items(chronicle, key = { it.quest.id }) { entry ->
            QuestCard {
                Column {
                    MetaLabel(Format.longDate(entry.completedAt, vm.zone))
                    Spacer(Modifier.height(2.dp))
                    Text(entry.quest.title, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(
                        "${entry.stepsDone} steps" + if (entry.subquestsDone > 0) " · ${entry.subquestsDone} subquests" else "",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Quest.colors
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value" }) {
        Text(value, style = MonoNumber.copy(fontSize = MaterialTheme.typography.titleLarge.fontSize), color = c.violet)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
private fun AchievementTile(a: Achievement, unlockedAt: Long?, vm: QuestViewModel, modifier: Modifier) {
    val c = Quest.colors
    val unlocked = unlockedAt != null
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(if (unlocked) c.violetTint else c.surfaceAlt)
            .border(1.dp, if (unlocked) c.violet else c.border, shape)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${a.title}: ${a.description}. " + if (unlocked) "Unlocked." else "Locked."
            },
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (unlocked) c.violet else c.border),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (unlocked) Icons.Rounded.EmojiEvents else Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (unlocked) c.onViolet else c.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(a.title, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(a.description, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        if (unlockedAt != null) {
            Spacer(Modifier.width(4.dp))
            Text(Format.longDate(unlockedAt, vm.zone), style = MaterialTheme.typography.labelSmall, color = c.violet)
        }
    }
}
