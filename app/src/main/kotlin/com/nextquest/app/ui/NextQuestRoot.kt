package com.nextquest.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextquest.app.ui.theme.Gold
import com.nextquest.app.ui.theme.Muted

/** Target of the "edit next step" dialog. */
private data class StepEdit(val questId: String, val title: String, val current: String)

@Composable
fun NextQuestRoot(viewModel: MainViewModel) {
    val tracked by viewModel.tracked.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val progress by viewModel.levelProgress.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sort by viewModel.boardSort.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var stepEdit by remember { mutableStateOf<StepEdit?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        // XP toasts — one at a time, latest wins.
        viewModel.toasts.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Tracked", "Board").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {},
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Gold,
                            unselectedTextColor = Muted,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> TrackedScreen(
                cards = tracked,
                progress = progress,
                trackedCap = viewModel.trackedCap,
                onDoneStep = viewModel::completeStep,
                onEditStep = { card ->
                    stepEdit = StepEdit(
                        // The invariant may point the step at a focused subquest;
                        // edit whichever quest currently owns it.
                        questId = card.nextStep.questId,
                        title = card.quest.title,
                        current = card.quest.nextStep.orEmpty(),
                    )
                },
                onCompleteQuest = viewModel::completeQuest,
                onUntrack = viewModel::untrack,
                modifier = Modifier.padding(padding),
            )
            else -> BoardScreen(
                quests = board,
                searchQuery = searchQuery,
                sort = sort,
                onSearchChange = { viewModel.searchQuery.value = it },
                onSortChange = { viewModel.boardSort.value = it },
                onCapture = viewModel::capture,
                onTrack = viewModel::track,
                onEditStep = { quest ->
                    stepEdit = StepEdit(quest.id, quest.title, quest.nextStep.orEmpty())
                },
                modifier = Modifier.padding(padding),
            )
        }
    }

    stepEdit?.let { edit ->
        NextStepDialog(
            title = edit.title,
            initial = edit.current,
            onDismiss = { stepEdit = null },
            onConfirm = { text ->
                viewModel.setNextStep(edit.questId, text)
                stepEdit = null
            },
        )
    }
}

@Composable
private fun NextStepDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("One concrete next step", color = Muted) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Set step", color = Gold, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        },
    )
}
