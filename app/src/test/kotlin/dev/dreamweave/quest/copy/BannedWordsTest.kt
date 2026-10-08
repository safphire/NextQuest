// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.copy

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue

/**
 * Calm copy (v1.1, C5): no shaming words in anything the user reads. Scans every string
 * resource and every string literal in the app, the data layer (sync messages) and the
 * core (refusal reasons the app shows). "missed" is allowed only in the forgiveness lines,
 * which say that one missed day barely matters, and in the brief's own Fresh start line
 * ("Nothing you missed carries forward", D-1119). Log messages are not user-facing.
 */
class BannedWordsTest {
    private val banned = Regex("""\b(overdue|failed|missed|lazy|behind|again)\b""", RegexOption.IGNORE_CASE)
    private val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Lines that aren't user-facing: comments and log calls. */
    private fun skip(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") ||
            "Log." in t || "Calm.attempt(" in t || "Calm.commit(" in t
    }

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun stringResources_useNoBannedWords() {
        val file = File(root(), "app/src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val problems = mutableListOf<String>()
        fun check(name: String, text: String, allowMissed: Boolean) {
            banned.findAll(text).map { it.value.lowercase() }.filterNot { allowMissed && it == "missed" }.forEach {
                problems += "$name: '$it' in \"$text\""
            }
        }
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            check(e.getAttribute("name"), e.textContent, allowMissed = e.getAttribute("name") in MISSED_ALLOWED)
        }
        val arrays = doc.getElementsByTagName("string-array")
        for (i in 0 until arrays.length) {
            val a = arrays.item(i) as Element
            val items = a.getElementsByTagName("item")
            for (j in 0 until items.length) check(a.getAttribute("name"), items.item(j).textContent, allowMissed = a.getAttribute("name") == "forgiveness_lines")
        }
        assertTrue(strings.length > 50, "the v1.1 copy section is in strings.xml")
        assertTrue(problems.isEmpty(), "Banned words in user-facing copy:\n" + problems.joinToString("\n"))
    }

    @Test
    fun stringLiterals_useNoBannedWords() {
        val dirs = listOf("app/src/main/kotlin", "data/src/main/kotlin", "core/src/main/kotlin").map { File(root(), it) }
        val problems = mutableListOf<String>()
        var scanned = 0
        for (dir in dirs) {
            dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (skip(line)) return@forEachIndexed
                    for (m in literal.findAll(line)) {
                        scanned++
                        banned.find(m.groupValues[1])?.let { problems += "${f.name}:${i + 1}: '${it.value}' in ${m.value}" }
                    }
                }
            }
        }
        assertTrue(scanned > 500, "scanned only $scanned literals")
        assertTrue(problems.isEmpty(), "Banned words in user-facing strings:\n" + problems.joinToString("\n"))
    }

    private companion object {
        /** The Fresh start line quoted by the brief (C1). */
        val MISSED_ALLOWED = setOf("fresh_body", "reset_fresh_hint")
    }

    @Test
    fun theScannerCatchesThem() {
        assertTrue(banned.containsMatchIn("Try AGAIN later"))
        assertTrue(banned.containsMatchIn("3 tasks overdue"))
        assertTrue(!banned.containsMatchIn("gain against the grain"))
        assertTrue(skip("    Log.e(TAG, \"sync failed\", e)"))
    }
}
