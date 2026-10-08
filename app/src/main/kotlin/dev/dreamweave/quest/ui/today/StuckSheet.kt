// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Quest as QuestModel
import dev.dreamweave.quest.core.Step
import dev.dreamweave.quest.ui.reset.ResetChoice
import dev.dreamweave.quest.ui.sheets.BottomPanel
import dev.dreamweave.quest.ui.theme.Quest

/**
 * "Stuck?" (B4): four small ways back in, never a judgment. Shrink, ten minutes of focus,
 * park it, or ask an AI to break it down.
 */
@Composable
fun StuckSheet(
    quest: QuestModel,
    step: Step,
    onDismiss: () -> Unit,
    onShrink: () -> Unit,
    onFocus10: () -> Unit,
    onPark: () -> Unit,
    onAskAi: () -> Unit,
) {
    val c = Quest.colors
    BottomPanel(title = stringResource(R.string.stuck_title), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.stuck_title), style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(quest.title + " · " + step.text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.stuck_body), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResetChoice(Icons.Rounded.UnfoldLess, stringResource(R.string.stuck_shrink), stringResource(R.string.stuck_shrink_hint), onShrink)
                ResetChoice(Icons.Rounded.Timer, stringResource(R.string.stuck_focus), stringResource(R.string.stuck_focus_hint), onFocus10)
                ResetChoice(Icons.Rounded.Schedule, stringResource(R.string.stuck_park), stringResource(R.string.stuck_park_hint), onPark)
                ResetChoice(Icons.Rounded.AutoAwesome, stringResource(R.string.stuck_ask_ai), stringResource(R.string.stuck_ask_ai_hint), onAskAi)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
