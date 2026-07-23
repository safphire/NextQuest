package com.nextquest.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextquest.core.LevelProgress
import com.nextquest.core.NextStep
import com.nextquest.app.ui.theme.Amber
import com.nextquest.app.ui.theme.Gold
import com.nextquest.app.ui.theme.Muted

@Composable
fun TrackedScreen(
    cards: List<TrackedCard>,
    progress: LevelProgress,
    trackedCap: Int,
    onDoneStep: (String) -> Unit,
    onEditStep: (TrackedCard) -> Unit,
    onCompleteQuest: (String) -> Unit,
    onUntrack: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LevelHeader(progress, slotsUsed = cards.size, slotCap = trackedCap)
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nothing tracked.\nPick up a quest from the Board.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(cards, key = { it.quest.id }) { card ->
                    TrackedQuestCard(card, onDoneStep, onEditStep, onCompleteQuest, onUntrack)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun LevelHeader(progress: LevelProgress, slotsUsed: Int, slotCap: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "LV ${progress.level}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Gold,
            )
            Spacer(Modifier.weight(1f))
            SlotPips(used = slotsUsed, cap = slotCap)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.fraction },
            color = Gold,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${progress.intoLevel} / ${progress.forNext} XP",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

/** The v0 slot pips: one dot per tracked slot, filled while occupied. */
@Composable
private fun SlotPips(used: Int, cap: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(cap) { index ->
            Surface(
                shape = CircleShape,
                color = if (index < used) Gold else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(8.dp),
            ) {}
        }
    }
}

@Composable
private fun TrackedQuestCard(
    card: TrackedCard,
    onDoneStep: (String) -> Unit,
    onEditStep: (TrackedCard) -> Unit,
    onCompleteQuest: (String) -> Unit,
    onUntrack: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                card.quest.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            when (val step = card.nextStep) {
                is NextStep.Defined -> {
                    Text("NEXT", style = MaterialTheme.typography.labelSmall, color = Gold)
                    Spacer(Modifier.height(4.dp))
                    Text(step.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(12.dp))
                    // The loot moment: the big gold Done button.
                    Button(
                        onClick = { onDoneStep(step.questId) },
                        colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
                is NextStep.Undefined -> {
                    OutlinedButton(
                        onClick = { onEditStep(card) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Define the next step")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { onEditStep(card) }) { Text("Edit step", color = Muted) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onUntrack(card.quest.id) }) { Text("Untrack", color = Muted) }
                TextButton(onClick = { onCompleteQuest(card.quest.id) }) {
                    Text("Complete quest", color = Gold)
                }
            }
        }
    }
}
