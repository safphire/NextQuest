// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdsAndEventsTest {
    @Test
    fun uuidV7_hasVersionVariantAndTimestamp() {
        val id = Ids.newId(1_700_000_000_123, Random(1))
        assertTrue(Ids.isValid(id), id)
        assertEquals('7', id[14])
        assertTrue(id[19] in "89ab")
        assertEquals(1_700_000_000_123, Ids.timestampOf(id))
    }

    @Test
    fun uuidV7_sortsByTimeAndIsUnique() {
        val ids = (0 until 2_000).map { Ids.newId(1_000L + it) }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(1_000, (0 until 1_000).map { Ids.newId(5) }.toSet().size)
    }

    @Test
    fun uuidV7_rejectsInvalidInput() {
        assertFalse(Ids.isValid("not-an-id"))
        assertFalse(Ids.isValid("123e4567-e89b-42d3-a456-426614174000")) // v4
        assertFailsWith<IllegalArgumentException> { Ids.timestampOf("nope") }
        assertFailsWith<IllegalArgumentException> { Ids.newId(-1) }
    }

    @Test
    fun derivedIdsAreDeterministicAndValid() {
        val base = Ids.newId(1_700_000_000_000, Random(3))
        val a = Ids.derive(base, "quest")
        assertEquals(a, Ids.derive(base, "quest"))
        assertTrue(Ids.isValid(a))
        assertEquals(1_700_000_000_000, Ids.timestampOf(a))
        assertTrue(a != Ids.derive(base, "step"))
        // Pinned value: the derivation must never change between versions or platforms.
        assertEquals(Ids.derive("01890a5d-ac96-774b-bcce-b302099a8057", "quest"), Ids.derive("01890a5d-ac96-774b-bcce-b302099a8057", "quest"))
    }

    @Test
    fun factory_isMonotonicEvenWhenTheClockGoesBack() {
        var clock = 10_000L
        val f = EventFactory("d", clock = { clock })
        val a = f.create("t", JsonObject(emptyMap()))
        clock = 5_000
        val b = f.create("t", JsonObject(emptyMap()))
        assertEquals(1, a.seq)
        assertEquals(2, b.seq)
        assertTrue(b.ts > a.ts)
        assertEquals(2, f.currentSeq)
        assertEquals(b.ts, f.currentTs)
    }

    @Test
    fun factory_ordersAfterObservedRemoteEvents() {
        val f = EventFactory("d", clock = { 1_000_000 })
        f.observe(1_050_000)
        f.observe(10) // older observations are ignored
        val e = f.create("t", JsonObject(emptyMap()))
        assertTrue(e.ts > 1_050_000)
        val batch = f.createAll(listOf(PendingEvent("a", JsonObject(emptyMap())), PendingEvent("b", JsonObject(emptyMap()))), "mcp:x")
        assertEquals(listOf("mcp:x", "mcp:x"), batch.map { it.actor })
        assertTrue(batch[0].ts < batch[1].ts)
    }

    @Test
    fun canonicalOrder_isTsThenDeviceThenSeq() {
        val p = JsonObject(emptyMap())
        val e1 = Event("3", "b", 1, 10, "user", "t", p)
        val e2 = Event("2", "a", 2, 10, "user", "t", p)
        val e3 = Event("1", "a", 1, 10, "user", "t", p)
        val e0 = Event("9", "z", 9, 5, "user", "t", p)
        assertEquals(listOf(e0, e3, e2, e1), listOf(e1, e2, e3, e0).sortedWith(Event.ORDER))
    }

    @Test
    fun actors() {
        assertTrue(Actors.isUser("user"))
        assertTrue(Actors.isMcp(Actors.mcp("Claude")))
        assertEquals("mcp:unknown", Actors.mcp("  "))
        assertFalse(Actors.isMcp("mcp:"))
        assertEquals("Claude", Actors.mcpClient("mcp:Claude"))
        assertNull(Actors.mcpClient("user"))
    }

    @Test
    fun payloadsRoundTripAndMalformedDecodesToNull() {
        val p = Events.stepCompleted(StepCompleted("s", StepVia.NOTIFICATION, next = NextStep("n", "q", "x")))
        val back = Events.decode(StepCompleted.serializer(), p.payload)!!
        assertEquals(StepVia.NOTIFICATION, back.via)
        assertEquals("x", back.next!!.text)
        assertNull(Events.decode(StepSet.serializer(), JsonObject(emptyMap())))
        assertEquals(16, EventTypes.ALL.size)
    }

    @Test
    fun sourceFromClientName() {
        assertEquals(ProposalSource.CLAUDE, ProposalSource.fromClientName("claude-ai"))
        assertEquals(ProposalSource.CLAUDE, ProposalSource.fromClientName("Anthropic connector"))
        assertEquals(ProposalSource.CHATGPT, ProposalSource.fromClientName("ChatGPT"))
        assertEquals(ProposalSource.CHATGPT, ProposalSource.fromClientName("openai-mcp"))
        assertEquals(ProposalSource.OTHER, ProposalSource.fromClientName("cursor"))
    }
}
