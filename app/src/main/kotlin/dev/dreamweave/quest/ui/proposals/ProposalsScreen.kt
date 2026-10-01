// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui.proposals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dreamweave.quest.core.AddQuestPayload
import dev.dreamweave.quest.core.AddSubquestPayload
import dev.dreamweave.quest.core.Decision
import dev.dreamweave.quest.core.Proposal
import dev.dreamweave.quest.core.ProposalOp
import dev.dreamweave.quest.core.ProposalOps
import dev.dreamweave.quest.core.ProposalRules
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.SetNextStepPayload
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.ui.QuestViewModel
import dev.dreamweave.quest.ui.RootActions
import dev.dreamweave.quest.ui.components.ChipText
import dev.dreamweave.quest.ui.components.EmptyState
import dev.dreamweave.quest.ui.components.LimeButton
import dev.dreamweave.quest.ui.components.MetaLabel
import dev.dreamweave.quest.ui.components.QuestCard
import dev.dreamweave.quest.ui.components.QuietButton
import dev.dreamweave.quest.ui.sheets.SwapDialog
import dev.dreamweave.quest.ui.sheets.TextPromptDialog
import dev.dreamweave.quest.ui.theme.Quest
import dev.dreamweave.quest.util.Format

/** Follow-up questions an acceptance may need. */
private sealed interface Ask {
    data class Edit(val proposal: Proposal) : Ask
    data class NextStep(val proposalId: String, val track: Boolean?, val parkQuestId: String?) : Ask
    data class Swap(val proposalId: String, val nextStep: String?) : Ask
}

