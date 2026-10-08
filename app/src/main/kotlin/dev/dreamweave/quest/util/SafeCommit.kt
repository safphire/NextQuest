// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.util

import android.util.Log
import dev.dreamweave.quest.data.Change
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.ui.QuestViewModel
import kotlinx.coroutines.CancellationException

/** What a user action outside the main screen (alarm screen, widget dialog) came to. */
sealed interface ActionOutcome {
    data class Done(val change: Change) : ActionOutcome

    /** The rules refused it (e.g. the step moved on): [message] says why, calmly. */
    data class Refused(val message: String) : ActionOutcome

    /** Something broke underneath (storage full, database error): logged, never a crash. */
    data class Failed(val message: String) : ActionOutcome
}

/**
 * Background and out-of-app entry points must fail quietly: a failure is logged and turned
 * into a calm message instead of taking the process down with it.
 */
object Calm {
    const val MESSAGE = "That didn't go through. Your quests are safe — give it another try in a moment."

    /** Runs [block]; returns null (after logging) if it threw. Cancellation still propagates. */
    suspend fun <T> attempt(tag: String, what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.w(tag, "$what didn't complete", e) }
        null
    }

    /** Runs one commit and classifies the result. */
    suspend fun commit(tag: String, what: String, block: suspend () -> CommitResult): ActionOutcome =
        when (val r = attempt(tag, what, block)) {
            null -> ActionOutcome.Failed(MESSAGE)
            is CommitResult.Ok -> ActionOutcome.Done(r.change)
            is CommitResult.Rejected -> ActionOutcome.Refused(QuestViewModel.humanize(r.reason))
        }
}
