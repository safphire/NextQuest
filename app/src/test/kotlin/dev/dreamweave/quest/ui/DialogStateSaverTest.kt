// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import dev.dreamweave.quest.ui.board.TrackFlow
import dev.dreamweave.quest.ui.proposals.Ask
import dev.dreamweave.quest.ui.quest.Prompt
import dev.dreamweave.quest.ui.sheets.jsonSaver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every dialog state a screen keeps in its saved instance state round-trips through the
 * JSON saver. A serializer that failed on restore would make the dialog vanish silently.
 */
class DialogStateSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    private fun <T : Any> roundTrip(saver: Saver<T?, String>, value: T): T? {
        val saved = with(saver) { scope.save(value) } ?: error("nothing saved for $value")
        return saver.restore(saved)
    }

    @Test
    fun questDetailPrompts() {
        val all: List<Prompt> = listOf(
            Prompt.Rename, Prompt.Why, Prompt.SetStep("q"), Prompt.EditStep("s", "Write"), Prompt.Schedule("s", 5L, true),
            Prompt.Schedule("s", null, false), Prompt.AddSubquest, Prompt.LeaveNeedsStep("q", true, "o"), Prompt.FocusNeedsStep(null),
            Prompt.FocusNeedsStep("c"), Prompt.TrackNeedsStep, Prompt.Swap(null), Prompt.Swap("step"), Prompt.ConfirmDrop,
        )
        val saver = jsonSaver(Prompt.serializer())
        all.forEach { assertEquals(it, roundTrip(saver, it)) }
    }

    @Test
    fun boardTrackFlowAndProposalAsks() {
        val flow = jsonSaver(TrackFlow.serializer())
        listOf(TrackFlow("q"), TrackFlow("q", "First", true)).forEach { assertEquals(it, roundTrip(flow, it)) }
        val ask = jsonSaver(Ask.serializer())
        listOf(Ask.Edit("p"), Ask.NextStep("p", true, null), Ask.NextStep("p", null, "x"), Ask.Swap("p", null)).forEach {
            assertEquals(it, roundTrip(ask, it))
        }
        assertNull(ask.restore("not json"))
    }
}
