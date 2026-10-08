// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.Serializable

/**
 * One focus block on a step (v1.1). The app keeps it on disk while it runs, so the timer
 * survives process death; the arithmetic lives here so it is JVM-tested.
 */
@Serializable
data class FocusSession(
    val questId: String,
    val stepId: String,
    val stepText: String,
    val minutes: Int,
    /** When the block ends; null while paused. */
    val endsAt: Long?,
    /** Time left when paused (and the full length before it starts). */
    val remainingMs: Long,
    /** The block ran to its end and the user hasn't answered "Did you finish the step?" yet. */
    val ended: Boolean = false,
) {
    val paused: Boolean get() = endsAt == null && !ended

    fun leftMs(now: Long): Long = when {
        ended -> 0
        endsAt != null -> (endsAt - now).coerceAtLeast(0)
        else -> remainingMs
    }

    /** Running, and its end has come. */
    fun isOver(now: Long): Boolean = !ended && endsAt != null && now >= endsAt

    fun pause(now: Long): FocusSession = if (endsAt == null || ended) this else copy(endsAt = null, remainingMs = leftMs(now))

    fun resume(now: Long): FocusSession = if (!paused) this else copy(endsAt = now + remainingMs)

    fun end(): FocusSession = copy(ended = true, endsAt = null, remainingMs = 0)

    companion object {
        fun start(questId: String, stepId: String, stepText: String, minutes: Int, now: Long): FocusSession {
            require(minutes in Focus.MIN_MINUTES..Focus.MAX_MINUTES) { "a focus block is ${Focus.MIN_MINUTES}–${Focus.MAX_MINUTES} minutes" }
            val length = minutes * 60_000L
            return FocusSession(questId, stepId, stepText, minutes, endsAt = now + length, remainingMs = length)
        }

        /** "24:59", "1:05:00". */
        fun format(ms: Long): String {
            val total = (ms + 999) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
        }
    }
}

/**
 * Cyclic sighing (v1.1 Breathe): two inhales through the nose (the second one short, to top
 * up the lungs), then a long, slow exhale. One cycle is [CYCLE_MS]; a session runs whole
 * minutes. Pure timing, shared by the animation, the text count and the haptics.
 */
object Breathing {
    enum class Phase { INHALE, TOP_UP, EXHALE }

    const val INHALE_MS = 2_500L
    const val TOP_UP_MS = 1_000L
    const val EXHALE_MS = 5_500L
    const val CYCLE_MS = INHALE_MS + TOP_UP_MS + EXHALE_MS
    val MINUTES = listOf(1, 3, 5)

    data class Moment(
        /** 1-based breath number. */
        val breath: Int,
        val phase: Phase,
        /** 0..1 through the current phase. */
        val progress: Float,
        /** 0..1 how full the lungs are (drives the shape's size). */
        val fullness: Float,
        val leftMs: Long,
        val finished: Boolean,
    )

    fun at(elapsedMs: Long, minutes: Int): Moment {
        val total = minutes * 60_000L
        val e = elapsedMs.coerceIn(0, total)
        if (e >= total) return Moment(cycles(minutes), Phase.EXHALE, 1f, 0f, 0, finished = true)
        val inCycle = e % CYCLE_MS
        val breath = (e / CYCLE_MS).toInt() + 1
        val (phase, start, length) = when {
            inCycle < INHALE_MS -> Triple(Phase.INHALE, 0L, INHALE_MS)
            inCycle < INHALE_MS + TOP_UP_MS -> Triple(Phase.TOP_UP, INHALE_MS, TOP_UP_MS)
            else -> Triple(Phase.EXHALE, INHALE_MS + TOP_UP_MS, EXHALE_MS)
        }
        val p = ((inCycle - start).toFloat() / length).coerceIn(0f, 1f)
        val fullness = when (phase) {
            Phase.INHALE -> 0.8f * p
            Phase.TOP_UP -> 0.8f + 0.2f * p
            Phase.EXHALE -> 1f - p
        }
        return Moment(breath, phase, p, fullness, total - e, finished = false)
    }

    /** Whole breaths a session of [minutes] holds (the last one may be cut short). */
    fun cycles(minutes: Int): Int = ((minutes * 60_000L + CYCLE_MS - 1) / CYCLE_MS).toInt()

    /** When each phase starts, from the session start: what the haptics play. */
    fun phaseStarts(minutes: Int): List<Pair<Long, Phase>> {
        val total = minutes * 60_000L
        val out = ArrayList<Pair<Long, Phase>>()
        var t = 0L
        while (t < total) {
            out += t to Phase.INHALE
            if (t + INHALE_MS < total) out += (t + INHALE_MS) to Phase.TOP_UP
            if (t + INHALE_MS + TOP_UP_MS < total) out += (t + INHALE_MS + TOP_UP_MS) to Phase.EXHALE
            t += CYCLE_MS
        }
        return out
    }
}
