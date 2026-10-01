// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The vault mirror (one-way, app → folder): one Markdown note per quest and subquest,
 * Obsidian/Dataview friendly. Pure: the app only writes the strings to disk.
 *
 * ```
 * ---
 * id: 0199…
 * type: quest
 * status: tracked
 * parent: "[[Ship the app]]"     # subquests only
 * created: 2026-07-23
 * completed:
 * tags: [quest]
 * ---
 *
 * # Build memory architecture
 *
 * > Why it matters
 *
 * next-step:: Draft the layer split
 * due:: 2026-07-24 09:00
 *
 * ## Subquests
 * - [x] [[Approve design]]
 * - [ ] [[Code it]] (focused)
 *
 * ## Log
 * - 2026-07-23 14:02 · Captured “Build memory architecture”
 * ```
 */
object VaultMarkdown {
    data class Note(val questId: String, val fileName: String, val content: String)

    private val unsafe = Regex("""[\\/:*?"<>|#^\[\]\n\r\t]""")

    /** A file-system and wikilink safe title. */
    fun sanitize(title: String): String =
        title.replace(unsafe, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(80).ifEmpty { "Untitled" }

    /** Note names for every quest; collisions get a short id suffix. Stable for a given state. */
    fun names(state: QuestState): Map<String, String> {
        val byName = state.quests.values.sortedBy { it.id }.groupBy { sanitize(it.title).lowercase() }
        val out = HashMap<String, String>()
        for ((_, group) in byName) {
            group.forEachIndexed { i, q ->
                out[q.id] = if (i == 0) sanitize(q.title) else "${sanitize(q.title)} (${q.id.takeLast(6)})"
            }
        }
        return out
    }

    fun render(state: QuestState, tz: TimeZone): List<Note> {
        val names = names(state)
        return state.quests.values.sortedBy { it.id }.map { q -> Note(q.id, names.getValue(q.id) + ".md", note(state, q, names, tz)) }
    }

    private fun date(ms: Long, tz: TimeZone) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz).date.toString()

    private fun dateTime(ms: Long, tz: TimeZone): String {
        val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        return "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    }

    private fun yamlString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun note(state: QuestState, q: Quest, names: Map<String, String>, tz: TimeZone): String = buildString {
        appendLine("---")
        appendLine("id: ${q.id}")
        appendLine("type: ${if (q.isRoot) "quest" else "subquest"}")
        appendLine("status: ${q.status.name.lowercase()}")
        q.parentId?.let { p -> names[p]?.let { appendLine("parent: ${yamlString("[[$it]]")}") } }
        appendLine("created: ${date(q.createdAt, tz)}")
        appendLine("completed: ${q.completedAt?.let { date(it, tz) } ?: ""}")
        appendLine("tags: [quest]")
        appendLine("---")
        appendLine()
        appendLine("# ${q.title}")
        appendLine()
        q.why?.let {
            appendLine("> $it")
            appendLine()
        }
        val step = if (q.isRoot) state.effectiveStep(q.id) else state.openStep(q.id)
        if (q.status.isOpen) {
            appendLine("next-step:: ${step?.text ?: "(none yet)"}")
            step?.dueAt?.let { appendLine("due:: ${dateTime(it, tz)}") }
            if (step != null && q.isRoot && step.questId != q.id) names[step.questId]?.let { appendLine("next-step-from:: [[$it]]") }
            appendLine()
        }
        val children = state.children(q.id).filter { it.status != QuestStatus.DROPPED }
        if (children.isNotEmpty()) {
            appendLine("## Subquests")
            for (c in children) {
                val mark = if (c.status == QuestStatus.DONE) "x" else " "
                val focus = if (q.focusedChildId == c.id) " (focused)" else ""
                appendLine("- [$mark] [[${names.getValue(c.id)}]]$focus")
            }
            appendLine()
        }
        val log = state.history.filter { it.questId == q.id }
        if (log.isNotEmpty()) {
            appendLine("## Log")
            for (h in log) {
                appendLine("- ${dateTime(h.ts, tz)} · ${h.text}${if (h.xp > 0) " (+${h.xp} XP)" else ""}")
            }
        }
    }.trimEnd() + "\n"

    /** Reads the `id:` from a note's front matter, to recognise notes this app wrote. */
    fun idOf(content: String): String? {
        if (!content.startsWith("---")) return null
        val end = content.indexOf("\n---", 3).takeIf { it > 0 } ?: return null
        return content.substring(3, end).lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("id:") }?.removePrefix("id:")?.trim()?.takeIf { Ids.isValid(it) }
    }
}
