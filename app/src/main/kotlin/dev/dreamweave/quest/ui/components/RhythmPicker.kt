// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Practices
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.RhythmKind
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

/**
 * Picks a practice's rhythm (B1): daily, chosen weekdays, or N times a week, and optionally
 * a reminder time on session days. Nothing more.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RhythmPicker(rhythm: Rhythm, onChange: (Rhythm) -> Unit, onPickReminder: (() -> Unit)? = null) {
    val c = Quest.colors
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.violetTint, selectedLabelColor = c.violet)
    Column {
        Text(stringResource(R.string.practice_rhythm_title), style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                RhythmKind.DAILY to R.string.practice_daily,
                RhythmKind.WEEKDAYS to R.string.practice_weekdays,
                RhythmKind.PER_WEEK to R.string.practice_per_week,
            ).forEach { (kind, label) ->
                FilterChip(
                    selected = rhythm.kind == kind,
                    onClick = {
                        onChange(
                            when (kind) {
                                RhythmKind.DAILY -> Rhythm(kind, reminderMinute = rhythm.reminderMinute)
                                RhythmKind.WEEKDAYS -> Rhythm(kind, days = rhythm.days.ifEmpty { listOf(1, 3, 5) }, reminderMinute = rhythm.reminderMinute)
                                RhythmKind.PER_WEEK -> Rhythm(kind, times = rhythm.times.takeIf { it in 1..7 } ?: 3, reminderMinute = rhythm.reminderMinute)
                            },
                        )
                    },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors,
                )
            }
        }
        when (rhythm.kind) {
            RhythmKind.WEEKDAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { d ->
                    val on = d in rhythm.days
                    FilterChip(
                        selected = on,
                        onClick = {
                            val days = if (on) rhythm.days - d else (rhythm.days + d).sorted()
                            // At least one day stays chosen.
                            if (days.isNotEmpty()) onChange(rhythm.copy(days = days))
                        },
                        label = { Text(Practices.dayName(d)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = chipColors,
                    )
                }
            }
            RhythmKind.PER_WEEK -> Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onChange(rhythm.copy(times = (rhythm.times - 1).coerceAtLeast(1))) }, enabled = rhythm.times > 1) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Fewer sessions a week")
                }
                Text(
                    stringResource(R.string.practice_times, rhythm.times), style = MaterialTheme.typography.bodyLarge, color = c.text,
                    modifier = Modifier.semantics { contentDescription = "${rhythm.times} sessions a week" },
                )
                IconButton(onClick = { onChange(rhythm.copy(times = (rhythm.times + 1).coerceAtMost(7))) }, enabled = rhythm.times < 7) {
                    Icon(Icons.Rounded.Add, contentDescription = "More sessions a week")
                }
            }
            RhythmKind.DAILY -> Unit
        }
        if (onPickReminder != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = rhythm.reminderMinute != null,
                    onClick = onPickReminder,
                    label = {
                        Text(
                            rhythm.reminderMinute?.let { stringResource(R.string.practice_reminder) + " · " + Format.minutesOfDay(it) }
                                ?: stringResource(R.string.practice_reminder),
                        )
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = chipColors,
                )
                if (rhythm.reminderMinute != null) {
                    FilterChip(
                        selected = false, onClick = { onChange(rhythm.copy(reminderMinute = null)) },
                        label = { Text(stringResource(R.string.practice_reminder_none)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}
