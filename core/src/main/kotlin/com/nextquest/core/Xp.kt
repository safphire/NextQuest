package com.nextquest.core

data class LevelProgress(
    val level: Int,
    /** XP earned inside the current level. */
    val intoLevel: Int,
    /** XP required to go from the current level to the next. */
    val forNext: Int,
) {
    val fraction: Float get() = if (forNext == 0) 0f else intoLevel.toFloat() / forNext
}

object Xp {
    const val STEP_DONE = 15
    const val SUBQUEST_DONE = 40
    const val QUEST_DONE = 100

    fun forEvent(type: EventType): Int = when (type) {
        EventType.STEP_DONE -> STEP_DONE
        EventType.SUBQUEST_DONE -> SUBQUEST_DONE
        EventType.QUEST_DONE -> QUEST_DONE
        EventType.CREATED, EventType.TRACKED, EventType.UNTRACKED -> 0
    }

    fun total(events: Iterable<EventType>): Int = events.sumOf(::forEvent)

    /** XP required to advance from [level] to the next one: 100 + 50·(n−1). Levels start at 1. */
    fun requiredFor(level: Int): Int {
        require(level >= 1) { "levels start at 1" }
        return 100 + 50 * (level - 1)
    }

    fun progress(totalXp: Int): LevelProgress {
        require(totalXp >= 0) { "XP cannot be negative" }
        var level = 1
        var remaining = totalXp
        while (remaining >= requiredFor(level)) {
            remaining -= requiredFor(level)
            level++
        }
        return LevelProgress(level, remaining, requiredFor(level))
    }
}
