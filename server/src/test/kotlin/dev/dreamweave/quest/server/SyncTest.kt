// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.server

import dev.dreamweave.quest.core.Actors
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.Events
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.QuestCreated
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PushRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncTest {
    @Test
    fun healthAndAdminLogin() = serverTest {
        assertContains(http.get("/health").bodyAsText(), "\"ok\":true")
        assertContains(http.get("/").bodyAsText(), "Admin password")
        assertEquals(HttpStatusCode.Unauthorized, http.submitForm("/login", parameters { append("password", "nope") }).status)
        val cookie = login()
        assertContains(http.get("/") { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }.bodyAsText(), "Pair a device")
        // Pairing needs the CSRF token, not just the cookie.
        val noCsrf = http.submitForm("/pair", parameters { }) { header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie") }
        assertEquals(HttpStatusCode.Found, noCsrf.status)
    }

    @Test
    fun pairing_isOneTimeAndExpires() = serverTest {
        val code = pairingCode()
        val req = PairRequest(code, Ids.newId(clock.now), "Pixel")
        suspend fun pair(r: PairRequest) = http.post("/v1/pair") { contentType(ContentType.Application.Json); setBody(r) }
        assertEquals(HttpStatusCode.OK, pair(req).status)
        assertEquals(HttpStatusCode.BadRequest, pair(req).status) // used
        val late = pairingCode()
        clock.now += Devices.CODE_TTL_MS + 1
        assertEquals(HttpStatusCode.BadRequest, pair(PairRequest(late, Ids.newId(clock.now), "x")).status)
        assertEquals(HttpStatusCode.BadRequest, pair(PairRequest(pairingCode().lowercase().replace("-", " "), "not-a-uuid", "x")).status)
        // Codes are case- and separator-insensitive.
        assertEquals(HttpStatusCode.OK, pair(PairRequest(pairingCode().lowercase().replace("-", " "), Ids.newId(clock.now), "x")).status)
    }

    @Test
    fun pushPull_isIdempotentAndValidated() = serverTest {
        val phone = pairDevice("Phone")
        val tablet = pairDevice("Tablet")
        val r1 = phone.push(Events.questCreated(QuestCreated(Ids.newId(clock.now), "A")), Events.questCreated(QuestCreated(Ids.newId(clock.now), "B")))
        assertEquals(2, r1.accepted)
        val pulled = tablet.pull()
        assertEquals(listOf("A", "B"), pulled.map { (it.payload["title"] as kotlinx.serialization.json.JsonPrimitive).content })
        // Re-pushing the same events changes nothing.
        val again = phone.pushEvents(pulled)
        assertEquals(0, again.accepted)
        assertEquals(2, again.duplicates)
        assertEquals(0, tablet.pull().size)

        // Devices can't push AI events, malformed envelopes, or events from the far future.
        val good = phone.factory.create("quest.created", Events.questCreated(QuestCreated(Ids.newId(clock.now), "C")).payload)
        val rogue = phone.factory.create("proposal.created", JsonObject(emptyMap()), Actors.mcp("Claude"))
        val future = good.copy(id = Ids.newId(clock.now), seq = 99, ts = clock.now + 3 * 86_400_000L)
        val broken = Event("nope", phone.deviceId, 100, clock.now, "user", "quest.created", JsonObject(emptyMap()))
        val r2 = phone.pushEvents(listOf(good, rogue, future, broken))
        assertEquals(1, r2.accepted)
        assertEquals(setOf(rogue.id, future.id, "nope"), r2.rejected.map { it.id }.toSet())
        assertTrue(r2.rejected.any { "user events" in it.reason })
        assertTrue(r2.rejected.any { "future" in it.reason })
        // …nor under another device's id (that would take over its sequence numbers).
        val spoof = good.copy(id = Ids.newId(clock.now), seq = 200)
        val r3 = tablet.pushEvents(listOf(spoof))
        assertEquals(0, r3.accepted)
        assertTrue(r3.rejected.single().reason.contains("own events"))

        // Pagination over many events.
        phone.push(*Array(120) { Events.questCreated(QuestCreated(Ids.newId(clock.now + it), "Q$it")) })
        assertEquals(121, tablet.pull().size)
        assertEquals(123, services.store.state(services.config.userId).quests.size)
    }

    @Test
    fun syncNeedsAValidDeviceToken_andRevokedDevicesAreLockedOut() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/events").status)
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/events") { bearerAuth("made-up") }.status)
        val phone = pairDevice()
        assertEquals(HttpStatusCode.OK, http.get("/v1/events") { bearerAuth(phone.token) }.status)
        val cookie = login()
        val token = csrf(cookie)
        http.submitForm("/devices/revoke", parameters { append("csrf", token); append("id", phone.deviceId) }) {
            header(HttpHeaders.Cookie, "${Sessions.COOKIE}=$cookie")
        }
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/events") { bearerAuth(phone.token) }.status)
        val tooMany = http.post("/v1/events") {
            bearerAuth(pairDevice().token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(List(1_001) { Event(Ids.newId(), Ids.newId(), 1, 1, "user", "t", JsonObject(emptyMap())) }))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooMany.status)
    }
}
