// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.BuildConfig
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Limits
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.data.ThemeMode
import dev.dreamweave.quest.ui.ExternalRequest
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.SectionTitle
import dev.dreamweave.quest.ui.sheets.TimeOfDayDialog
import dev.dreamweave.quest.ui.theme.MonoNumber
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

/**
 * Settings. Sections from later phases (reminders & alarms, sync & pairing, vault &
 * backups) plug in through [SettingsExtras] so this file stays readable.
 */
@Composable
fun SettingsScreen(
    vm: QuestViewModel,
    state: QuestState,
    settings: AppSettings,
    actions: RootActions,
    pairRequest: ExternalRequest.Pair?,
    onPairHandled: () -> Unit,
) {
    val c = Quest.colors
    var timeDialog by remember { mutableStateOf<String?>(null) }
    var licenses by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.text) }
                Text("Settings", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.semantics { heading() })
            }
        }
        item {
            SectionTitle("Quests")
            QuestCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tracked slots", style = MaterialTheme.typography.titleSmall, color = c.text)
                        Text(
                            "How many quests can be tracked at once (${Limits.MIN_CAP}–${Limits.MAX_CAP}).",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        )
                    }
                    IconButton(onClick = { vm.run { setSlotCap(state.slotCap - 1) } }, enabled = state.slotCap > Limits.MIN_CAP) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Fewer slots", tint = c.text)
                    }
                    Text("${state.slotCap}", style = MonoNumber, color = c.violet, modifier = Modifier.semantics { contentDescription = "${state.slotCap} slots" })
                    IconButton(onClick = { vm.run { setSlotCap(state.slotCap + 1) } }, enabled = state.slotCap < Limits.MAX_CAP) {
                        Icon(Icons.Rounded.Add, contentDescription = "More slots", tint = c.text)
                    }
                }
            }
        }
        item {
            SectionTitle("Reminders")
            QuestCard {
                Column {
                    ToggleTimeRow(
                        title = "Morning digest",
                        subtitle = "Your tracked quests, their next steps and your streak.",
                        enabled = settings.digestEnabled,
                        minutes = settings.digestMinutes,
                        onToggle = { on ->
                            if (on) actions.reminderSet()
                            vm.updateSettings { it.copy(digestEnabled = on) }
                        },
                        onPickTime = { timeDialog = "digest" },
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleTimeRow(
                        title = "Streak guard",
                        subtitle = "Only if you haven't done a step today.",
                        enabled = settings.streakGuardEnabled,
                        minutes = settings.streakGuardMinutes,
                        onToggle = { on ->
                            if (on) actions.reminderSet()
                            vm.updateSettings { it.copy(streakGuardEnabled = on) }
                        },
                        onPickTime = { timeDialog = "guard" },
                    )
                }
            }
            SettingsExtras.reminders(vm, settings)
        }
        item { SettingsExtras.sync(vm, state, settings, pairRequest, onPairHandled) }
        item { SettingsExtras.vault(vm, state, settings) }
        item {
            SectionTitle("Appearance")
            QuestCard {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.theme == mode,
                            onClick = { vm.updateSettings { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
        }
        item {
            SectionTitle("About")
            QuestCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, color = c.text)
                    MetaLabel("Version ${BuildConfig.VERSION_NAME}", secondary = true)
                    Text(
                        "Free software under the GNU AGPL v3 or later. Your quests stay on this phone unless you pair your own server.",
                        style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                    )
                    LinkRow("Source code", BuildConfig.SOURCE_URL)
                    LinkRow("Sponsor on GitHub", BuildConfig.SPONSOR_URL)
                    LinkRow("Buy a coffee on Ko-fi", BuildConfig.KOFI_URL)
                    Text(
                        "Font licenses",
                        style = MaterialTheme.typography.labelLarge, color = c.violet,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { licenses = true }.padding(vertical = 14.dp),
                    )
                }
            }
        }
    }

    when (timeDialog) {
        "digest" -> TimeOfDayDialog("Morning digest", settings.digestMinutes, onDismiss = { timeDialog = null }) { m ->
            timeDialog = null
            actions.reminderSet()
            vm.updateSettings { it.copy(digestMinutes = m) }
        }
        "guard" -> TimeOfDayDialog("Streak guard", settings.streakGuardMinutes, onDismiss = { timeDialog = null }) { m ->
            timeDialog = null
            actions.reminderSet()
            vm.updateSettings { it.copy(streakGuardMinutes = m) }
        }
    }
    if (licenses) LicensesDialog(onDismiss = { licenses = false })
}

@Composable
private fun ToggleTimeRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    minutes: Int,
    onToggle: (Boolean) -> Unit,
    onPickTime: () -> Unit,
) {
    val c = Quest.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        TextButton(onClick = onPickTime, enabled = enabled) {
            Text(Format.minutesOfDay(minutes), style = MonoNumber, color = if (enabled) c.violet else c.textSecondary)
        }
        Switch(
            checked = enabled, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(checkedTrackColor = c.violet, checkedThumbColor = c.onViolet),
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    if (url.isBlank()) return
    val context = LocalContext.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = Quest.colors.violet,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
            .padding(vertical = 14.dp),
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val texts = remember {
        listOf("bricolage-grotesque" to "Bricolage Grotesque", "manrope" to "Manrope", "ibm-plex-mono" to "IBM Plex Mono").map { (file, name) ->
            name to runCatching { context.assets.open("licenses/OFL-$file.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Quest.colors.surface,
        title = { Text("Font licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                texts.forEach { (name, text) ->
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp).width(1.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
