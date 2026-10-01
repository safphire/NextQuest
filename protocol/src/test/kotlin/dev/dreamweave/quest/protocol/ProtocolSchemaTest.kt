// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol

import dev.dreamweave.quest.protocol.mcp.McpToolSpecs
import dev.dreamweave.quest.protocol.schema.Schemas
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolSchemaTest {
    /** The committed /schema must match the DTOs (run `./gradlew :protocol:generateSchemas`). */
    @Test
    fun committedSchemasAreUpToDate() {
        val dir = File(System.getProperty("schemaDir") ?: "../schema")
        val expected = Schemas.all()
        val stale = expected.filter { (name, schema) ->
            val f = File(dir, name)
            !f.exists() || f.readText() != Schemas.render(schema)
        }.keys
        val extra = dir.listFiles { f -> f.name.endsWith(".schema.json") }?.map { it.name }.orEmpty() - expected.keys
        assertTrue(stale.isEmpty() && extra.isEmpty(), "Regenerate schemas: stale=$stale extra=$extra")
    }

    @Test
    fun toolSchemasDescribeRequiredFieldsAndReason() {
        val add = McpToolSpecs.PROPOSE_ADD_QUEST.inputSchema
        assertEquals(JsonPrimitive("object"), add["type"])
        val required = (add["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("title", "reason"), required)
        val props = add["properties"]!!.jsonObject
        assertContains((props["reason"] as JsonObject)["description"].toString(), "Why you suggest this")
        assertEquals(JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), (props["why"] as JsonObject)["type"])
        for (t in McpToolSpecs.all.filter { !it.readOnly }) {
            assertContains(t.description, "nothing changes until the user")
            assertTrue("reason" in ((t.inputSchema["required"] as JsonArray).map { (it as JsonPrimitive).content }), t.name)
            assertTrue(t.name.startsWith("propose_"))
        }
        assertEquals(12, McpToolSpecs.all.size)
        assertEquals(12, McpToolSpecs.all.map { it.name }.toSet().size)
    }
}
