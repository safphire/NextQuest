// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultMarkdownTest {
    @Test
    fun rendersOneNotePerQuestWithFrontMatterStepSubquestsAndLog() {
        val s = Scenario(start = "2026-07-23T14:02:00Z")
        val root = s.tracked("Ship: the app?", "Write release notes")
        s.ok(Events.questEdited(QuestEdited(root, why = "People are waiting")))
        val a = s.sub(root, "Approve design")
        s.advanceMinutes(1)
        val b = s.sub(root, "Code it")
        s.ok(Events.questCompleted(QuestCompleted(a)))
        s.ok(Events.questFocusSet(QuestFocusSet(root, b, next = s.next(b, "Open the editor"))))
        val st = s.stepOf(root)
        s.ok(Events.stepEdited(StepEdited(st, dueAt = Instant.parse("2026-07-24T09:00:00Z").toEpochMilliseconds())))

        val notes = VaultMarkdown.render(s.state, s.tz).associateBy { it.questId }
        assertEquals(3, notes.size)
        val r = notes.getValue(root)
        assertEquals("Ship the app.md", r.fileName)
        assertTrue(r.content.startsWith("---\nid: $root\ntype: quest\nstatus: tracked\n"))
        assertContains(r.content, "created: 2026-07-23")
        assertContains(r.content, "# Ship: the app?")
        assertContains(r.content, "> People are waiting")
        assertContains(r.content, "next-step:: Open the editor")
        assertContains(r.content, "due:: 2026-07-24 09:00")
        assertContains(r.content, "next-step-from:: [[Code it]]")
        assertContains(r.content, "- [x] [[Approve design]]")
        assertContains(r.content, "- [ ] [[Code it]] (focused)")
        assertContains(r.content, "## Log")
        assertContains(r.content, "· Tracked “Ship: the app?”")
        val child = notes.getValue(a).content
        assertContains(child, "type: subquest")
        assertContains(child, "status: done")
        assertContains(child, "parent: \"[[Ship the app]]\"")
        assertEquals(root, VaultMarkdown.idOf(r.content))
    }

    @Test
    fun namesAreSafeAndUnique() {
        val s = Scenario()
        val x = s.capture("Plan / trip #1")
        s.advanceMinutes(1)
        val y = s.capture("plan  trip 1")
        val names = VaultMarkdown.names(s.state)
        assertEquals("Plan trip 1", names[minOf(x, y)])
        assertEquals("plan trip 1 (${maxOf(x, y).takeLast(6)})", names[maxOf(x, y)])
        assertEquals("Untitled", VaultMarkdown.sanitize("///"))
        assertEquals(80, VaultMarkdown.sanitize("x".repeat(200)).length)
        assertNull(VaultMarkdown.idOf("# no front matter"))
        assertNull(VaultMarkdown.idOf("---\nid: nope\n---\n"))
    }

    @Test
    fun namesStepAsideForFilesThatAreNotTheQuestsOwnNote() {
        val s = Scenario()
        val root = s.tracked("Learn piano", "Find a teacher")
        val child = s.sub(root, "Scales")
        val short = "Learn piano (${root.takeLast(6)})"

        // A user's own note with the same name (any case): the quest takes a suffixed name,
        // and every wikilink to it follows.
        val notes = VaultMarkdown.render(s.state, s.tz, occupied = mapOf("learn piano" to null)).associateBy { it.questId }
        assertEquals("$short.md", notes.getValue(root).fileName)
        assertContains(notes.getValue(child).content, "parent: \"[[$short]]\"")
        assertEquals("Scales.md", notes.getValue(child).fileName)

        // The quest's own note (same id) doesn't block the name; another quest's does.
        assertEquals("Learn piano", VaultMarkdown.names(s.state, mapOf("learn piano" to root))[root])
        assertEquals(short, VaultMarkdown.names(s.state, mapOf("learn piano" to child))[root])

        // Even the short suffix taken by a foreign file: fall back to the full id.
        val both = mapOf("learn piano" to null, short.lowercase() to null)
        assertEquals("Learn piano ($root)", VaultMarkdown.names(s.state, both)[root])
    }

    @Test
    fun duplicateTitlesKeepThePlainNameForTheQuestWhoseNoteHoldsIt() {
        val s = Scenario()
        val x = s.capture("Trip")
        s.advanceMinutes(1)
        val y = s.capture("trip")
        val (first, second) = listOf(x, y).sorted()
        // Normally the older id gets the plain name...
        assertEquals("Trip", VaultMarkdown.names(s.state)[x])
        // ...but if the existing "trip.md" is the newer quest's note, it keeps it.
        val names = VaultMarkdown.names(s.state, mapOf("trip" to second))
        assertEquals("trip", names[second])
        assertEquals("Trip (${first.takeLast(6)})", names[first])
    }

    @Test
    fun userTextCannotInjectMarkdownStructureOrDataviewFields() {
        val s = Scenario()
        val root = s.tracked("Title\n# Fake heading\r\nstatus:: done", "Step\u2028- [x] fake\u2029`code`")
        s.ok(Events.questEdited(QuestEdited(root, why = "> quote\n%%hidden%%\n```")))
        val note = VaultMarkdown.render(s.state, s.tz).single().content
        val body = note.substringAfter("\n---\n")
        // No user text starts a line of its own.
        assertTrue(body.lines().none { it.startsWith("# Fake") || it.startsWith("status::") || it.startsWith("- [x] fake") })
        assertContains(body, "# Title # Fake heading status:\\: done")
        assertContains(body, "next-step:: Step - [x] fake \\`code\\`")
        assertContains(body, "> \\> quote %\\%hidden%\\% \\`\\`\\`")
        assertTrue("status:: done" !in body)
        assertTrue("%%" !in body)
        assertEquals(1, Regex("^status: ", RegexOption.MULTILINE).findAll(note).count())

        assertEquals("\\# tag", VaultMarkdown.inline("# tag"))
        assertEquals("\\- item", VaultMarkdown.inline("  - item"))
        assertEquals("\\| a | b |", VaultMarkdown.inline("| a | b |"))
        assertEquals("1\\. first", VaultMarkdown.inline("1. first"))
        assertEquals("2\\) second", VaultMarkdown.inline("2) second"))
        assertEquals("a:\\:\\:b", VaultMarkdown.inline("a:::b"))
        assertEquals("plain text: fine", VaultMarkdown.inline("plain text: fine"))
    }

    @Test
    fun sanitizeCutsBetweenCodePointsAndAvoidsReservedAndHiddenNames() {
        val emoji = "\uD83D\uDE00" // one code point, two UTF-16 units
        val cut = VaultMarkdown.sanitize("a" + emoji.repeat(60))
        assertTrue(cut.length <= VaultMarkdown.MAX_NAME_CHARS)
        assertTrue(!Character.isHighSurrogate(cut.last()), "no half surrogate pair at the end")
        assertEquals(cut.codePointCount(0, cut.length) * 2 - 1, cut.length)
        // A lone surrogate is not a valid file name character.
        assertEquals("a b", VaultMarkdown.sanitize("a\uD83Db"))
        // UTF-8 budget: 3-byte characters stop at 60.
        assertEquals(VaultMarkdown.MAX_NAME_BYTES / 3, VaultMarkdown.sanitize("\u4E2D".repeat(100)).length)
        // Trailing dots and spaces after the cut, leading dots always.
        assertEquals("x".repeat(78), VaultMarkdown.sanitize("x".repeat(78) + ". tail"))
        assertEquals("hidden", VaultMarkdown.sanitize("..hidden"))
        assertEquals("Untitled", VaultMarkdown.sanitize(" . . "))
        assertEquals("a b", VaultMarkdown.sanitize("a\u0000\u007F\u2028b"))
        // Windows device names, any case, with or without an extension.
        assertEquals("CON_", VaultMarkdown.sanitize("CON"))
        assertEquals("nul_", VaultMarkdown.sanitize("nul"))
        assertEquals("Lpt9_", VaultMarkdown.sanitize("Lpt9"))
        assertEquals("com1_.txt", VaultMarkdown.sanitize("com1.txt"))
        assertEquals("aux_.tar.gz", VaultMarkdown.sanitize("aux.tar.gz"))
        assertEquals("COM\u00B9_", VaultMarkdown.sanitize("COM\u00B9"))
        assertEquals("Console", VaultMarkdown.sanitize("Console"))
        assertEquals("com10", VaultMarkdown.sanitize("com10"))
    }
}
