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
}
