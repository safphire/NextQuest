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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextquest.app.data.QuestEntity
import com.nextquest.app.ui.theme.Gold
import com.nextquest.app.ui.theme.Muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    quests: List<QuestEntity>,
    searchQuery: String,
    sort: BoardSort,
    onSearchChange: (String) -> Unit,
    onSortChange: (BoardSort) -> Unit,
    onCapture: (String) -> Unit,
    onTrack: (String) -> Unit,
    onEditStep: (QuestEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var captureText by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        // Capture: a thought becomes an untracked quest — nothing gets dropped.
        OutlinedTextField(
            value = captureText,
            onValueChange = { captureText = it },
            placeholder = { Text("Capture a quest…", color = Muted) },
            trailingIcon = {
                TextButton(
                    onClick = {
                        onCapture(captureText)
                        captureText = ""
                    },
                    enabled = captureText.isNotBlank(),
                ) { Text("Add", color = Gold, fontWeight = FontWeight.Bold) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search the board", color = Muted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BoardSort.entries.forEach { option ->
                FilterChip(
                    selected = sort == option,
                    onClick = { onSortChange(option) },
                    label = { Text(option.label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        if (quests.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isBlank()) "The board is clear." else "No quests match.",
                    color = Muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(quests, key = { it.id }) { quest ->
                    BoardRow(quest, onTrack, onEditStep)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun BoardRow(
    quest: QuestEntity,
    onTrack: (String) -> Unit,
    onEditStep: (QuestEntity) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(quest.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    quest.nextStep ?: "no next step yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            TextButton(onClick = { onEditStep(quest) }) { Text("Step", color = Muted) }
            OutlinedButton(onClick = { onTrack(quest.id) }) { Text("Track", color = Gold) }
        }
    }
}
