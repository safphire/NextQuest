package com.nextquest.core

object Tracking {
    /** Tracked cap is user-configurable within this range. */
    val CAP_RANGE = 3..5
    const val DEFAULT_CAP = 5

    fun clampCap(cap: Int): Int = cap.coerceIn(CAP_RANGE)

    /** Hard cap on tracked root quests: to track a new one, finish or untrack one. */
    fun canTrack(trackedRootCount: Int, cap: Int = DEFAULT_CAP): Boolean =
        trackedRootCount < clampCap(cap)
}
