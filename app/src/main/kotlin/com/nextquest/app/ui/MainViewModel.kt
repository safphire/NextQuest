package com.nextquest.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nextquest.app.data.QuestEntity
import com.nextquest.app.data.QuestRepository
import com.nextquest.app.data.TrackResult
import com.nextquest.core.LevelProgress
import com.nextquest.core.NextStep
import com.nextquest.core.NextSteps
import com.nextquest.core.QuestNode
import com.nextquest.core.Tracking
import com.nextquest.core.Xp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BoardSort(val label: String) {
    RECENT("Recent"),
    CREATED("Created"),
    TITLE("A–Z"),
}

/** A tracked card: the entity plus its resolved next step (core invariant). */
data class TrackedCard(val quest: QuestEntity, val nextStep: NextStep)

class MainViewModel(private val repo: QuestRepository) : ViewModel() {

    val trackedCap: Int = Tracking.DEFAULT_CAP

    val tracked: StateFlow<List<TrackedCard>> = repo.trackedRoots
        .map { roots -> roots.map { TrackedCard(it, NextSteps.resolve(it.toNode())) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val levelProgress: StateFlow<LevelProgress> = repo.totalXp
        .map(Xp::progress)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Xp.progress(0))

    val searchQuery = MutableStateFlow("")
    val boardSort = MutableStateFlow(BoardSort.RECENT)

    val board: StateFlow<List<QuestEntity>> =
        combine(repo.boardRoots, searchQuery, boardSort) { roots, query, sort ->
            val filtered = if (query.isBlank()) roots
            else roots.filter { it.title.contains(query.trim(), ignoreCase = true) }
            when (sort) {
                BoardSort.RECENT -> filtered.sortedByDescending { it.touchedAt }
                BoardSort.CREATED -> filtered.sortedByDescending { it.createdAt }
                BoardSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val toastChannel = Channel<String>(Channel.BUFFERED)
    val toasts: Flow<String> = toastChannel.receiveAsFlow()

    init {
        // XP toasts carry level-ups too; watch for the crossing here.
        viewModelScope.launch {
            var lastLevel: Int? = null
            levelProgress.collect { progress ->
                val previous = lastLevel
                lastLevel = progress.level
                if (previous != null && progress.level > previous) {
                    toastChannel.send("Level up — LV ${progress.level}!")
                }
            }
        }
    }

    fun capture(title: String) {
        viewModelScope.launch {
            if (repo.capture(title) != null) toastChannel.send("Quest captured")
        }
    }

    fun track(id: String) {
        viewModelScope.launch {
            when (val result = repo.track(id, trackedCap)) {
                is TrackResult.AtCap ->
                    toastChannel.send("Tracked is full (${result.cap}) — finish or untrack one")
                TrackResult.Tracked -> toastChannel.send("Quest tracked")
            }
        }
    }

    fun untrack(id: String) {
        viewModelScope.launch { repo.untrack(id) }
    }

    fun setNextStep(id: String, step: String) {
        viewModelScope.launch { repo.setNextStep(id, step) }
    }

    fun completeStep(id: String) {
        viewModelScope.launch {
            val xp = repo.completeStep(id)
            if (xp > 0) toastChannel.send("+$xp XP — step done")
        }
    }

    fun completeQuest(id: String) {
        viewModelScope.launch {
            val xp = repo.completeQuest(id)
            if (xp > 0) toastChannel.send("+$xp XP — quest complete")
        }
    }

    companion object {
        fun factory(repo: QuestRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(repo) }
        }
    }
}

private fun QuestEntity.toNode(): QuestNode =
    QuestNode(
        id = id,
        title = title,
        nextStep = nextStep,
        done = isDone,
        focusedChildId = focusedChild,
        // M0 has no subquest UI yet; children arrive with M1.
        children = emptyList(),
    )
