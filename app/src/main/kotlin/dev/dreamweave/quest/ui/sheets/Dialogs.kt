// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import dev.dreamweave.quest.core.Quest as QuestModel
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.theme.Quest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import dev.dreamweave.quest.util.Format

/** A one-field dialog: set a step, name a subquest, rename a quest, … */
@Composable
fun TextPromptDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    placeholder: String = "",
    message: String? = null,
    allowEmpty: Boolean = false,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val c = Quest.colors
    val ok = allowEmpty || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(text.trim()) }),
                )
            }
        },
        confirmButton = { LimeButton(confirmLabel, onClick = { onConfirm(text.trim()) }, enabled = ok) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Slots are full: park one tracked quest to make room (or cancel and finish one first). */
@Composable
fun SwapDialog(tracked: List<QuestModel>, cap: Int, onDismiss: () -> Unit, onPark: (String) -> Unit) {
    val c = Quest.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("All $cap slots are full", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Finish one first, or park one for later to make room.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )
                tracked.forEach { q ->
                    QuietButton("Park “${q.title}”", onClick = { onPark(q.id) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Quest.colors.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium, color = Quest.colors.textSecondary) },
        confirmButton = { LimeButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks a date, then a time. Returns epoch millis via [onPicked]; [onClear] removes the date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDatePicker(
    initial: Long?,
    now: Long,
    zone: TimeZone,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    val start = Instant.fromEpochMilliseconds(initial ?: now).toLocalDateTime(zone)
    if (pickedDate == null) {
        // The Material date picker works in UTC midnights.
        val utcMidnight = Format.startOfDay(start.date, TimeZone.UTC)
        val dateState = rememberDatePickerState(initialSelectedDateMillis = utcMidnight)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        pickedDate = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
                    }
                }) { Text("Next") }
            },
            dismissButton = {
                Row {
                    if (onClear != null && initial != null) TextButton(onClick = onClear) { Text("Remove date") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = dateState) }
    } else {
        val timeState = rememberTimePickerState(
            initialHour = if (initial != null) start.hour else 9,
            initialMinute = if (initial != null) start.minute else 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Quest.colors.surface,
            title = { Text("Time", style = MaterialTheme.typography.titleLarge) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                LimeButton("Set", onClick = {
                    onPicked(Format.epochOf(pickedDate!!, timeState.hour * 60 + timeState.minute, zone))
                })
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/** A time-of-day picker for settings (digest, streak guard). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayDialog(title: String, minutes: Int, onDismiss: () -> Unit, onPicked: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Quest.colors.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { TimePicker(state = state) },
        confirmButton = { LimeButton("Set", onClick = { onPicked(state.hour * 60 + state.minute) }) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

