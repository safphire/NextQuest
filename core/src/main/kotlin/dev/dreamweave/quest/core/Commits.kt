// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

/**
 * Local writes are all-or-nothing: a commit (one user action, possibly several events)
 * is first folded onto a copy of the current state, and is only appended to the log if
 * every event is accepted. The UI therefore can never write an event the fold would
 * refuse — the cap, the next-step rule and the actor rules hold before anything is stored.
 */
object Commits {
    sealed interface Result {
        data class Ok(val folder: Folder) : Result
        data class Rejected(val event: Event, val reason: String) : Result
    }

    fun validate(current: Folder, events: List<Event>): Result {
        val trial = current.copy()
        for (e in events) {
            when (val outcome = trial.apply(e)) {
                Outcome.Applied -> Unit
                is Outcome.Skipped -> return Result.Rejected(e, outcome.reason)
            }
        }
        return Result.Ok(trial)
    }
}
