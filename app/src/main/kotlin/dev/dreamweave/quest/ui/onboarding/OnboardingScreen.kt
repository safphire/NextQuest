// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.theme.Quest

/**
 * First launch: 1) brain-dump everything on your mind, 2) choose up to the cap to track,
 * each with its first step, 3) the rest go to Later. Notification permission is not asked
 * here — only when the first reminder is set.
 */
@Composable
fun OnboardingScreen(vm: QuestViewModel, state: QuestState) {
    val c = Quest.colors
    val items = remember { mutableStateListOf<String>() }
    val chosen = remember { mutableStateMapOf<Int, String>() } // index → first step
    var input by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(0) }
    val cap = state.slotCap

    fun add() {
        input.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { items.add(it.take(500)) }
        input = ""
    }

    fun finish() {
        val plan = items.mapIndexed { i, title -> title to chosen[i]?.takeIf { it.isNotBlank() } }
        vm.run(onOk = { vm.updateSettings { it.copy(onboardingDone = true) } }) { onboard(plan) }
    }

    Column(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().imePadding()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MetaLabel(stringResource(R.string.app_name))
                Spacer(Modifier.height(6.dp))
                if (page == 0) {
                    Text("Empty your head", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Everything you want to do, big or small. One per line. Don't sort — that's next.",
                        style = MaterialTheme.typography.bodyLarge, color = c.textSecondary,
                    )
                } else {
                    Text("Pick up to $cap", style = MaterialTheme.typography.displaySmall, color = c.text, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tracked quests show only their next step. Give each one a first step. Everything else waits in Later.",
                        style = MaterialTheme.typography.bodyLarge, color = c.textSecondary,
                    )
                }
            }
            if (page == 0) {
                item {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Plan the trip to Lisbon") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        singleLine = false,
                        maxLines = 6,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { add() }, enabled = input.isNotBlank()) { Text("Add") }
                }
                itemsIndexed(items) { i, title ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("•  $title", style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.weight(1f))
                        IconButton(onClick = { items.removeAt(i); chosen.clear() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove “$title”", tint = c.textSecondary)
                        }
                    }
                }
            } else {
                itemsIndexed(items) { i, title ->
                    val isChosen = chosen.containsKey(i)
                    val full = chosen.size >= cap
                    QuestCard {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox, enabled = isChosen || !full) {
                                    if (isChosen) chosen.remove(i) else chosen[i] = ""
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = isChosen, onCheckedChange = null, enabled = isChosen || !full,
                                    colors = CheckboxDefaults.colors(checkedColor = c.violet, checkmarkColor = c.onViolet),
                                )
                                Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text, modifier = Modifier.padding(start = 8.dp))
                            }
                            if (isChosen) {
                                OutlinedTextField(
                                    value = chosen[i].orEmpty(),
                                    onValueChange = { chosen[i] = it.replace("\n", "") },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("First step") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                                )
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (page == 0) {
                LimeButton(
                    if (items.isEmpty()) "Add at least one" else "Next · ${items.size} item${if (items.size == 1) "" else "s"}",
                    onClick = {
                        if (input.isNotBlank()) add()
                        page = 1
                    },
                    enabled = items.isNotEmpty() || input.isNotBlank(),
                    large = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.updateSettings { it.copy(onboardingDone = true) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip — start empty")
                }
            } else {
                val missingSteps = chosen.values.any { it.isBlank() }
                LimeButton(
                    when {
                        missingSteps -> "Add a first step to each"
                        chosen.isEmpty() -> "Put everything in Later"
                        else -> "Start · track ${chosen.size}"
                    },
                    onClick = { finish() },
                    enabled = !missingSteps,
                    large = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { page = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}
