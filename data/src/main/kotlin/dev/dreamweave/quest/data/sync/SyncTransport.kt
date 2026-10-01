// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.dreamweave.quest.data.sync

import dev.dreamweave.quest.core.CoreJson
import dev.dreamweave.quest.protocol.ApiError
import dev.dreamweave.quest.protocol.PairRequest
import dev.dreamweave.quest.protocol.PairResponse
import dev.dreamweave.quest.protocol.PullResponse
import dev.dreamweave.quest.protocol.PushRequest
import dev.dreamweave.quest.protocol.PushResponse
import dev.dreamweave.quest.protocol.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json

/** The sync API (docs/SYNC.md) as seen from a device. [server] is the base URL without a trailing slash. */
interface SyncTransport {
    suspend fun pair(server: String, request: PairRequest): PairResponse
    suspend fun push(server: String, token: String, request: PushRequest): PushResponse
    suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse
}

/** A non-2xx answer. [code] is the server's machine-readable error, [message] is meant for people. */
class SyncHttpException(val status: Int, val code: String?, message: String) : Exception(message)

class KtorSyncTransport(private val client: HttpClient) : SyncTransport {
    override suspend fun pair(server: String, request: PairRequest): PairResponse =
        client.post(server + SyncApi.PAIR) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orThrow().body()

    override suspend fun push(server: String, token: String, request: PushRequest): PushResponse =
        client.post(server + SyncApi.EVENTS) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.orThrow().body()

    override suspend fun pull(server: String, token: String, after: Long, limit: Int): PullResponse =
        client.get(server + SyncApi.EVENTS) {
            bearerAuth(token)
            parameter("after", after)
            parameter("limit", limit)
        }.orThrow().body()

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val error = runCatching { CoreJson.decodeFromString(ApiError.serializer(), bodyAsText()) }.getOrNull()
        throw SyncHttpException(status.value, error?.error, error?.message ?: "The server answered ${status.value}.")
    }

    companion object {
        /** The production client: OkHttp, JSON, sane timeouts (sync runs in the background). */
        fun create(): KtorSyncTransport = KtorSyncTransport(
            HttpClient(OkHttp) {
                install(ContentNegotiation) { json(CoreJson) }
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    requestTimeoutMillis = 60_000
                }
                expectSuccess = false
            },
        )
    }
}
