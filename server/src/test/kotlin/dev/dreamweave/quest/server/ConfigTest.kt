// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import kotlin.test.Test
import kotlin.test.assertEquals

/** docker-compose passes every optional setting through, empty when unset in .env. */
class ConfigTest {
    private val base = mapOf("ADMIN_PASSWORD" to "a-long-enough-password")

    @Test
    fun emptyOptionalSettings_fallBackToDefaults() {
        val c = Config.fromEnv(base + mapOf("APP_NAME" to "", "ALLOWED_ORIGINS" to "", "TRUST_PROXY" to ""))
        assertEquals(Config.APP_NAME_DEFAULT, c.appName)
        assertEquals(setOf("https://claude.ai", "https://chatgpt.com"), c.allowedOrigins)
    }

    @Test
    fun allowedOrigins_areTrimmedAndSplit() {
        val c = Config.fromEnv(base + ("ALLOWED_ORIGINS" to " https://claude.ai/ , https://example.org "))
        assertEquals(setOf("https://claude.ai", "https://example.org"), c.allowedOrigins)
    }
}
