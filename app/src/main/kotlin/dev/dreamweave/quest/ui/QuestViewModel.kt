// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Achievement
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.EventTypes
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.QuestCompleted
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.core.StepCompleted
import dev.dreamweave.quest.data.Change
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.QuestActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** Transient feedback shown by the toast host. */
sealed interface UiEvent {
    data class XpGained(val amount: Long) : UiEvent
    data class AchievementUnlocked(val achievement: Achievement) : UiEvent
    data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null) : UiEvent
    data class QuestCompleted(val title: String, val xp: Long) : UiEvent

    /** A completion was just saved: the 10-second Undo bar (B2). */
    data class UndoOffer(val eventId: String, val label: String) : UiEvent
}

class QuestViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as QuestApp).container
    private val repo = container.repository
    val actions: QuestActions = container.actions

    val state: StateFlow<QuestState?> = repo.state
    val settings: StateFlow<AppSettings?> = container.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ticks every 30 s so due chips, ages and the streak stay fresh. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    val zone: TimeZone get() = repo.zone

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    /** Set when the quest log couldn't be read; the UI shows it with a Retry instead of crashing. */
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** A user action that failed unexpectedly (storage full, database error) gets a calm message, never a crash. */
    private val failures = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "action failed", e)
        message("That didn't go through. Your quests are safe — give it another try, and restart the app if it keeps happening.")
    }

    init {
        loadLog()
    }

    fun loadLog() {
        _loadError.value = null
        viewModelScope.launch {
            try {
                repo.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "loading the quest log failed", e)
                _loadError.value = "Your quests couldn't be loaded. Nothing has been deleted."
            }
        }
    }

    /**
     * Runs one user action and turns its result into feedback: XP toast, achievement
     * toasts, the quest-complete celebration, or a calm message when it was refused.
     */
    fun run(
        onOk: () -> Unit = {},
        xpAlreadyShown: Long = 0,
        /** Handles a refusal itself (e.g. by asking for input); returns false to show the usual message. */
        onRejected: (reason: String) -> Boolean = { false },
        block: suspend QuestActions.() -> CommitResult,
    ) {
        viewModelScope.launch(failures) {
            when (val r = actions.block()) {
                is CommitResult.Ok -> {
                    feedback(r.change.before, r.change.after, xpAlreadyShown)
                    offerUndo(r.change)
                    onOk()
                }
                is CommitResult.Rejected -> if (!onRejected(r.reason)) {
                    message(humanize(r.reason) + if (xpAlreadyShown > 0) " No XP was added." else "")
                }
            }
        }
    }

    /** XP, quest-complete and achievement feedback for the difference between two states. */
    private fun feedback(before: QuestState, after: QuestState, xpAlreadyShown: Long = 0) {
        val finished = after.quests.values.filter {
            it.isRoot && it.status == QuestStatus.DONE && before.quests[it.id]?.status != QuestStatus.DONE
        }
        finished.forEach { _events.tryEmit(UiEvent.QuestCompleted(it.title, after.xp - before.xp)) }
        val gained = after.xp - before.xp - xpAlreadyShown
        if (finished.isEmpty() && gained > 0) _events.tryEmit(UiEvent.XpGained(gained))
        (after.achievements.keys - before.achievements.keys).forEach { _events.tryEmit(UiEvent.AchievementUnlocked(it)) }
    }

    /** Every completion made in the app can be undone for 10 seconds (B2). */
    private fun offerUndo(change: Change) {
        val e = change.events.lastOrNull { it.type == EventTypes.STEP_COMPLETED || it.type == EventTypes.QUEST_COMPLETED } ?: return
        val label = when (e.type) {
            EventTypes.STEP_COMPLETED -> Events.decode(StepCompleted.serializer(), e.payload)?.let { p ->
                val step = change.before.steps[p.stepId]
                val quest = step?.let { change.before.quests[it.questId] }
                when {
                    quest?.isPractice == true && step != null -> "Session · ${step.text}"
                    p.finishQuest && quest != null -> quest.title
                    else -> step?.text
                }
            }
            else -> Events.decode(QuestCompleted.serializer(), e.payload)?.let { change.before.quests[it.questId]?.title }
        } ?: return
        _events.tryEmit(UiEvent.UndoOffer(e.id, label))
    }

    /** Takes back a completion: XP, streak, achievements and focus return exactly to before. */
    fun undo(eventId: String) {
        val app = getApplication<Application>()
        viewModelScope.launch(failures) {
            when (actions.undo(eventId)) {
                is CommitResult.Ok -> message(app.getString(R.string.undo_done))
                is CommitResult.Rejected -> message(app.getString(R.string.undo_refused))
            }
        }
    }

    /** Runs a suspending UI task in the view model's scope (survives recomposition). */
    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch(failures) { block() }
    }

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _events.tryEmit(UiEvent.Message(text, actionLabel, action))
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch(failures) { container.settings.update(transform) }
    }

    fun acceptAll() {
        viewModelScope.launch(failures) {
            val before = repo.current()
            val n = actions.acceptAll()
            val after = repo.current()
            feedback(before, after)
            val left = after.pendingProposals.size
            message(
                when {
                    n == 0 && left > 0 -> "These proposals need your input — open each one."
                    left > 0 -> "Accepted $n. $left need your input."
                    else -> "Accepted $n."
                },
            )
        }
    }

    companion object {
        private const val TAG = "QuestViewModel"

        /** Fold reasons are written for logs; this makes them calm and human. */
        fun humanize(reason: String): String = when {
            "slots are full" in reason -> "All slots are full — finish or park a quest first."
            "needs a next step" in reason -> "A tracked quest needs a next step."
            "title is empty" in reason -> "Give it a name first."
            "step text is empty" in reason -> "Write the step first."
            "too long" in reason -> "That's a bit long — try a shorter version."
            "park quests before lowering" in reason -> reason.replaceFirstChar { it.uppercase() } + "."
            else -> reason.replaceFirstChar { it.uppercase() }.let { if (it.endsWith(".")) it else "$it." }
        }
    }
}
