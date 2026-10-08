// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Focus
import dev.dreamweave.quest.core.FocusSession
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.sheets.BottomPanel
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import kotlinx.coroutines.delay

/** Pick 10 / 25 / 50 minutes (the last choice is remembered) and start a block on a step. */
@Composable
fun FocusStartSheet(stepText: String, initialMinutes: Int, onDismiss: () -> Unit, onStart: (minutes: Int) -> Unit) {
    val c = Quest.colors
    var minutes by rememberSaveable { mutableStateOf(initialMinutes.takeIf { it in Focus.PRESETS } ?: Focus.DEFAULT_MINUTES) }
    BottomPanel(title = stringResource(R.string.focus_title), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(stepText, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Focus.PRESETS.forEach { m ->
                    FilterChip(
                        selected = m == minutes,
                        onClick = { minutes = m },
                        label = { Text(stringResource(R.string.focus_minutes, m)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.violetTint, selectedLabelColor = c.violet),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LimeButton(stringResource(R.string.focus_start), onClick = { onStart(minutes) }, large = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Ticks [now] a few times a second while shown. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    return now
}

/**
 * The full-screen focus view: the step and the time left, nothing else to look at. Pause and
 * Stop are quiet text buttons at the bottom (also in the notification). Back leaves the
 * view; the timer keeps running.
 */
@Composable
fun FocusView(
    session: FocusSession,
    endXp: Int?,
    onClose: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDone: () -> Unit,
    onKeepGoing: () -> Unit,
    onNotYet: () -> Unit,
) {
    val c = Quest.colors
    val now = rememberNow()
    // A full-screen dialog of its own: nothing beneath it can be tapped or read out. Back closes
    // the view; the timer keeps running.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
    Column(
        Modifier.fillMaxSize().background(c.background).safeDrawingPadding().padding(24.dp)
            .semantics { paneTitle = session.stepText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            session.stepText, style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(32.dp))
        if (session.ended) {
            FocusEndContent(endXp, onDone, onKeepGoing, onNotYet)
        } else {
            val left = FocusSession.format(session.leftMs(now))
            val label = if (session.paused) stringResource(R.string.focus_paused, left) else stringResource(R.string.focus_left, left)
            Text(
                left,
                style = MonoNumber.copy(fontSize = 64.sp, lineHeight = 72.sp),
                color = if (session.paused) c.textSecondary else c.violet,
                modifier = Modifier.semantics { contentDescription = label },
            )
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.paused) {
                    TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.focus_resume)) }
                } else {
                    TextButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.focus_pause)) }
                }
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.focus_stop)) }
            }
        }
    }
    }
}

/** "Did you finish the step?" — Done (the normal flow), Keep going, or Not yet. No judgment either way. */
@Composable
fun FocusEndContent(endXp: Int?, onDone: () -> Unit, onKeepGoing: () -> Unit, onNotYet: () -> Unit) {
    val c = Quest.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        endXp?.let { xp ->
            MetaLabel(
                if (xp > 0) stringResource(R.string.focus_end_effort, xp) else stringResource(R.string.focus_end_effort_capped),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(stringResource(R.string.focus_end_question), style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        LimeButton(stringResource(R.string.focus_end_done), onClick = onDone, large = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        QuietButton(stringResource(R.string.focus_end_keep_going), onClick = onKeepGoing, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onNotYet, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.focus_end_not_yet)) }
    }
}

/** The end question as a bottom panel, when the block ended while the full-screen view was closed. */
@Composable
fun FocusEndPanel(session: FocusSession, endXp: Int?, onDone: () -> Unit, onKeepGoing: () -> Unit, onNotYet: () -> Unit) {
    val c = Quest.colors
    BottomPanel(title = stringResource(R.string.focus_end_question), onDismissRequest = onNotYet, dismissible = false) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(session.stepText, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            Spacer(Modifier.height(8.dp))
            FocusEndContent(endXp, onDone, onKeepGoing, onNotYet)
            Spacer(Modifier.height(12.dp))
        }
    }
}
