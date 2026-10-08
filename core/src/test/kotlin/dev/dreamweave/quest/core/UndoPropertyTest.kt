// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Undo is exact (v1.1, D-1112): a log with a completion and its undo folds to the same
 * quests, steps, XP, counters, streak, achievements and focus as the log without both —
 * on random histories, whether folded incrementally or from scratch.
 */
class UndoPropertyTest {
    private val tz = TimeZone.of("Europe/Berlin")

    private fun projection(s: QuestState, now: Long) = listOf(
        s.quests, s.steps, s.proposals, s.slotCap, s.counters, s.achievements, s.streak(now), s.focusBlocks, s.tracked.map { it.id },
    )

    @Test
    fun completionPlusUndo_equalsNeverHavingCompleted() {
        var undos = 0
        repeat(80) { round ->
            val rnd = Random(7_000 + round)
            val s = Scenario(tz, "2026-03-02T08:00:00Z", Ids.newId(Instant.parse("2026-03-02T08:00:00Z").toEpochMilliseconds(), rnd))
            val removed = HashSet<String>()
            fun id() = Ids.newId(s.now, rnd)
            repeat(rnd.nextInt(30, 120)) {
                s.now += rnd.nextLong(60_000, 20 * 3_600_000L)
                val st = s.state
                val roots = st.quests.values.filter { it.isRoot && it.status.isOpen }.sortedBy { it.id }
                when (rnd.nextInt(9)) {
                    0 -> s.commit(Events.questCreated(QuestCreated(id(), "Q", rhythm = if (rnd.nextInt(4) == 0) Rhythm(RhythmKind.DAILY) else null)))
                    1 -> roots.randomOrNull(rnd)?.let { s.commit(Events.stepSet(StepSet(id(), it.id, "s${rnd.nextInt(99)}", cue = "here".takeIf { rnd.nextBoolean() }))) }
                    2 -> roots.randomOrNull(rnd)?.let { s.commit(Events.questStatusSet(QuestStatusSet(it.id, QuestStatus.TRACKED))) }
                    3 -> roots.filter { !it.isPractice }.randomOrNull(rnd)?.let { s.commit(Events.questCreated(QuestCreated(id(), "sub", parentId = it.id))) }
                    4 -> roots.randomOrNull(rnd)?.let { r ->
                        st.children(r.id).filter { it.status.isOpen }.randomOrNull(rnd)?.let { c ->
                            s.commit(Events.questFocusSet(QuestFocusSet(r.id, c.id, next = NextStep(id(), c.id, "focus"))))
                        }
                    }
                    5 -> st.steps.values.filter { !it.superseded }.sortedBy { it.id }.randomOrNull(rnd)?.let {
                        s.commit(Events.focusBlockCompleted(FocusBlockCompleted(it.questId, it.id, 25, LocalStamp.of(s.now, tz))))
                    }
                    else -> {
                        // A completion, from any path, often undone right after.
                        val step = st.steps.values.filter { !it.isDone && !it.superseded }.sortedBy { it.id }.randomOrNull(rnd) ?: return@repeat
                        val finish = rnd.nextInt(4) == 0
                        val plan = Completion.plan(st, step.id, finish)
                        val via = StepVia.entries.random(rnd)
                        val r = s.commit(
                            Events.stepCompleted(
                                StepCompleted(
                                    step.id, via, finish,
                                    next = plan.nextOwnerId?.takeIf { plan.nextRequired || rnd.nextBoolean() }?.let { NextStep(id(), it, "n") },
                                    local = LocalStamp.of(s.now, tz),
                                ),
                            ),
                        )
                        if (r is Commits.Result.Ok && rnd.nextBoolean()) {
                            val completion = s.log.last()
                            s.now += rnd.nextLong(1_000, 10_000)
                            if (s.commit(Events.completionUndone(CompletionUndone(completion.id))) is Commits.Result.Ok) {
                                removed += completion.id
                                removed += s.log.last().id
                                undos++
                            }
                        }
                    }
                }
            }
            val withUndo = Fold.of(s.log, tz)
            val without = Fold.of(s.log.filter { it.id !in removed }, tz)
            assertEquals(projection(without, s.now), projection(withUndo, s.now), "round $round")
            // Incremental (the scenario's folder) == from scratch, and the counters recount.
            assertEquals(projection(withUndo, s.now), projection(s.state, s.now), "incremental round $round")
            assertEquals(Stats.recount(withUndo), withUndo.counters, "recount round $round")
            assertEquals(Achievement.unlockedBy(withUndo.counters), withUndo.achievements.keys, "achievements round $round")
        }
        assertTrue(undos > 100, "the generator must actually exercise undo ($undos)")
    }
}
