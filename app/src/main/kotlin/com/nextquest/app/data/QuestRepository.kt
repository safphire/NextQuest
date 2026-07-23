package com.nextquest.app.data

import com.nextquest.core.EventType
import com.nextquest.core.QuestStatus
import com.nextquest.core.Tracking
import com.nextquest.core.Xp
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface TrackResult {
    data object Tracked : TrackResult
    /** Cap reached: finish or untrack one first. */
    data class AtCap(val cap: Int) : TrackResult
}

class QuestRepository(
    private val quests: QuestDao,
    private val events: EventLogDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val trackedRoots: Flow<List<QuestEntity>> = quests.observeTrackedRoots()
    val boardRoots: Flow<List<QuestEntity>> = quests.observeBoardRoots()

    /** XP is derived from the EventLog, never stored (CLAUDE.md). */
    val totalXp: Flow<Int> =
        events.observeTypes().map { types -> Xp.total(types.mapNotNull(::eventTypeOf)) }

    suspend fun capture(title: String): QuestEntity? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val t = now()
        val quest = QuestEntity(
            id = UUID.randomUUID().toString(),
            title = trimmed,
            createdAt = t,
            touchedAt = t,
        )
        quests.insert(quest)
        log(quest.id, EventType.CREATED, payload = trimmed, at = t)
        return quest
    }

    suspend fun track(id: String, cap: Int = Tracking.DEFAULT_CAP): TrackResult {
        if (!Tracking.canTrack(quests.trackedRootCount(), cap)) {
            return TrackResult.AtCap(Tracking.clampCap(cap))
        }
        val quest = quests.byId(id) ?: return TrackResult.Tracked
        val t = now()
        quests.update(quest.copy(tracked = true, touchedAt = t))
        log(id, EventType.TRACKED, at = t)
        return TrackResult.Tracked
    }

    suspend fun untrack(id: String) {
        val quest = quests.byId(id) ?: return
        val t = now()
        quests.update(quest.copy(tracked = false, touchedAt = t))
        log(id, EventType.UNTRACKED, at = t)
    }

    suspend fun setNextStep(id: String, step: String?) {
        val quest = quests.byId(id) ?: return
        quests.update(quest.copy(nextStep = step?.trim()?.ifEmpty { null }, touchedAt = now()))
    }

    /** Completes the current next step: +15 XP, step text goes to the log, slot clears. */
    suspend fun completeStep(id: String): Int {
        val quest = quests.byId(id) ?: return 0
        val step = quest.nextStep?.trim().orEmpty()
        if (step.isEmpty()) return 0
        val t = now()
        quests.update(quest.copy(nextStep = null, touchedAt = t))
        log(id, EventType.STEP_DONE, payload = step, at = t)
        return Xp.STEP_DONE
    }

    /** Completes the whole quest: +100 XP, leaves Tracked via status. */
    suspend fun completeQuest(id: String): Int {
        val quest = quests.byId(id) ?: return 0
        if (quest.isDone) return 0
        val t = now()
        quests.update(
            quest.copy(status = QuestStatus.DONE.name, completedAt = t, touchedAt = t)
        )
        log(id, EventType.QUEST_DONE, payload = quest.title, at = t)
        return Xp.QUEST_DONE
    }

    private suspend fun log(questId: String, type: EventType, payload: String? = null, at: Long) {
        events.insert(
            EventLogEntity(
                id = UUID.randomUUID().toString(),
                questId = questId,
                type = type.name,
                payload = payload,
                at = at,
            )
        )
    }

    private fun eventTypeOf(name: String): EventType? =
        EventType.entries.firstOrNull { it.name == name }
}
