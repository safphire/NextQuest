// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dreamweave.quest.core.BoardSort
import dev.dreamweave.quest.core.BoardTab
import dev.dreamweave.quest.core.Quest as QuestModel
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.Views
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.components.SlotPips
import dev.dreamweave.quest.ui.sheets.SwapDialog
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import dev.dreamweave.quest.ui.theme.Quest

/** Tracking can need two answers: a first step, and which quest to park when slots are full. */
private data class TrackFlow(val questId: String, val step: String? = null, val needsSwap: Boolean = false)

@Composable
fun BoardScreen(vm: QuestViewModel, state: QuestState, actions: RootActions) {
    val c = Quest.colors
    var tab by rememberSaveable { mutableStateOf(BoardTab.INBOX) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(BoardSort.TOUCHED) }
    var sortMenu by remember { mutableStateOf(false) }
    var trackFlow by remember { mutableStateOf<TrackFlow?>(null) }
    val items = remember(state, tab, query, sort) { Views.board(state, tab, query, sort) }
    val inboxCount = remember(state) { Views.board(state, BoardTab.INBOX).size }
    val laterCount = remember(state) { Views.board(state, BoardTab.LATER).size }

    fun startTrack(q: QuestModel) {
        val hasStep = state.openStep(q.id) != null
        trackFlow = TrackFlow(q.id, step = null, needsSwap = state.freeSlots == 0)
        if (hasStep && state.freeSlots > 0) {
            trackFlow = null
            vm.run { track(q.id) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Board", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
            SlotPips(state.tracked.size, state.slotCap)
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, contentDescription = "Sort", tint = c.text) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf(
                        BoardSort.TOUCHED to "Recently touched",
                        BoardSort.CREATED to "Recently created",
                        BoardSort.ALPHABETICAL to "A–Z",
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(if (value == sort) "✓  $label" else label) },
                            onClick = {
                                sort = value
                                sortMenu = false
                            },
                        )
                    }
                }
            }
        }
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = c.background,
            contentColor = c.violet,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[tab.ordinal]), color = c.violet)
            },
        ) {
            Tab(selected = tab == BoardTab.INBOX, onClick = { tab = BoardTab.INBOX }, text = { Text("Inbox · $inboxCount") })
            Tab(selected = tab == BoardTab.LATER, onClick = { tab = BoardTab.LATER }, text = { Text("Later · $laterCount") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    EmptyState(
                        icon = if (tab == BoardTab.INBOX) Icons.Rounded.Inbox else Icons.Rounded.Schedule,
                        title = when {
                            query.isNotBlank() -> "No matches"
                            tab == BoardTab.INBOX -> "Inbox zero"
                            else -> "Nothing parked"
                        },
                        body = when {
                            query.isNotBlank() -> "Nothing here matches “$query”."
                            tab == BoardTab.INBOX -> "Everything you capture with + lands here, unsorted."
                            else -> "Quests you park for later wait here — no pressure."
                        },
                    )
                }
            }
            items(items, key = { it.id }) { q ->
                BoardItem(
                    quest = q,
                    state = state,
                    tab = tab,
                    onOpen = { actions.openQuest(q.id) },
                    onTrack = { startTrack(q) },
                    onMove = { vm.run { if (tab == BoardTab.INBOX) park(q.id) else toInbox(q.id) } },
                    onDrop = {
                        vm.run(onOk = { vm.message("Dropped “${q.title}”", "Undo") { vm.run { restore(q.id) } } }) { drop(q.id) }
                    },
                )
            }
        }
    }

    trackFlow?.let { flow ->
        val q = state.quests[flow.questId]
        when {
            q == null -> trackFlow = null
            state.openStep(q.id) == null && flow.step == null -> TextPromptDialog(
                title = "First step",
                message = "A tracked quest always shows its next step. What's the first one for “${q.title}”?",
                confirmLabel = if (flow.needsSwap) "Next" else "Track",
                placeholder = "The very next physical action",
                onDismiss = { trackFlow = null },
                onConfirm = { step ->
                    if (flow.needsSwap) {
                        trackFlow = flow.copy(step = step)
                    } else {
                        trackFlow = null
                        vm.run { track(q.id, firstStep = step) }
                    }
                },
            )
            flow.needsSwap -> SwapDialog(
                tracked = state.tracked,
                cap = state.slotCap,
                onDismiss = { trackFlow = null },
                onPark = { parkId ->
                    trackFlow = null
                    vm.run { track(q.id, firstStep = flow.step, parkQuestId = parkId) }
                },
            )
            else -> trackFlow = null
        }
    }
}

@Composable
private fun BoardItem(
    quest: QuestModel,
    state: QuestState,
    tab: BoardTab,
    onOpen: () -> Unit,
    onTrack: () -> Unit,
    onMove: () -> Unit,
    onDrop: () -> Unit,
) {
    val c = Quest.colors
    val step = state.openStep(quest.id)
    val kids = state.children(quest.id).size
    QuestCard(onClick = onOpen, onClickLabel = "Open quest") {
        Column {
            Text(quest.title, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            quest.why?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (step != null || kids > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step != null) {
                        MetaLabel("Next", secondary = true)
                        Spacer(Modifier.width(6.dp))
                        Text(step.text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    if (kids > 0) MetaLabel("$kids sub", secondary = true)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LimeButton("Track", onClick = onTrack)
                QuietButton(
                    if (tab == BoardTab.INBOX) "Later" else "Inbox",
                    onClick = onMove,
                    icon = if (tab == BoardTab.INBOX) Icons.Rounded.Schedule else Icons.Rounded.MoveToInbox,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDrop) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Drop “${quest.title}”", tint = c.textSecondary)
                }
            }
        }
    }
}
