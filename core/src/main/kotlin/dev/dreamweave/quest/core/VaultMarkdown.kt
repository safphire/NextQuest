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

    /**
     * Characters that are unsafe in a file name on some platform, or that break a wikilink:
     * path and wildcard characters, `#^[]|`, control characters, Unicode line separators
     * and unpaired surrogates (Java regex matches a valid pair as one code point).
     */
    private val unsafe = Regex("""[\\/:*?"<>|#^\[\]\u0000-\u001F\u007F\u2028\u2029\uD800-\uDFFF]""")
    private val spaces = Regex("\\s+")

    /** Windows device names, also reserved with any extension (`con.txt`). */
    private val reserved = Regex("^(con|prn|aux|nul|com[0-9\u00B9\u00B2\u00B3]|lpt[0-9\u00B9\u00B2\u00B3])$", RegexOption.IGNORE_CASE)

    const val MAX_NAME_CHARS = 80

    /** UTF-8 budget for a name, leaving room for a full-id suffix under the usual 255-byte limit. */
    const val MAX_NAME_BYTES = 180

    /** A file-system and wikilink safe title. */
    fun sanitize(title: String): String {
        val cleaned = title.replace(unsafe, " ").replace(spaces, " ").trim().trimStart('.', ' ')
        val name = clip(cleaned).trimEnd(' ', '.').ifEmpty { "Untitled" }
        val dot = name.indexOf('.')
        val base = if (dot < 0) name else name.substring(0, dot)
        if (!reserved.matches(base.trimEnd())) return name
        return if (dot < 0) name + "_" else base + "_" + name.substring(dot)
    }

    /** At most [MAX_NAME_CHARS] UTF-16 units and [MAX_NAME_BYTES] UTF-8 bytes, cut between code points. */
    private fun clip(s: String): String {
        var i = 0
        var bytes = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val units = Character.charCount(cp)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (i + units > MAX_NAME_CHARS || bytes + size > MAX_NAME_BYTES) break
            i += units
            bytes += size
        }
        return s.substring(0, i)
    }

    /**
     * Note names (without `.md`) for every quest. Collisions get a short id suffix, and so
     * does a quest whose name is [occupied] by a file that isn't its own note. Stable for a
     * given state and folder.
     *
     * [occupied] maps a lowercased note name to the quest id found in that file's front
     * matter, or null when the file isn't one of this app's notes. A file holding the
     * quest's own id doesn't block the name.
     */
    fun names(state: QuestState, occupied: Map<String, String?> = emptyMap()): Map<String, String> {
        val byName = state.quests.values.sortedBy { it.id }.groupBy { sanitize(it.title).lowercase() }
        val out = HashMap<String, String>()
        val used = HashSet<String>()
        val rest = mutableListOf<Quest>()
        // Plain names first, so a suffixed name can never take one of them.
        for ((key, group) in byName) {
            val claimant = if (key in occupied) group.firstOrNull { it.id == occupied[key] } else group.first()
            if (claimant != null) {
                out[claimant.id] = sanitize(claimant.title)
                used += key
            }
            rest += group.filter { it.id != claimant?.id }
        }
        fun free(name: String, id: String): Boolean {
            val key = name.lowercase()
            return key !in used && (key !in occupied || occupied[key] == id)
        }
        for (q in rest.sortedBy { it.id }) {
            val base = sanitize(q.title)
            val short = "$base (${q.id.takeLast(6)})"
            val name = if (free(short, q.id)) short else "$base (${q.id})"
            out[q.id] = name
            used += name.lowercase()
        }
        return out
    }

    /** [nowMs] dates a practice's "this week" line. */
    fun render(state: QuestState, tz: TimeZone, occupied: Map<String, String?> = emptyMap(), nowMs: Long = System.currentTimeMillis()): List<Note> {
        val names = names(state, occupied)
        return state.quests.values.sortedBy { it.id }.map { q -> Note(q.id, names.getValue(q.id) + ".md", note(state, q, names, tz, nowMs)) }
    }

    private val lineBreaks = Regex("[\r\n\u000B\u000C\u0085\u2028\u2029]+")
    private val doubleColon = Regex(":(?=:)")
    private val doublePercent = Regex("%(?=%)")
    private val orderedMarker = Regex("^(\\d{1,9})([.)])")
    private const val BLOCK_MARKERS = "#>-+*|~<[="

    /**
     * User text (titles, steps, why, log lines) made inert for one line of the body: line
     * breaks collapse to spaces, and nothing in it can start a heading, quote, list, table,
     * code span or fence, an Obsidian comment (`%%`) or a Dataview inline field (`key:: value`).
     * Backslash escapes render as the plain character in Markdown.
     */
    fun inline(text: String): String {
        var t = text.replace(lineBreaks, " ").trim()
        t = t.replace("`", "\\`")
        t = t.replace(doubleColon) { ":\\" }
        t = t.replace(doublePercent) { "%\\" }
        t = orderedMarker.replace(t) { it.groupValues[1] + "\\" + it.groupValues[2] }
        if (t.isNotEmpty() && t[0] in BLOCK_MARKERS) t = "\\" + t
        return t
    }

    private fun date(ms: Long, tz: TimeZone) = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz).date.toString()

    private fun dateTime(ms: Long, tz: TimeZone): String {
        val dt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(tz)
        return "${dt.date} ${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    }

    private fun yamlString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun note(state: QuestState, q: Quest, names: Map<String, String>, tz: TimeZone, nowMs: Long): String = buildString {
        appendLine("---")
        appendLine("id: ${q.id}")
        appendLine("type: ${if (q.isPractice) "practice" else if (q.isRoot) "quest" else "subquest"}")
        appendLine("status: ${q.status.name.lowercase()}")
        q.rhythm?.let { appendLine("rhythm: ${yamlString(Practices.label(it))}") }
        q.parentId?.let { p -> names[p]?.let { appendLine("parent: ${yamlString("[[$it]]")}") } }
        appendLine("created: ${date(q.createdAt, tz)}")
        appendLine("completed: ${q.completedAt?.let { date(it, tz) } ?: ""}")
        appendLine("tags: [quest]")
        appendLine("---")
        appendLine()
        appendLine("# ${inline(q.title)}")
        appendLine()
        q.why?.let {
            appendLine("> ${inline(it)}")
            appendLine()
        }
        if (q.obstacle != null || q.plan != null) {
            q.obstacle?.let { appendLine("obstacle:: ${inline(it)}") }
            q.plan?.let { appendLine("plan:: ${inline(it)}") }
            appendLine()
        }
        val step = if (q.isRoot) state.effectiveStep(q.id) else state.openStep(q.id)
        if (q.status.isOpen) {
            appendLine("${if (q.isPractice) "next-session" else "next-step"}:: ${step?.text?.let(::inline) ?: "(none yet)"}")
            step?.cue?.let { appendLine("cue:: ${inline(it)}") }
            step?.dueAt?.let { appendLine("due:: ${dateTime(it, tz)}") }
            q.rhythm?.let { r ->
                val week = Practices.week(r, Practices.sessionDays(state, q.id), Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date)
                appendLine("this-week:: ${week.done}/${week.target}")
            }
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
                appendLine("- ${dateTime(h.ts, tz)} · ${inline(h.text)}${if (h.xp > 0) " (+${h.xp} XP)" else ""}")
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
