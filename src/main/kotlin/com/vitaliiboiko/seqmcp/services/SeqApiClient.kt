// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.net.http.WebSocketHandshakeException
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import javax.net.ssl.SSLException
import kotlin.coroutines.resumeWithException

/** The production Seq client, also used by transport and remote integration tests. */
open class SeqApiClient(
    private val serverUrl: () -> String,
    private val apiKey: (String?) -> String?,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) : SeqMcpBackend, AutoCloseable {
    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative) { "requestTimeout must be positive" }
    }

    override fun close() {
        httpClient.shutdownNow()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
        require(request.count in 1..MAX_SEARCH_EVENTS) { "count must be between 1 and $MAX_SEARCH_EVENTS" }
        require(request.timeoutSeconds in 1..300) { "timeoutSeconds must be between 1 and 300" }

        return streamFromResourceGroup(
            groupName = "Events",
            linkName = "Scan",
            workspace = request.workspace,
            parameters = buildMap {
                put("filter", request.filter)
                put("count", request.count)
                put("render", true)
                request.signalId?.let { put("signal", it) }
                request.fromDateUtc?.let { put("fromDateUtc", it.toString()) }
                request.toDateUtc?.let { put("toDateUtc", it.toString()) }
                request.afterId?.let { put("afterId", it) }
            },
            maxMessages = request.count,
            timeout = Duration.ofSeconds(request.timeoutSeconds.toLong()),
            failOnTimeout = true,
        )
    }

    override suspend fun waitForEvents(filter: String?, count: Int, workspace: String?): JsonArray {
        require(count in 1..100) { "count must be between 1 and 100" }

        return streamFromResourceGroup(
            groupName = "Events",
            linkName = "Stream",
            workspace = workspace,
            parameters = buildMap {
                filter?.let { put("filter", it) }
                put("render", true)
            },
            maxMessages = count,
            timeout = Duration.ofSeconds(5),
        )
    }

    override suspend fun listSignals(workspace: String?): JsonArray {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Signals", workspace)
            val uri = resolveResourceLink(
                resourceGroup,
                "Items",
                mapOf("shared" to true),
            )
            val response = sendRequest(uri, workspace)
            val payload = parseJson(response.body())
            when (payload) {
                is JsonArray -> payload
                else -> throw IOException("Unexpected Seq response for signals list")
            }
        }
    }

    override suspend fun toStrictFilterExpression(fuzzy: String, workspace: String?): SeqStrictExpressionResult {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Expressions", workspace)
            val uri = resolveResourceLink(
                resourceGroup,
                "ToStrict",
                mapOf("fuzzy" to fuzzy),
            )
            val response = sendRequest(uri, workspace)
            val payload = parseJson(response.body()).jsonObject

            SeqStrictExpressionResult(
                strictExpression = payload["StrictExpression"]?.jsonPrimitive?.content
                    ?: throw IOException("Unexpected Seq response for strict expression conversion"),
                matchedAsText = payload["MatchedAsText"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                reasonIfMatchedAsText = payload["ReasonIfMatchedAsText"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    override suspend fun querySql(request: SeqSqlQueryRequest): JsonObject {
        require(request.query.isNotBlank()) { "query must not be blank" }
        request.timeoutSeconds?.let { require(it in 1..300) { "timeoutSeconds must be between 1 and 300" } }

        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Data", request.workspace)
            val response = sendJsonRequest(
                method = "POST",
                uri = resolveResourceLink(
                    resourceGroup,
                    "Query",
                    buildMap {
                        put("q", request.query)
                        request.rangeStartUtc?.let { put("rangeStartUtc", it.toString()) }
                        request.rangeEndUtc?.let { put("rangeEndUtc", it.toString()) }
                        request.signalId?.let { put("signal", it) }
                        request.timeoutSeconds?.let { put("timeoutMS", it * 1000) }
                    },
                ),
                workspace = request.workspace,
                body = buildJsonObject {},
                timeout = sqlRequestTimeout(request),
            )
            parseJson(response.body()).jsonObject
        }
    }

    override suspend fun querySqlCsv(request: SeqSqlQueryRequest): String {
        require(request.query.isNotBlank()) { "query must not be blank" }
        request.timeoutSeconds?.let { require(it in 1..300) { "timeoutSeconds must be between 1 and 300" } }

        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Data", request.workspace)
            sendJsonRequest(
                method = "POST",
                uri = resolveResourceLink(
                    resourceGroup,
                    "Query",
                    buildMap {
                        put("q", request.query)
                        put("format", "text/csv")
                        request.rangeStartUtc?.let { put("rangeStartUtc", it.toString()) }
                        request.rangeEndUtc?.let { put("rangeEndUtc", it.toString()) }
                        request.signalId?.let { put("signal", it) }
                        request.timeoutSeconds?.let { put("timeoutMS", it * 1000) }
                    },
                ),
                workspace = request.workspace,
                body = buildJsonObject {},
                accept = "text/csv",
                timeout = sqlRequestTimeout(request),
            ).body()
        }
    }

    override suspend fun listSqlQueries(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
        return listResourceGroupItems(
            groupName = "SqlQueries",
            workspace = workspace,
            parameters = mapOf(
                "ownerId" to ownerId,
                "shared" to shared,
            ),
        )
    }

    override suspend fun getSqlQuery(id: String, workspace: String?): JsonObject {
        require(id.isNotBlank()) { "id must not be blank" }
        return getResourceGroupItem("SqlQueries", workspace, id)
    }

    override suspend fun listWorkspaces(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
        return listResourceGroupItems(
            groupName = "Workspaces",
            workspace = workspace,
            parameters = mapOf(
                "ownerId" to ownerId,
                "shared" to shared,
            ),
        )
    }

    override suspend fun getWorkspace(id: String, workspace: String?): JsonObject {
        require(id.isNotBlank()) { "id must not be blank" }
        return getResourceGroupItem("Workspaces", workspace, id)
    }

    override suspend fun listDashboards(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
        return listResourceGroupItems(
            groupName = "Dashboards",
            workspace = workspace,
            parameters = mapOf(
                "ownerId" to ownerId,
                "shared" to shared,
            ),
        )
    }

    override suspend fun getDashboard(id: String, workspace: String?): JsonObject {
        require(id.isNotBlank()) { "id must not be blank" }
        return getResourceGroupItem("Dashboards", workspace, id)
    }

    override suspend fun getCapabilities(workspace: String?): SeqCapabilityReport {
        return withContext(Dispatchers.IO) {
            val credentialContext = resolveCredentialContext(workspace)
            val requestedWorkspace = workspace?.takeIf { it.isNotBlank() }
            val notes = mutableListOf<String>()
            val root = getApiRoot(workspace)

            if (requestedWorkspace != null && credentialContext.credentialSource == "default_api_key") {
                notes += "No workspace-specific API key was found for `$requestedWorkspace`; using the default API key."
            } else if (requestedWorkspace != null && credentialContext.credentialSource == "none") {
                notes += "No workspace-specific API key was found for `$requestedWorkspace`, and no default API key is configured."
            }

            val eventsGroup = loadResourceGroupOrNull("Events", workspace, root, notes)
            val usersGroup = loadResourceGroupOrNull("Users", workspace, root, notes)
            val permalinksGroup = loadResourceGroupOrNull("Permalinks", workspace, root, notes)

            val currentUser = usersGroup?.let { group ->
                runCatching { getCurrentUser(group, workspace) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    notes += "Current user lookup failed: ${error.message ?: "unknown error"}"
                    null
                }
            }

            if (currentUser == null) {
                notes += "No Seq user identity was resolved for the current credential."
            }

            val canQueryEvents = runCatching {
                searchEvents(
                    SeqSearchRequest(
                        filter = "",
                        count = 1,
                        workspace = workspace,
                        timeoutSeconds = 5,
                    ),
                )
                true
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                notes += "Event query check failed: ${error.message ?: "unknown error"}"
                false
            }

            val canResolveCurrentUser = currentUser?.stringValue("Id") != null
            val canCreatePermalinks = permalinksGroup != null &&
                    canResolveCurrentUser &&
                    credentialContext.authMode != "anonymous"

            if (!canCreatePermalinks && permalinksGroup != null && !canResolveCurrentUser) {
                notes += "Permalink creation requires a Seq user identity; the current credential did not resolve one."
            }
            if (!canCreatePermalinks && permalinksGroup != null && credentialContext.authMode == "anonymous") {
                notes += "Permalink creation is unavailable in anonymous mode; /api/permalinks requires an authenticated user-scoped credential even if /api/users/current returns a display identity."
            }

            SeqCapabilityReport(
                authContext = SeqAuthContext(
                    authMode = credentialContext.authMode,
                    credentialSource = credentialContext.credentialSource,
                    resolvedUserId = currentUser?.stringValue("Id"),
                    resolvedUsername = currentUser?.stringValue("Username"),
                    resolvedUserDisplayName = currentUser?.stringValue("DisplayName"),
                    resolvedWorkspace = requestedWorkspace,
                    serverVersion = root.stringValue("Version"),
                    serverProduct = root.stringValue("Product"),
                    serverInstanceName = root.stringValue("InstanceName"),
                ),
                canQueryEvents = canQueryEvents,
                canResolveCurrentUser = canResolveCurrentUser,
                canCreatePermalinks = canCreatePermalinks,
                eventsEndpoint = eventsGroup?.let { group ->
                    resolvedLinkOrNull(
                        group,
                        "Scan",
                        mapOf(
                            "count" to 1,
                            "render" to true,
                        ),
                    )
                },
                currentUserEndpoint = usersGroup?.let { resolvedLinkOrNull(it, "Current") },
                permalinkEndpoint = permalinksGroup?.let { resolvedLinkOrNull(it, "Items") },
                notes = notes.distinct(),
            )
        }
    }

    private fun resolvedLinkOrNull(
        entity: JsonObject,
        linkName: String,
        parameters: Map<String, Any?> = emptyMap(),
    ): String? {
        return runCatching {
            resolveResourceLink(
                entity,
                linkName,
                parameters,
            ).toString()
        }.getOrNull()
    }

    override suspend fun getEvent(id: String, workspace: String?, render: Boolean): JsonObject {
        require(id.isNotBlank()) { "id must not be blank" }
        return getResourceGroupItem(
            groupName = "Events",
            workspace = workspace,
            id = id,
            additionalParameters = buildMap {
                if (render) {
                    put("render", true)
                }
            },
        )
    }

    override suspend fun createPermalink(
        eventId: String,
        workspace: String?,
        includeEvent: Boolean,
        renderEvent: Boolean,
    ): JsonObject {
        require(eventId.isNotBlank()) { "eventId must not be blank" }

        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Permalinks", workspace)
            val created = parseJson(
                sendJsonRequest(
                    method = "POST",
                    uri = resolveResourceLink(resourceGroup, "Items"),
                    workspace = workspace,
                    body = buildJsonObject {
                        put("EventId", JsonPrimitive(eventId))
                    },
                ).body(),
            ).jsonObject

            val permalink = if (includeEvent || renderEvent) {
                val id = created["Id"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("Seq returned a permalink without an Id")
                getResourceGroupItem(
                    groupName = "Permalinks",
                    workspace = workspace,
                    id = id,
                    additionalParameters = mapOf(
                        "includeEvent" to includeEvent,
                        "renderEvent" to renderEvent,
                    ),
                )
            } else {
                created
            }

            withResolvedLinks(permalink)
        }
    }

    suspend fun deleteEvents(
        filter: String? = null,
        workspace: String? = null,
        signalId: String? = null,
        fromDateUtc: Instant? = null,
        toDateUtc: Instant? = null,
    ) {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup("Events", workspace)
            val uri = resolveResourceLink(
                resourceGroup,
                "DeleteInSignal",
                buildMap {
                    signalId?.let { put("signal", it) }
                    filter?.takeIf { it.isNotBlank() }?.let { put("filter", it) }
                    fromDateUtc?.let { put("fromDateUtc", it.toString()) }
                    toDateUtc?.let { put("toDateUtc", it.toString()) }
                },
            )
            sendDeleteRequest(uri, workspace, buildJsonObject {})
        }
    }

    private suspend fun streamFromResourceGroup(
        groupName: String,
        linkName: String,
        workspace: String?,
        parameters: Map<String, Any?>,
        maxMessages: Int,
        timeout: Duration,
        failOnTimeout: Boolean = false,
    ): JsonArray {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup(groupName, workspace)
            val uri = toWebSocketUri(resolveResourceLink(resourceGroup, linkName, parameters))
            collectWebSocketMessages(uri, workspace, maxMessages, timeout, failOnTimeout)
        }
    }

    private suspend fun collectWebSocketMessages(
        uri: URI,
        workspace: String?,
        maxMessages: Int,
        timeout: Duration,
        failOnTimeout: Boolean,
    ): JsonArray {
        val listener = QueuingWebSocketListener()
        val builder = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))

        resolveCredentialContext(workspace).apiKey?.let { builder.header(SEQ_API_KEY_HEADER, it) }

        val webSocket = try {
            withTimeoutOrNull(5_000) { builder.buildAsync(uri, listener).awaitCancellable(WebSocket::abort) }
                ?: throw SeqApiException(
                    "Seq WebSocket handshake timed out. Check connectivity and proxy WebSocket support.",
                    errorCode = "TIMEOUT"
                )
        } catch (error: Exception) {
            throw connectionFailure(error)
        }
        val elements = ArrayList<JsonElement>(maxMessages)
        var receivedCharacters = 0
        val deadlineNanos = System.nanoTime() + timeout.toNanos()

        try {
            while (elements.size < maxMessages) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                val next = if (remainingNanos <= 0) null else {
                    withTimeoutOrNull(maxOf(1, Duration.ofNanos(remainingNanos).toMillis())) { listener.receive() }
                }
                when (next) {
                    null -> {
                        if (failOnTimeout) {
                            throw SeqApiException(
                                "Seq search timed out before completion; partial results were discarded. Narrow the time range or increase timeoutSeconds.",
                                errorCode = "SEARCH_TIMEOUT",
                            )
                        }
                        break
                    }

                    is WebSocketMessage.Text -> {
                        receivedCharacters += next.payload.length
                        if (receivedCharacters > 4_194_304) {
                            throw SeqApiException(
                                "Seq search result is too large. Reduce count or use SQL to select fewer fields.",
                                errorCode = "RESULT_TOO_LARGE"
                            )
                        }
                        elements += parseJson(next.payload)
                        webSocket.request(1)
                    }

                    is WebSocketMessage.Closed -> {
                        if (next.statusCode != WebSocket.NORMAL_CLOSURE) {
                            throw SeqApiException(
                                "Seq stream closed unexpectedly (WebSocket code ${next.statusCode}); partial results were discarded.",
                                errorCode = "STREAM_CLOSED",
                            )
                        }
                        break
                    }

                    is WebSocketMessage.Failure -> throw connectionFailure(next.error)
                }
            }
        } finally {
            // Never wait for a close handshake with an unresponsive proxy or server.
            webSocket.abort()
        }

        return JsonArray(elements)
    }

    private suspend fun getApiRoot(workspace: String?): JsonObject {
        return parseJson(sendRequest(resolveAgainstBase("api"), workspace).body()).jsonObject
    }

    private suspend fun getResourceGroup(groupName: String, workspace: String?, root: JsonObject? = null): JsonObject {
        val resolvedRoot = root ?: getApiRoot(workspace)
        return parseJson(
            sendRequest(
                resolveResourceLink(resolvedRoot, "${groupName}Resources"),
                workspace
            ).body()
        ).jsonObject
    }

    private suspend fun listResourceGroupItems(
        groupName: String,
        workspace: String?,
        parameters: Map<String, Any?> = emptyMap(),
    ): JsonArray {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup(groupName, workspace)
            val response = sendRequest(resolveResourceLink(resourceGroup, "Items", parameters), workspace)
            when (val payload = parseJson(response.body())) {
                is JsonArray -> payload
                else -> throw IOException("Unexpected Seq response for $groupName list")
            }
        }
    }

    private suspend fun getResourceGroupItem(
        groupName: String,
        workspace: String?,
        id: String,
        additionalParameters: Map<String, Any?> = emptyMap(),
    ): JsonObject {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup(groupName, workspace)
            val response = sendRequest(
                resolveResourceLink(
                    resourceGroup,
                    "Item",
                    buildMap {
                        put("id", id)
                        putAll(additionalParameters)
                    },
                ),
                workspace,
            )
            parseJson(response.body()).jsonObject
        }
    }

    private fun resolveResourceLink(
        entity: JsonObject,
        linkName: String,
        parameters: Map<String, Any?> = emptyMap(),
    ): URI {
        val links = entity["Links"]?.jsonObject ?: throw IOException("Seq response is missing Links")
        val template = links[linkName]?.jsonPrimitive?.content
            ?: throw IOException("Seq response is missing the $linkName link")
        val resolved = resolveUriTemplate(template, parameters)
        return resolveAgainstBase(resolved)
    }

    private fun resolveAgainstBase(target: String): URI {
        return resolveSeqApiLink(currentServerUri(), target)
    }

    private suspend fun sendRequest(uri: URI, workspace: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .apply {
                resolveCredentialContext(workspace).apiKey?.let { header(SEQ_API_KEY_HEADER, it) }
            }
            .GET()
            .build()

        return sendChecked(request)
    }

    private suspend fun sendDeleteRequest(uri: URI, workspace: String?, body: JsonObject): HttpResponse<String> {
        return sendJsonRequest(
            method = "DELETE",
            uri = uri,
            workspace = workspace,
            body = body,
        )
    }

    private suspend fun sendJsonRequest(
        method: String,
        uri: URI,
        workspace: String?,
        body: JsonObject,
        accept: String = "application/json",
        timeout: Duration = requestTimeout,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", accept)
            .header("Content-Type", "application/json")
            .apply {
                resolveCredentialContext(workspace).apiKey?.let { header(SEQ_API_KEY_HEADER, it) }
            }
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build()

        return sendChecked(request)
    }

    private suspend fun sendChecked(request: HttpRequest): HttpResponse<String> {
        val response = try {
            // The coroutine deadline also covers a response body that stalls after its headers arrive.
            withTimeoutOrNull(request.timeout().orElse(requestTimeout).toMillis()) {
                httpClient.sendAsync(request) { LimitedStringBodySubscriber() }.awaitCancellable()
            } ?: throw SeqApiException(
                "Seq HTTP request timed out. Check server availability or narrow the query.",
                errorCode = "TIMEOUT"
            )
        } catch (error: Exception) {
            throw connectionFailure(error)
        }
        if (response.statusCode() in 200..299) {
            return response
        }

        val payload = runCatching { parseJson(response.body()).jsonObject }.getOrNull()
        val error = payload?.get("Error")?.jsonPrimitive?.contentOrNull
        val suggestion = payload?.get("Suggestion")?.jsonPrimitive?.contentOrNull
        val reasons = payload?.get("Reasons")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val suffix = error?.let { ": $it" }.orEmpty()
        val hint = when (response.statusCode()) {
            401 -> " Check the Seq API key."
            403 -> " Check that the API key has Read permission for queries."
            in 300..399 -> " Configure the final Seq API URL; redirects and browser sign-in pages are not supported."
            else -> ""
        }
        throw SeqApiException(
            message = "Seq API request failed with ${response.statusCode()}$suffix$hint",
            statusCode = response.statusCode(),
            seqError = error,
            seqSuggestion = suggestion,
            seqReasons = reasons,
            responseBody = response.body(),
            errorCode = "HTTP_ERROR",
        )
    }

    private fun parseJson(payload: String): JsonElement {
        return if (payload.isBlank()) {
            JsonNull
        } else {
            json.parseToJsonElement(payload)
        }
    }

    private fun currentServerUri(): URI {
        val configured = serverUrl().trim()
        require(configured.isNotBlank()) { "SEQ_SERVER_URL is not configured" }
        val normalized = if (configured.endsWith('/')) configured else "$configured/"
        val uri = URI(normalized)
        require(
            uri.scheme?.lowercase() in setOf("http", "https") && uri.host != null &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        ) { "SEQ_SERVER_URL must be an HTTP(S) base URL without credentials, query parameters, or a fragment" }
        return uri
    }

    private fun resolveCredentialContext(workspaceId: String?): SeqCredentialContext {
        val normalizedWorkspaceId = workspaceId?.takeIf { it.isNotBlank() }
        val workspaceApiKey = normalizedWorkspaceId
            ?.let(apiKey)
            ?.takeIf { it.isNotBlank() }
        val defaultApiKey = apiKey(null)?.takeIf { it.isNotBlank() }

        return when {
            workspaceApiKey != null -> SeqCredentialContext(
                workspaceId = normalizedWorkspaceId,
                apiKey = workspaceApiKey,
                authMode = "api_key",
                credentialSource = "workspace_override",
            )

            defaultApiKey != null -> SeqCredentialContext(
                workspaceId = normalizedWorkspaceId,
                apiKey = defaultApiKey,
                authMode = "api_key",
                credentialSource = "default_api_key",
            )

            else -> SeqCredentialContext(
                workspaceId = normalizedWorkspaceId,
                apiKey = null,
                authMode = "anonymous",
                credentialSource = "none",
            )
        }
    }

    private suspend fun loadResourceGroupOrNull(
        groupName: String,
        workspace: String?,
        root: JsonObject,
        notes: MutableList<String>,
    ): JsonObject? {
        return runCatching { getResourceGroup(groupName, workspace, root) }.getOrElse { error ->
            if (error is CancellationException) throw error
            notes += "$groupName resource group unavailable: ${error.message ?: "unknown error"}"
            null
        }
    }

    private suspend fun getCurrentUser(usersGroup: JsonObject, workspace: String?): JsonObject? {
        val response = sendRequest(resolveResourceLink(usersGroup, "Current"), workspace)
        return when (val payload = parseJson(response.body())) {
            is JsonObject -> withResolvedLinks(payload)
            else -> null
        }
    }

    private fun withResolvedLinks(entity: JsonObject): JsonObject {
        val links = entity["Links"]?.jsonObject ?: return entity
        val resolvedLinks = buildJsonObject {
            links.forEach { (name, value) ->
                value.jsonPrimitive.contentOrNull?.let { put(name, JsonPrimitive(resolveAgainstBase(it).toString())) }
            }
        }

        return JsonObject(
            entity.toMutableMap().apply {
                put("ResolvedLinks", resolvedLinks)
            },
        )
    }

    private fun resolveUriTemplate(template: String, parameters: Map<String, Any?>): String {
        var resolved = template

        QUERY_TEMPLATE.findAll(template).toList().asReversed().forEach { match ->
            val operator = match.groupValues[1]
            val names = match.groupValues[2].split(',')
            val query = names.mapNotNull { name ->
                parameters[name]?.let { value -> "${encode(name)}=${encodeParameterValue(value)}" }
            }.joinToString("&")

            val replacement = when {
                query.isEmpty() -> ""
                operator == "?" -> "?$query"
                else -> "&$query"
            }
            resolved = resolved.replaceRange(match.range, replacement)
        }

        SIMPLE_TEMPLATE.findAll(resolved).toList().asReversed().forEach { match ->
            val name = match.groupValues[1]
            val replacement = parameters[name]?.let(::encodeParameterValue)
                ?: throw IllegalArgumentException("Missing URI template parameter: $name")
            resolved = resolved.replaceRange(match.range, replacement)
        }

        return resolved
    }

    private fun encodeParameterValue(value: Any): String {
        return when (value) {
            is JsonPrimitive -> value.content
            else -> value.toString()
        }.let(::encode)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }

    private fun sqlRequestTimeout(request: SeqSqlQueryRequest): Duration =
        request.timeoutSeconds?.let { Duration.ofSeconds(it.toLong() + 5) } ?: requestTimeout

    companion object {
        private const val SEQ_API_KEY_HEADER = "X-Seq-ApiKey"
        private val QUERY_TEMPLATE = Regex("""\{([?&])([^}]+)}""")
        private val SIMPLE_TEMPLATE = Regex("""\{([^?&][^}]*)}""")

        const val MAX_SEARCH_EVENTS = 1000
    }
}

