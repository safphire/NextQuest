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
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.theme.Quest

/**
 * Capture in two seconds: one field, focused immediately, Enter saves to the inbox.
 * Nothing else is asked — sorting happens later on the Board.
 */
@Composable
fun CaptureSheet(onDismiss: () -> Unit, onCapture: (String) -> Unit, initial: String = "") {
    var text by rememberSaveable(initial) { mutableStateOf(initial.replace("\n", " ").take(500)) }
    val focus = remember { FocusRequester() }
    val c = Quest.colors
    var saved by remember { mutableStateOf(false) }
    fun save() {
        if (text.isNotBlank() && !saved) {
            saved = true
            onCapture(text.trim())
            onDismiss()
        }
    }
    BottomPanel(onDismissRequest = onDismiss) {
        // The dialog's own window has the keyboard, so read the controller in here.
        val keyboard = LocalSoftwareKeyboardController.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Capture", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("Lands in your inbox. Sort it later.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("What's on your mind?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            Spacer(Modifier.height(12.dp))
            LimeButton("Save to inbox", onClick = { save() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
}
