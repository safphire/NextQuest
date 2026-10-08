// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.protocol.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * JSON Schema (draft 2020-12) from kotlinx.serialization descriptors. Used for the
 * committed /schema files and for MCP tool input schemas, so the two can't drift.
 */
@OptIn(ExperimentalSerializationApi::class)
object JsonSchema {
    fun of(descriptor: SerialDescriptor, title: String? = null, descriptions: Map<String, String> = emptyMap()): JsonObject {
        val body = node(descriptor, descriptions)
        return JsonObject(
            buildMap {
                put("\$schema", JsonPrimitive("https://json-schema.org/draft/2020-12/schema"))
                if (title != null) put("title", JsonPrimitive(title))
                putAll(body)
            },
        )
    }

    /** The schema without the `$schema` header (for embedding, e.g. MCP inputSchema). */
    fun inline(descriptor: SerialDescriptor, descriptions: Map<String, String> = emptyMap()): JsonObject = node(descriptor, descriptions)

    private fun node(d: SerialDescriptor, descriptions: Map<String, String>): JsonObject {
        val base: JsonObject = when (val kind: SerialKind = d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { (0 until d.elementsCount).forEach { add(JsonPrimitive(d.getElementName(it))) } }
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", node(d.getElementDescriptor(0), emptyMap()))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", node(d.getElementDescriptor(1), emptyMap()))
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (d.serialName.startsWith("kotlinx.serialization.json.JsonObject")) {
                    type("object")
                } else {
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            JsonObject(
                                (0 until d.elementsCount).associate { i ->
                                    val name = d.getElementName(i)
                                    val child = node(d.getElementDescriptor(i), emptyMap())
                                    name to (descriptions[name]?.let { JsonObject(child + ("description" to JsonPrimitive(it))) } ?: child)
                                },
                            ),
                        )
                        val required = (0 until d.elementsCount).filter { !d.isElementOptional(it) }.map { d.getElementName(it) }
                        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                        put("additionalProperties", JsonPrimitive(false))
                    }
                }
            }
            is PolymorphicKind, SerialKind.CONTEXTUAL -> buildJsonObject { }
            else -> buildJsonObject { }
        }
        return if (d.isNullable) JsonObject(base + ("nullable" to JsonPrimitive(true))).let(::nullable) else base
    }

    private fun type(t: String) = buildJsonObject { put("type", t) }

    /** Draft 2020-12 nullability: `type: [x, "null"]`. */
    private fun nullable(o: JsonObject): JsonObject {
        val t = o["type"]
        val withoutMarker = o - "nullable"
        return if (t is JsonPrimitive) JsonObject(withoutMarker + ("type" to JsonArray(listOf(t, JsonPrimitive("null"))))) else JsonObject(withoutMarker)
    }

}
