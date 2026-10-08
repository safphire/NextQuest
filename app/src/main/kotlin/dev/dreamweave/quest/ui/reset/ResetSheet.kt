// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.reset

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Breathing
import dev.dreamweave.quest.core.FocusSession
import dev.dreamweave.quest.core.Limits
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.reset.BreatheRun
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.sheets.BottomPanel
import dev.dreamweave.quest.ui.theme.Quest
import kotlinx.coroutines.delay

/** What the Reset sheet shows. Saved as its name, so it survives rotation. */
enum class ResetMode { MENU, BREATHE, SHRINK, FRESH }

/**
 * Reset (C1): Breathe, Shrink, or Fresh start, from one button in Today's header. Each is
 * small on purpose: no settings, no history, no XP for breathing.
 */
@Composable
fun ResetSheet(
    state: QuestState,
    initialMode: ResetMode,
    breathing: BreatheRun?,
    /** Preselects a quest for Shrink (from the Stuck sheet or the forgiveness line). */
    shrinkQuestId: String? = null,
    onDismiss: () -> Unit,
    onBreathe: (minutes: Int) -> Unit,
    onStopBreathing: () -> Unit,
    onShrink: (stepId: String, text: String) -> Unit,
    onFreshStart: (keepQuestId: String?, parkOthers: Boolean) -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(initialMode.name) }
    val mode = ResetMode.valueOf(modeName)
    BottomPanel(title = stringResource(R.string.reset_title), onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            when (mode) {
                ResetMode.MENU -> ResetMenu { modeName = it.name }
                ResetMode.BREATHE -> BreathePane(breathing, onBreathe, onStopBreathing, onDone = onDismiss)
                ResetMode.SHRINK -> ShrinkPane(state, shrinkQuestId, onShrink)
                ResetMode.FRESH -> FreshStartPane(state, onFreshStart)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ResetMenu(onPick: (ResetMode) -> Unit) {
    val c = Quest.colors
    Text(stringResource(R.string.reset_title), style = MaterialTheme.typography.titleLarge, color = c.text)
    Text(stringResource(R.string.reset_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResetChoice(Icons.Rounded.SelfImprovement, stringResource(R.string.reset_breathe), stringResource(R.string.reset_breathe_hint)) { onPick(ResetMode.BREATHE) }
        ResetChoice(Icons.Rounded.UnfoldLess, stringResource(R.string.reset_shrink), stringResource(R.string.reset_shrink_hint)) { onPick(ResetMode.SHRINK) }
        ResetChoice(Icons.Rounded.WbSunny, stringResource(R.string.reset_fresh), stringResource(R.string.reset_fresh_hint)) { onPick(ResetMode.FRESH) }
    }
}

@Composable
internal fun ResetChoice(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val c = Quest.colors
    QuestCard(onClick = onClick, onClickLabel = title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = c.violet)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
    }
}

/**
 * Breathe: pick 1, 3 or 5 minutes; then a slow shape grows on the inhales and shrinks on the
 * exhale, with the phase in words and a count, and a haptic cue at every phase (from a
 * foreground service, so the screen can be off).
 */
@Composable
private fun BreathePane(run: BreatheRun?, onStart: (Int) -> Unit, onStop: () -> Unit, onDone: () -> Unit) {
    val c = Quest.colors
    var minutes by rememberSaveable { mutableStateOf(3) }
    var started by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.breathe_title), style = MaterialTheme.typography.titleLarge, color = c.text)
    if (run == null && !started) {
        Text(stringResource(R.string.breathe_why), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Breathing.MINUTES.forEach { m ->
                FilterChip(
                    selected = m == minutes, onClick = { minutes = m },
                    label = { Text(stringResource(R.string.breathe_minutes, m)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.violetTint, selectedLabelColor = c.violet),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.breathe_screen_off), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        LimeButton(stringResource(R.string.breathe_start), onClick = {
            started = true
            onStart(minutes)
        }, large = true, modifier = Modifier.fillMaxWidth())
        return
    }
    if (run == null) {
        // The session ran to its end (or was stopped).
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.breathe_finished), style = MaterialTheme.typography.titleMedium, color = c.text,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(12.dp))
        LimeButton(stringResource(R.string.breathe_close), onClick = onDone, modifier = Modifier.fillMaxWidth())
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(run) {
        while (true) {
            now = System.currentTimeMillis()
            delay(50)
        }
    }
    val m = Breathing.at(now - run.startedAt, run.minutes)
    val phase = when (m.phase) {
        Breathing.Phase.INHALE -> stringResource(R.string.breathe_inhale)
        Breathing.Phase.TOP_UP -> stringResource(R.string.breathe_inhale_more)
        Breathing.Phase.EXHALE -> stringResource(R.string.breathe_exhale)
    }
    Spacer(Modifier.height(16.dp))
    val shapeLabel = stringResource(R.string.breathe_shape_a11y)
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        val violet = c.violet
        val tint = c.violetTint
        Canvas(Modifier.size(220.dp).semantics { contentDescription = shapeLabel }) {
            val max = size.minDimension / 2
            drawCircle(tint, radius = max)
            drawCircle(violet, radius = max * (0.35f + 0.6f * m.fullness))
        }
    }
    Spacer(Modifier.height(12.dp))
    // The text carries everything the shape shows, for screen readers and eyes closed.
    Text(
        phase, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    Text(
        stringResource(R.string.breathe_count, m.breath, FocusSession.format(m.leftMs)),
        style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.breathe_stop)) }
}

