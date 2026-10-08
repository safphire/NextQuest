// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.quest

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.RhythmPicker
import dev.dreamweave.quest.ui.sheets.TimeOfDayDialog
import dev.dreamweave.quest.ui.sheets.jsonSaver
import dev.dreamweave.quest.ui.theme.Quest

/** Make a quest a practice, change its rhythm and reminder, or make it a regular quest again. */
@Composable
internal fun RhythmDialog(initial: Rhythm, isPractice: Boolean, onDismiss: () -> Unit, onSave: (Rhythm) -> Unit, onMakeQuest: () -> Unit) {
    var rhythm by rememberSaveable(stateSaver = jsonSaver(Rhythm.serializer())) { mutableStateOf<Rhythm?>(initial) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val r = rhythm ?: initial
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Quest.colors.surface,
        title = { Text(stringResource(if (isPractice) R.string.practice_change_rhythm else R.string.practice_make), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                RhythmPicker(r, onChange = { rhythm = it }, onPickReminder = { pickingTime = true })
                if (isPractice) TextButton(onClick = onMakeQuest) { Text(stringResource(R.string.practice_unmake)) }
            }
        },
        confirmButton = { LimeButton("Save", onClick = { onSave(r) }) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingTime) {
        TimeOfDayDialog(
            title = stringResource(R.string.practice_reminder),
            minutes = r.reminderMinute ?: (8 * 60),
            onDismiss = { pickingTime = false },
            onPicked = {
                rhythm = r.copy(reminderMinute = it)
                pickingTime = false
            },
        )
    }
}
