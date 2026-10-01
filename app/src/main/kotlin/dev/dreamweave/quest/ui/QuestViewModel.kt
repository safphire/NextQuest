// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dreamweave.quest.QuestApp
import dev.dreamweave.quest.core.Achievement
import dev.dreamweave.quest.core.QuestState
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.data.AppSettings
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.QuestActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    init {
        viewModelScope.launch { repo.load() }
    }

    /**
     * Runs one user action and turns its result into feedback: XP toast, achievement
     * toasts, the quest-complete celebration, or a calm message when it was refused.
     */
    fun run(onOk: () -> Unit = {}, xpAlreadyShown: Long = 0, block: suspend QuestActions.() -> CommitResult) {
        viewModelScope.launch {
            when (val r = actions.block()) {
                is CommitResult.Ok -> {
                    val before = r.change.before
                    val after = r.change.after
                    val finished = after.quests.values.filter {
                        it.isRoot && it.status == QuestStatus.DONE && before.quests[it.id]?.status != QuestStatus.DONE
                    }
                    finished.forEach { _events.tryEmit(UiEvent.QuestCompleted(it.title, after.xp - before.xp)) }
                    val gained = after.xp - before.xp - xpAlreadyShown
                    if (finished.isEmpty() && gained > 0) _events.tryEmit(UiEvent.XpGained(gained))
                    (after.achievements.keys - before.achievements.keys).forEach { _events.tryEmit(UiEvent.AchievementUnlocked(it)) }
                    onOk()
                }
                is CommitResult.Rejected -> message(humanize(r.reason))
            }
        }
    }

    /** Runs a suspending UI task in the view model's scope (survives recomposition). */
    fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun message(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _events.tryEmit(UiEvent.Message(text, actionLabel, action))
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    fun acceptAll() {
        viewModelScope.launch {
            val n = actions.acceptAll()
            val left = repo.current().pendingProposals.size
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
