// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import dev.dreamweave.quest.R
import dev.dreamweave.quest.core.Breakdown
import dev.dreamweave.quest.core.QuestState

/**
 * "Ask AI to break this down" (B5): the Android share sheet with a ready prompt for Claude or
 * ChatGPT. No API keys, no in-app AI calls (D-1114). Returns false when nothing can receive it.
 */
object ShareAi {
    fun shareBreakdown(context: Context, state: QuestState, questId: String, paired: Boolean): Boolean {
        if (state.quests[questId] == null) return false
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, Breakdown.prompt(state, questId, paired))
        val chooser = Intent.createChooser(send, context.getString(R.string.ask_ai_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