private data class SeqCredentialContext(
    val workspaceId: String?,
    val apiKey: String?,
    val authMode: String,
    val credentialSource: String,
)

private fun JsonObject.stringValue(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun toWebSocketUri(uri: URI): URI {
    val nextScheme = when (uri.scheme?.lowercase()) {
        "ws", "wss" -> return uri
        "http" -> "ws"
        "https" -> "wss"
        null -> throw IllegalArgumentException("URI with undefined scheme")
        else -> throw IllegalArgumentException("Unsupported WebSocket URI scheme: ${uri.scheme}")
    }

    // Preserve escaped query delimiters and path separators when changing only the scheme.
    return URI(nextScheme + uri.toASCIIString().substring(uri.scheme.length))
}

internal fun resolveSeqApiLink(base: URI, target: String): URI {
    val resolved = base.resolve(target)
    if (!resolved.scheme.equals(base.scheme, ignoreCase = true) ||
        !resolved.host.equals(base.host, ignoreCase = true) || effectivePort(resolved) != effectivePort(base) ||
        resolved.rawUserInfo != null || resolved.rawFragment != null
    ) {
        throw SeqApiException(
            "Seq returned an API link outside the configured server origin. Check SEQ_API_CANONICALURI and the reverse proxy configuration.",
            errorCode = "INVALID_API_LINK",
        )
    }
    return resolved
}

private fun effectivePort(uri: URI): Int =
    if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

private fun connectionFailure(error: Throwable): SeqApiException {
    if (error is CancellationException) throw error
    if (error is SeqApiException) return error
    val causes = generateSequence(error) { it.cause }.take(20).toList()
    val handshake = causes.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
    val (code, message) = when {
        handshake != null -> "WEBSOCKET_HANDSHAKE" to
                "Seq WebSocket upgrade failed (HTTP ${handshake.response.statusCode()}). Check API key permissions and reverse proxy Upgrade/Connection headers."

        causes.any { it is SSLException } -> "TLS_ERROR" to
                "Seq TLS verification failed. Check the certificate hostname, expiry, and CA trust in the IDE JVM."

        causes.any { it is HttpTimeoutException } -> "TIMEOUT" to
                "Seq request timed out. Check server availability and proxy timeouts."

        causes.any { it is UnknownHostException || it is java.nio.channels.UnresolvedAddressException } -> "DNS_ERROR" to
                "The Seq server hostname could not be resolved. Check the URL, DNS, and VPN connection."

        causes.any { it is ConnectException } -> "CONNECTION_FAILED" to
                "Could not connect to Seq. Check the host, port, firewall, and VPN connection."

        else -> "TRANSPORT_ERROR" to "Seq connection failed or was interrupted. Check server and proxy availability."
    }
    return SeqApiException(message, statusCode = handshake?.response?.statusCode(), errorCode = code, cause = error)
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(discard: (T) -> Unit = {}): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(true) }
        whenComplete { value, error ->
            if (error == null) {
                continuation.resume(value, onCancellation = { _, result, _ -> discard(result) })
            } else {
                continuation.resumeWithException(if (error is CompletionException) error.cause ?: error else error)
            }
        }
    }

