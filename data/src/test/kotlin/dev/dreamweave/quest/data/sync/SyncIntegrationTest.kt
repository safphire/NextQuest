// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.core.Decision
import dev.dreamweave.quest.core.Event
import dev.dreamweave.quest.core.Ids
import dev.dreamweave.quest.core.ProposalStatus
import dev.dreamweave.quest.core.QuestStatus
import dev.dreamweave.quest.data.CommitResult
import dev.dreamweave.quest.data.QuestActions
import dev.dreamweave.quest.data.QuestRepository
import dev.dreamweave.quest.data.SettingsStore
import dev.dreamweave.quest.data.db.QuestDatabase
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PairResponse
import dev.dreamweave.quest.protocol.PullResponse
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.PushResponse
import dev.dreamweave.quest.protocol.ServerEvent
import dev.dreamweave.quest.server.Config
import dev.dreamweave.quest.server.Secrets
import dev.dreamweave.quest.server.Services
import dev.dreamweave.quest.server.questModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The whole loop against the real server module (in-process): a phone pairs and syncs,
 * an AI client proposes through MCP (OAuth + JSON-RPC over HTTP), the phone pulls the
 * proposal, the user accepts it, and the result reaches the server and a second phone.
 */
@RunWith(AndroidJUnit4::class)
class SyncIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<QuestDatabase>()
    private var now = System.currentTimeMillis()
    private val password = "integration-test-password"
    private val redirect = "https://claude.ai/api/mcp/auth_callback"

    private class MemoryTokens : TokenStore {
        override var token: String? = null
    }

    /** One phone: its own database, settings, token and engine, talking to [transport]. */
    private inner class Phone(transport: SyncTransport) {
        val db = Room.inMemoryDatabaseBuilder(context, QuestDatabase::class.java).allowMainThreadQueries().build().also { databases += it }
        val repo = QuestRepository(db.events(), Ids.newId(now), { TimeZone.UTC }, { now }, Dispatchers.Unconfined)
        val actions = QuestActions(repo) { now }
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
                File(Files.createTempDirectory("settings").toFile(), "settings.preferences_pb")
            },
        )
        val tokens = MemoryTokens()
        val engine = SyncEngine(repo, settings, tokens, transport) { now }
    }

    @After
    fun tearDown() = databases.forEach { it.close() }

    private fun withServer(block: suspend ApplicationTestBuilder.(Services, HttpClient) -> Unit) = testApplication {
        val config = Config(
            publicUrl = "http://localhost", adminPassword = password,
            dataDir = Files.createTempDirectory("quest-server").toFile(), timeZone = TimeZone.UTC,
        )
        var services: Services? = null
        application { services = questModule(config) { now } }
        startApplication()
        val client = createClient { install(ContentNegotiation) { json(CoreJson) } }
        block(services!!, client)
    }

    private fun Services.pairingCode(): String = devices.newPairingCode(config.userId).first

    /** Connects an MCP client the way Claude does: DCR, consent with the admin password, PKCE. */
    private suspend fun HttpClient.connectMcp(): String {
        val register = post("/oauth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"redirect_uris":["$redirect"],"client_name":"Claude","token_endpoint_auth_method":"none"}""")
        }
        val clientId = CoreJson.parseToJsonElement(register.bodyAsText()).jsonObject["client_id"]!!.jsonPrimitive.content
        val verifier = "integration-verifier-".padEnd(60, 'x')
        val approve = submitForm(
            "/oauth/authorize",
            parameters {
                append("response_type", "code"); append("client_id", clientId); append("redirect_uri", redirect)
                append("state", "s"); append("code_challenge", Secrets.pkceChallenge(verifier)); append("code_challenge_method", "S256")
                append("resource", "http://localhost/mcp"); append("decision", "approve"); append("password", password)
            },
        )
        val location = approve.headers[HttpHeaders.Location] ?: error("no redirect: ${approve.status} ${approve.bodyAsText().take(300)}")
        val code = java.net.URLDecoder.decode(Regex("code=([^&]+)").find(location)!!.groupValues[1], "UTF-8")
        val token = submitForm(
            "/oauth/token",
            parameters {
                append("grant_type", "authorization_code"); append("code", code); append("redirect_uri", redirect)
                append("client_id", clientId); append("code_verifier", verifier)
            },
        )
        return CoreJson.parseToJsonElement(token.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.callTool(token: String, name: String, args: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", args) }
        }
        val response = post("/mcp") {
            bearerAuth(token)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val result = CoreJson.parseToJsonElement(response.bodyAsText()).jsonObject["result"]!!.jsonObject
        assertEquals("false", result["isError"]!!.jsonPrimitive.content, "tool error: $result")
        return result["structuredContent"]!!.jsonObject
    }

    @Test
    fun proposeViaMcp_syncToPhone_accept_reachesServerAndSecondPhone() = withServer { services, http ->
        val phone = Phone(KtorSyncTransport(http))
        assertNull(phone.engine.pair("http://localhost/", services.pairingCode(), "Pixel"))
        assertTrue(phone.engine.isPaired)

        // Local work reaches the server.
        assertIs<CommitResult.Ok>(phone.actions.capture("Plan the garden"))
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        assertEquals(0, phone.repo.unpushedCount())
        assertEquals(listOf("Plan the garden"), services.store.state(services.config.userId).quests.values.map { it.title })

        // An AI client proposes a tracked quest through MCP.
        val token = http.connectMcp()
        val created = http.callTool(
            token, "propose_add_quest",
            buildJsonObject {
                put("title", "Fix the bike"); put("next_step", "Buy a tube"); put("track", true)
                put("reason", "You mentioned it twice this week.")
            },
        )
        val proposalId = created["proposal_id"]!!.jsonPrimitive.content
        // Nothing changed yet: it is only a proposal.
        assertTrue(services.store.state(services.config.userId).quests.values.none { it.title == "Fix the bike" })

        // The phone pulls it; the user accepts.
        val pulled = phone.engine.sync()
        assertIs<SyncOutcome.Ok>(pulled)
        assertEquals(listOf(proposalId), phone.repo.current().pendingProposals.map { it.id })
        assertIs<CommitResult.Ok>(phone.actions.decide(proposalId, Decision.Accept()))
        val local = phone.repo.current()
        val bike = local.quests.values.single { it.title == "Fix the bike" }
        assertEquals(QuestStatus.TRACKED, bike.status)
        assertEquals("Buy a tube", local.effectiveStep(bike.id)!!.text)

        // The acceptance syncs back: the server's view (what AI clients read) agrees.
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        val server = services.store.state(services.config.userId)
        assertEquals(ProposalStatus.ACCEPTED, server.proposals[proposalId]!!.status)
        assertEquals(QuestStatus.TRACKED, server.quests[bike.id]!!.status)

        // A second phone pairs and gets everything.
        val tablet = Phone(KtorSyncTransport(http))
        assertNull(tablet.engine.pair("http://localhost", services.pairingCode(), "Tablet"))
        val t = tablet.repo.current()
        assertEquals(setOf("Plan the garden", "Fix the bike"), t.quests.values.map { it.title }.toSet())
        assertEquals("Buy a tube", t.effectiveStep(bike.id)!!.text)
        assertEquals(local.xp, t.xp)
    }

    @Test
    fun offlineChangesQueueAndUploadLater_revokedPhoneStops() = withServer { services, http ->
        val real = KtorSyncTransport(http)
        var online = true
        val flaky = object : SyncTransport {
            override suspend fun pair(server: String, request: PairRequest): PairResponse = real.pair(server, request)
            override suspend fun push(server: String, token: String, request: PushRequest): PushResponse =
                if (online) real.push(server, token, request) else throw IOException("offline")
            override suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse =
                if (online) real.pull(server, token, after, limit) else throw IOException("offline")
        }
        val phone = Phone(flaky)
        assertNull(phone.engine.pair("http://localhost", services.pairingCode(), "Pixel"))

        online = false
        phone.actions.capture("Call the plumber")
        phone.actions.capture("Book the dentist")
        val failed = phone.engine.sync()
        assertIs<SyncOutcome.Failed>(failed)
        assertTrue(failed.retry)
        assertEquals(2, phone.repo.unpushedCount())
        assertTrue(phone.settings.current().lastSyncError != null)

        online = true
        val ok = phone.engine.sync()
        assertIs<SyncOutcome.Ok>(ok)
        assertEquals(2, ok.pushed)
        assertEquals(0, phone.repo.unpushedCount())
        assertNull(phone.settings.current().lastSyncError)
        assertEquals(2, services.store.state(services.config.userId).quests.size)

        // Removed on the server page: sync stops and tells the user to pair again.
        services.devices.revoke(services.config.userId, phone.repo.deviceId)
        val revoked = phone.engine.sync()
        assertIs<SyncOutcome.Failed>(revoked)
        assertEquals(false, revoked.retry)
        // …and forgets the dead pairing, so nothing keeps retrying in the background.
        assertTrue(revoked.unpaired)
        assertTrue(!phone.engine.isPaired)
        assertNull(phone.settings.current().serverUrl)
        assertTrue(phone.settings.current().lastSyncError!!.contains("Pair it in Settings"))
        assertIs<SyncOutcome.NotPaired>(phone.engine.sync())
    }

    @Test
    fun restoredInstall_withSettingsButNoToken_isToldToPairAgain() = withServer { services, http ->
        val phone = Phone(KtorSyncTransport(http))
        assertNull(phone.engine.pair("http://localhost", services.pairingCode(), "Pixel"))
        // Android restore brings the settings back but never the token (excluded from backup).
        phone.tokens.token = null
        assertEquals("http://localhost", phone.settings.current().serverUrl)
        phone.engine.reconcile()
        assertNull(phone.settings.current().serverUrl)
        assertTrue(phone.settings.current().lastSyncError != null)
        // Pairing again works and re-uploads the log.
        phone.actions.capture("Written before the restore")
        assertNull(phone.engine.pair("http://localhost", services.pairingCode(), "Pixel"))
        assertEquals(0, phone.repo.unpushedCount())
        assertTrue(services.store.state(services.config.userId).quests.values.any { it.title == "Written before the restore" })
    }

    /** A minimal sync server in memory (cursor = position in the log), which can be "restored" to an older backup. */
    private class MemoryServer : SyncTransport {
        val log = mutableListOf<Event>()
        override suspend fun pair(server: String, request: PairRequest) = PairResponse("token-${request.deviceId}", "Test", "user")
        override suspend fun push(server: String, token: String, request: PushRequest): PushResponse {
            val fresh = request.events.filter { e -> log.none { it.id == e.id } }.distinctBy { it.id }
            log += fresh
            return PushResponse(accepted = fresh.size, duplicates = request.events.size - fresh.size, cursor = log.size.toLong())
        }
        override suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse {
            val page = log.withIndex().drop(after.toInt()).take(limit).map { (i, e) -> ServerEvent(i + 1L, e) }
            return PullResponse(page, page.lastOrNull()?.cursor ?: after, after + page.size < log.size)
        }
        fun restoreBackup(keep: Int) = repeat(log.size - keep) { log.removeAt(log.lastIndex) }
    }

    @Test
    fun serverRestoredFromOlderBackup_andOvertaken_getsThePhonesLogAgain() = runBlocking {
        val server = MemoryServer()
        val phone = Phone(server)
        assertNull(phone.engine.pair("https://quests.example.com", "CODE", "Pixel"))
        listOf("Water the plants", "Call mum", "Fix the bike").forEach { phone.actions.capture(it) }
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        val phoneEvents = phone.db.events().all().map { it.id }.toSet()
        assertTrue(server.log.map { it.id }.containsAll(phoneEvents))
        val pulledTo = phone.settings.current().syncCursor
        assertEquals(server.log.size.toLong(), pulledTo)

        // The server comes back from an older backup, and another device pushes past our cursor.
        server.restoreBackup(keep = 1)
        val tablet = Phone(server)
        assertNull(tablet.engine.pair("https://quests.example.com", "CODE", "Tablet"))
        repeat(pulledTo.toInt() + 2) { tablet.actions.capture("Tablet idea $it") }
        assertIs<SyncOutcome.Ok>(tablet.engine.sync())
        assertTrue(server.log.size > pulledTo)

        // The phone notices (the event at its cursor is a different one) and re-uploads everything.
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        assertTrue(server.log.map { it.id }.containsAll(phoneEvents))
        assertEquals(0, phone.repo.unpushedCount())
        assertTrue(phone.repo.current().quests.values.any { it.title == "Tablet idea 0" })
        assertEquals(server.log.size.toLong(), phone.settings.current().syncCursor)
    }

    /**
     * The gap between the last pull and later pushes: pushes that the pull never passed
     * (here the pull fails after the push) are lost when the server is restored to a backup
     * taken in between. The pull cursor still matches, so only the push watermark notices.
     */
    @Test
    fun serverRestoredBetweenLastPullAndLaterPushes_getsThosePushesAgain() = runBlocking {
        val server = MemoryServer()
        var pullFails = false
        // Only the page pull fails; the one-event probes (limit 1) still get through.
        val memory: SyncTransport = server
        val transport = object : SyncTransport by memory {
            override suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse =
                if (pullFails && limit > 1) throw IOException("connection dropped") else memory.pull(server, token, after, limit)
        }
        val phone = Phone(transport)
        assertNull(phone.engine.pair("https://quests.example.com", "CODE", "Pixel"))
        phone.actions.capture("Pulled and safe")
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        val backup = server.log.size
        assertEquals(backup.toLong(), phone.settings.current().syncCursor)

        // Pushed, then the connection drops before the pull: the pull cursor stays behind.
        phone.actions.capture("Pushed after the last pull")
        pullFails = true
        assertIs<SyncOutcome.Failed>(phone.engine.sync())
        pullFails = false
        assertEquals(0, phone.repo.unpushedCount())
        val late = phone.repo.current().quests.values.single { it.title == "Pushed after the last pull" }
        assertTrue(server.log.any { it.id == late.id || it.payload.toString().contains(late.id) })

        // The server is restored to the backup taken after the pull and before that push.
        server.restoreBackup(keep = backup)
        assertTrue(server.log.none { it.payload.toString().contains(late.id) })

        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        assertTrue(server.log.any { it.payload.toString().contains(late.id) }, "the lost push was sent again")
        assertEquals(0, phone.repo.unpushedCount())
    }

    @Test
    fun serverRestoredBetweenPullAndPushes_andOvertaken_isNoticedToo() = runBlocking {
        val server = MemoryServer()
        var pullFails = false
        // Only the page pull fails; the one-event probes (limit 1) still get through.
        val memory: SyncTransport = server
        val transport = object : SyncTransport by memory {
            override suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse =
                if (pullFails && limit > 1) throw IOException("connection dropped") else memory.pull(server, token, after, limit)
        }
        val phone = Phone(transport)
        assertNull(phone.engine.pair("https://quests.example.com", "CODE", "Pixel"))
        phone.actions.capture("Pulled and safe")
        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        val backup = server.log.size
        phone.actions.capture("Pushed after the last pull")
        pullFails = true
        phone.engine.sync()
        pullFails = false
        val late = phone.repo.current().quests.values.single { it.title == "Pushed after the last pull" }

        // Restored, and another device pushes past the phone's watermark before it syncs.
        server.restoreBackup(keep = backup)
        val tablet = Phone(server)
        assertNull(tablet.engine.pair("https://quests.example.com", "CODE", "Tablet"))
        repeat(4) { tablet.actions.capture("Tablet idea $it") }
        assertIs<SyncOutcome.Ok>(tablet.engine.sync())

        assertIs<SyncOutcome.Ok>(phone.engine.sync())
        assertTrue(server.log.any { it.payload.toString().contains(late.id) }, "the lost push was sent again")
    }

    @Test
    fun pairingRefusesNonHttpAddressesAndWrongCodes() = withServer { services, http ->
        val phone = Phone(KtorSyncTransport(http))
        assertTrue(phone.engine.pair("ftp://example.com", "ABCD-EFGH", "Pixel") != null)
        assertTrue(phone.engine.pair("http://localhost", "WRONG-CODE", "Pixel") != null)
        assertTrue(!phone.engine.isPaired)
        assertIs<SyncOutcome.NotPaired>(phone.engine.sync())
        assertEquals("https://quests.example.com", SyncEngine.normalize(" https://quests.example.com/mcp/ "))
    }
}