@Composable
fun ProposalsScreen(vm: QuestViewModel, state: QuestState, settings: AppSettings, actions: RootActions) {
    val c = Quest.colors
    val now by vm.now.collectAsStateWithLifecycle()
    val pending = state.pendingProposals
    var ask by remember { mutableStateOf<Ask?>(null) }

    fun accept(p: Proposal, track: Boolean? = null, parkQuestId: String? = null, nextStep: String? = null) {
        val wantsTrack = track ?: (ProposalOps.decode(p.op, p.payload) as? AddQuestPayload)?.track ?: false
        if (p.op == ProposalOp.ADD_QUEST.wire && wantsTrack && parkQuestId == null && state.freeSlots == 0) {
            ask = Ask.Swap(p.id, nextStep)
            return
        }
        // Through run(): accepting shows XP and achievement toasts like doing it by hand.
        vm.run(
            onRejected = { reason ->
                (reason == ProposalRules.NEEDS_NEXT_STEP).also { if (it) ask = Ask.NextStep(p.id, track, parkQuestId) }
            },
        ) { decide(p.id, Decision.Accept(track = track, parkQuestId = parkQuestId, nextStepText = nextStep)) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = c.text) }
                Text("Proposals", style = MaterialTheme.typography.headlineMedium, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                if (pending.size > 1) LimeButton("Accept all (${pending.size})", onClick = { vm.acceptAll() })
            }
            Text(
                "AI assistants can only suggest. Nothing changes until you accept.",
                style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
            )
        }
        if (pending.isEmpty()) {
            item {
                EmptyState(
                    Icons.Rounded.AutoAwesome,
                    "No proposals",
                    if (settings.serverUrl == null) "Pair a sync server in Settings to let Claude or ChatGPT suggest changes here."
                    else "When Claude or ChatGPT suggests a change, it waits here for you.",
                )
            }
        }
        items(pending, key = { it.id }) { p ->
            ProposalCard(
                p, state, now,
                onReject = { vm.run { decide(p.id, Decision.Reject) } },
                onEdit = { ask = Ask.Edit(p) },
                onAccept = { accept(p) },
                onToInbox = { accept(p, track = false) },
                onTrack = { accept(p, track = true) },
            )
        }
        item { SyncFooter(settings, now) }
    }

    when (val a = ask) {
        null -> Unit
        is Ask.NextStep -> TextPromptDialog(
            title = "Next step needed",
            message = "This quest will be tracked, so it needs a next step. What is it?",
            confirmLabel = "Accept",
            onDismiss = { ask = null },
            onConfirm = { t ->
                ask = null
                state.proposals[a.proposalId]?.let { accept(it, a.track, a.parkQuestId, t) }
            },
        )
        is Ask.Swap -> SwapDialog(
            tracked = state.tracked, cap = state.slotCap, onDismiss = { ask = null },
            onPark = { parkId ->
                ask = null
                state.proposals[a.proposalId]?.let { accept(it, true, parkId, a.nextStep) }
            },
        )
        is Ask.Edit -> {
            val p = a.proposal
            val decoded = ProposalOps.decode(p.op, p.payload)
            val (label, initial) = when (decoded) {
                is SetNextStepPayload -> "Step" to decoded.text
                is AddQuestPayload -> "Quest" to decoded.title
                is AddSubquestPayload -> "Subquest" to decoded.title
                else -> null to ""
            }
            if (label == null) {
                androidx.compose.runtime.LaunchedEffect(p.id) { ask = null }
            } else {
                TextPromptDialog(
                    title = "Edit proposal", message = label, confirmLabel = "Accept edited", initial = initial,
                    onDismiss = { ask = null },
                    onConfirm = { t ->
                        ask = null
                        val edited = when (decoded) {
                            is SetNextStepPayload -> ProposalOps.encode(decoded.copy(text = t))
                            is AddQuestPayload -> ProposalOps.encode(decoded.copy(title = t))
                            is AddSubquestPayload -> ProposalOps.encode(decoded.copy(title = t))
                            else -> null
                        }
                        vm.run { decide(p.id, Decision.Accept(edited = edited)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProposalCard(
    p: Proposal,
    state: QuestState,
    now: Long,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onAccept: () -> Unit,
    onToInbox: () -> Unit,
    onTrack: () -> Unit,
) {
    val c = Quest.colors
    val preview = remember(state, p.id) { ProposalRules.preview(state, p) }
    val editable = p.op in setOf(ProposalOp.SET_NEXT_STEP.wire, ProposalOp.ADD_QUEST.wire, ProposalOp.ADD_SUBQUEST.wire)
    QuestCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChipText(p.source.name, tinted = true)
                Spacer(Modifier.width(8.dp))
                Text(Format.age(p.createdAt, now), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                Spacer(Modifier.weight(1f))
                MetaLabel(preview.opLabel)
            }
            Spacer(Modifier.height(10.dp))
            Text(preview.questTitle, style = MaterialTheme.typography.titleMedium, color = c.text)
            Spacer(Modifier.height(6.dp))
            if (preview.before != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        preview.before!!, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                        textDecoration = TextDecoration.LineThrough, modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "becomes", tint = c.violet, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(preview.after, style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f, fill = false))
                }
            } else {
                Text(preview.after, style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            p.reason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c.violet))
                    Spacer(Modifier.width(10.dp))
                    Text("“$reason”", style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic), color = c.textSecondary)
                }
            }
            preview.stale?.let {
                Spacer(Modifier.height(8.dp))
                Text("Can't apply as-is: $it", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietButton("Reject", onClick = onReject)
                if (preview.isNewQuestToTrack && preview.stale == null) {
                    QuietButton("To Inbox", onClick = onToInbox)
                    Spacer(Modifier.weight(1f))
                    LimeButton("Track", onClick = onTrack)
                } else {
                    if (editable && preview.stale == null) QuietButton("Edit", onClick = onEdit)
                    Spacer(Modifier.weight(1f))
                    LimeButton("Accept", onClick = onAccept, enabled = preview.stale == null)
                }
            }
        }
    }
}

@Composable
private fun SyncFooter(settings: AppSettings, now: Long) {
    val c = Quest.colors
    val text = when {
        settings.serverUrl == null -> "Sync is off. Everything stays on this phone."
        settings.lastSyncError != null -> "Sync problem: ${settings.lastSyncError.orEmpty().trimEnd('.')}."
        settings.lastSyncAt != null -> "Synced ${Format.age(settings.lastSyncAt ?: now, now).let { if (it == "now") "just now" else "$it ago" }}."
        else -> "Waiting for the first sync…"
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
}
