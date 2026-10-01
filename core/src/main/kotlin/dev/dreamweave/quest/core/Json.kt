// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.core

import kotlinx.serialization.json.Json

/** The one JSON configuration used for event payloads everywhere (app, server, snapshots). */
val CoreJson: Json = Json {
    ignoreUnknownKeys = true // forward compatibility: newer writers may add fields
    encodeDefaults = false
    explicitNulls = false
}
