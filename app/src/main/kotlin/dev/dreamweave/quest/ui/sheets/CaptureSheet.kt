// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.CaptureText
import dev.dreamweave.quest.core.Limits
import dev.dreamweave.quest.core.Rhythm
import dev.dreamweave.quest.core.RhythmKind
import dev.dreamweave.quest.ui.components.RhythmPicker
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.res.stringResource
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.theme.Quest

/**
 * Capture in two seconds: one field, focused immediately, Enter saves to the inbox.
 * Nothing else is asked — sorting happens later on the Board. Two optional extras (v1.1):
 * a pasted list (e.g. an AI's breakdown) saves one inbox item per line, and "Practice"
 * makes it a practice with a rhythm.
 */
@Composable
fun CaptureSheet(onDismiss: () -> Unit, onCapture: (String) -> Unit, initial: String = "", onCaptureList: (String) -> Unit = {}, onCapturePractice: (String, Rhythm) -> Unit = { t, _ -> onCapture(t) }) {
    var text by rememberSaveable(initial) { mutableStateOf(initial.replace("\n", " ").take(Limits.MAX_TITLE)) }
    var practice by rememberSaveable { mutableStateOf(false) }
    var rhythm by rememberSaveable(stateSaver = jsonSaver(Rhythm.serializer())) { mutableStateOf<Rhythm?>(Rhythm(RhythmKind.DAILY)) }
    val focus = remember { FocusRequester() }
    val c = Quest.colors
    var saved by remember { mutableStateOf(false) }
    val items = CaptureText.items(text)
    val isList = items.size > 1 && !practice
    fun save() {
        if (text.isNotBlank() && !saved) {
            saved = true
            when {
                isList -> onCaptureList(text)
                practice -> onCapturePractice(text.replace("\n", " ").trim(), rhythm ?: Rhythm(RhythmKind.DAILY))
                else -> onCapture(text.trim())
            }
            onDismiss()
        }
    }
    BottomPanel(title = "Capture", onDismissRequest = onDismiss) {
        // The dialog's own window has the keyboard, so read the controller in here.
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Capture", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(
                if (isList) stringResource(R.string.capture_list_hint) else "Lands in your inbox. Sort it later.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                // A pasted list keeps its lines (one item each); a single item stays one line.
                onValueChange = { v -> text = (if (CaptureText.isList(v)) v else v.replace("\n", "")).take(Limits.MAX_TITLE * 20) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("What's on your mind?") },
                singleLine = false,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            if (!isList) {
                Spacer(Modifier.height(4.dp))
                FilterChip(
                    selected = practice,
                    onClick = { practice = !practice },
                    label = { Text(stringResource(R.string.practice_label)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.violetTint, selectedLabelColor = c.violet),
                )
                if (practice) RhythmPicker(rhythm ?: Rhythm(RhythmKind.DAILY), onChange = { rhythm = it })
            }
            Spacer(Modifier.height(12.dp))
            LimeButton(
                if (isList) stringResource(R.string.capture_list, items.size) else "Save to inbox",
                onClick = { save() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
}
