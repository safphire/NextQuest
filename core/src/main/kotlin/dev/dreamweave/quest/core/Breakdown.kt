// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

/**
 * "Ask AI to break this down" (v1.1): a ready prompt for Claude or ChatGPT, shared through
 * the Android share sheet. No API keys and no in-app AI calls (D-1114).
 */
object Breakdown {
    fun prompt(state: QuestState, questId: String, paired: Boolean): String {
        val q = state.quests[questId] ?: throw IllegalArgumentException("unknown quest $questId")
        val root = state.rootOf(questId)?.let { state.quests[it] } ?: q
        val step = if (q.isRoot) state.effectiveStep(q.id) else state.openStep(q.id)
        val kids = state.children(root.id).filter { it.status != QuestStatus.DROPPED }
        return buildString {
            appendLine("Help me break a goal into small, concrete steps.")
            appendLine()
            appendLine("Goal: ${q.title}")
            if (q.id != root.id) appendLine("Part of: ${root.title}")
            (q.why ?: root.why)?.let { appendLine("Why it matters: $it") }
            (q.obstacle ?: root.obstacle)?.let { appendLine("What's in the way: $it") }
            (q.plan ?: root.plan)?.let { appendLine("My plan for that: $it") }
            step?.let { appendLine("Current next step: ${it.text}" + (it.cue?.let { c -> " ($c)" } ?: "")) }
            if (kids.isNotEmpty()) {
                appendLine("Parts I already have:")
                kids.forEach { k -> appendLine("- ${k.title}" + if (k.status == QuestStatus.DONE) " (done)" else "") }
            }
            if (root.isPractice) {
                // Practices have no subquests: ask for smaller sessions instead.
                appendLine("This is an ongoing practice (${Practices.label(root.rhythm!!)}), not a project.")
                appendLine()
                appendLine("Suggest 3 to 7 small, concrete session ideas I could rotate through. The first one must be doable in under 10 minutes.")
                append("Reply with one idea per line and nothing else.")
                return@buildString
            }
            appendLine()
            appendLine("Suggest 3 to 7 small, concrete subquests, in order. The first one must be doable in under 10 minutes.")
            appendLine("Keep each one short: a verb and an object, no explanations.")
            if (paired) {
                appendLine()
                append("If you have my quest-log connector, submit each one with propose_add_subquest (parent_id: ${root.id}) ")
                appendLine("so they arrive in my app as proposals for me to approve. Otherwise just list them.")
            } else {
                appendLine("Reply with one subquest per line and nothing else, so I can paste the list straight into my inbox.")
            }
        }.trimEnd()
    }
}

/** Capture text that holds several items (e.g. a pasted AI reply): one inbox item per line. */
object CaptureText {
    private val marker = Regex("""^\s*(?:[-*•–]|\d{1,3}[.)]|\[[ xX]?])\s+""")

    /** The items in [text]: one per non-blank line, list markers removed, at most [Limits.MAX_TITLE] characters each. */
    fun items(text: String): List<String> =
        text.lines()
            .map { it.replace(marker, "").trim().trim('*', '_').trim() }
            .filter { it.isNotEmpty() }
            .map { it.take(Limits.MAX_TITLE) }

    /** True when [text] holds more than one item, so capture saves each line separately. */
    fun isList(text: String): Boolean = items(text).size > 1
}