/** Shrink: pick a tracked quest, see its step, write the 2-minute version. */
@Composable
private fun ShrinkPane(state: QuestState, preselected: String?, onShrink: (stepId: String, text: String) -> Unit) {
    val c = Quest.colors
    val candidates = state.tracked.filter { state.effectiveStep(it.id) != null }
    var questId by rememberSaveable { mutableStateOf(preselected?.takeIf { id -> candidates.any { it.id == id } } ?: candidates.singleOrNull()?.id) }
    var text by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.shrink_title), style = MaterialTheme.typography.titleLarge, color = c.text)
    val chosen = questId?.let { id -> candidates.firstOrNull { it.id == id } }
    if (chosen == null) {
        Text(stringResource(R.string.shrink_pick), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { q ->
                QuestCard(onClick = { questId = q.id }, onClickLabel = q.title) {
                    Column {
                        Text(q.title, style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(state.effectiveStep(q.id)?.text.orEmpty(), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
            }
        }
        return
    }
    val step = state.effectiveStep(chosen.id) ?: return
    MetaLabel(chosen.title)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.shrink_now, step.text), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.shrink_prompt), style = MaterialTheme.typography.titleSmall, color = c.text)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.replace("\n", "").take(Limits.MAX_TEXT) },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.shrink_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
    Spacer(Modifier.height(12.dp))
    LimeButton(stringResource(R.string.shrink_save), onClick = { onShrink(step.id, text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth())
}

/** Fresh start: clean slate, optionally parking all but one tracked quest. */
@Composable
private fun FreshStartPane(state: QuestState, onFreshStart: (keepQuestId: String?, parkOthers: Boolean) -> Unit) {
    val c = Quest.colors
    var park by rememberSaveable { mutableStateOf(false) }
    var keep by rememberSaveable { mutableStateOf(state.tracked.firstOrNull()?.id) }
    Text(stringResource(R.string.fresh_title), style = MaterialTheme.typography.titleLarge, color = c.text)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.fresh_body), style = MaterialTheme.typography.titleMedium, color = c.text)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.fresh_details), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
    if (state.tracked.size > 1) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = park, role = Role.Switch, onValueChange = { park = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.fresh_park_toggle), style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
            Switch(
                checked = park, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedTrackColor = c.violet, checkedThumbColor = c.onViolet),
            )
        }
        if (park) {
            Text(stringResource(R.string.fresh_keep), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            state.tracked.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = keep == q.id, role = Role.RadioButton, onClick = { keep = q.id }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = keep == q.id, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.violet))
                    Spacer(Modifier.size(8.dp))
                    Text(q.title, style = MaterialTheme.typography.bodyLarge, color = c.text)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    LimeButton(
        stringResource(R.string.fresh_confirm),
        onClick = { onFreshStart(if (park) keep else null, park && keep != null) },
        large = true, modifier = Modifier.fillMaxWidth(),
    )
}
