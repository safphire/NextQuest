// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Robolectric creates a fresh [QuestApp] per test, but some shadow state is static (posted
 * notifications, for one). A debounced refresh left running in the previous test's app scope
 * would judge the next test's notifications against its own, older repository and cancel
 * them as stale. Call this in @After so each test's background work ends with the test.
 */
fun QuestApp.stopBackgroundWork() {
    runBlocking { withTimeoutOrNull(10_000) { container.appScope.coroutineContext.job.cancelAndJoin() } }
}