/** Bound response allocation before parsing JSON or returning CSV to the model. */
private class LimitedStringBodySubscriber : HttpResponse.BodySubscriber<String> {
    private val delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
    private lateinit var subscription: Flow.Subscription
    private var receivedBytes = 0L
    private var failed = false

    override fun getBody(): CompletionStage<String> = delegate.body

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        delegate.onSubscribe(subscription)
    }

    override fun onNext(items: List<ByteBuffer>) {
        if (failed) return
        receivedBytes += items.sumOf { it.remaining().toLong() }
        if (receivedBytes > 8 * 1024 * 1024) {
            failed = true
            subscription.cancel()
            delegate.onError(
                SeqApiException(
                    "Seq response exceeds the 8 MiB limit. Narrow the query or select fewer fields.",
                    errorCode = "RESULT_TOO_LARGE",
                )
            )
        } else {
            delegate.onNext(items)
        }
    }

    override fun onError(error: Throwable) {
        if (!failed) delegate.onError(error)
    }

    override fun onComplete() {
        if (!failed) delegate.onComplete()
    }
}

private sealed interface WebSocketMessage {
    data class Text(val payload: String) : WebSocketMessage

    data class Closed(val statusCode: Int, val reason: String) : WebSocketMessage

    data class Failure(val error: Throwable) : WebSocketMessage
}

private class QueuingWebSocketListener : WebSocket.Listener {
    // Only request the next complete message after the consumer has processed the previous one.
    // The channel also has room for the terminal close/error notification.
    private val messages = Channel<WebSocketMessage>(Channel.UNLIMITED)
    private val currentText = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*> {
        if (currentText.length + data.length > 1_048_576) {
            messages.trySend(
                WebSocketMessage.Failure(
                    SeqApiException(
                        "Seq event exceeds the 1,048,576-character limit. Use SQL to select fewer fields.",
                        errorCode = "RESULT_TOO_LARGE",
                    )
                )
            )
            webSocket.abort()
            return CompletableFuture.completedFuture(null)
        }
        currentText.append(data)
        if (last) {
            messages.trySend(WebSocketMessage.Text(currentText.toString()))
            currentText.setLength(0)
        } else {
            webSocket.request(1)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*> {
        messages.trySend(WebSocketMessage.Closed(statusCode, reason))
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        messages.trySend(WebSocketMessage.Failure(error))
    }

    suspend fun receive(): WebSocketMessage = messages.receive()
}
