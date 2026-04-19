package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

interface SeqMcpBackend {
    suspend fun searchEvents(request: SeqSearchRequest): JsonArray

    suspend fun waitForEvents(filter: String?, count: Int, workspace: String?): JsonArray

    suspend fun listSignals(workspace: String?): JsonArray

    suspend fun toStrictFilterExpression(fuzzy: String, workspace: String?): SeqStrictExpressionResult

    suspend fun querySql(request: SeqSqlQueryRequest): JsonObject

    suspend fun querySqlCsv(request: SeqSqlQueryRequest): String

    suspend fun listSqlQueries(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getSqlQuery(id: String, workspace: String?): JsonObject

    suspend fun listWorkspaces(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getWorkspace(id: String, workspace: String?): JsonObject

    suspend fun listDashboards(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getDashboard(id: String, workspace: String?): JsonObject

    suspend fun getCapabilities(workspace: String? = null): SeqCapabilityReport

    suspend fun getEvent(id: String, workspace: String? = null, render: Boolean = false): JsonObject

    suspend fun createPermalink(
        eventId: String,
        workspace: String? = null,
        includeEvent: Boolean = false,
        renderEvent: Boolean = false,
    ): JsonObject
}

data class SeqSearchRequest(
    val filter: String,
    val count: Int = 100,
    val workspace: String? = null,
    val signalId: String? = null,
    val fromDateUtc: Instant? = null,
    val toDateUtc: Instant? = null,
    val afterId: String? = null,
    val timeoutSeconds: Int = 15,
)

data class SeqSqlQueryRequest(
    val query: String,
    val workspace: String? = null,
    val signalId: String? = null,
    val rangeStartUtc: Instant? = null,
    val rangeEndUtc: Instant? = null,
    val timeoutSeconds: Int? = null,
)

class SeqApiException(
    message: String,
    val statusCode: Int? = null,
    val seqError: String? = null,
    val seqSuggestion: String? = null,
    val seqReasons: List<String> = emptyList(),
    val responseBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class SeqStrictExpressionResult(
    val strictExpression: String,
    val matchedAsText: Boolean,
    val reasonIfMatchedAsText: String? = null,
)

data class SeqAuthContext(
    val authMode: String,
    val credentialSource: String,
    val resolvedUserId: String? = null,
    val resolvedUsername: String? = null,
    val resolvedUserDisplayName: String? = null,
    val resolvedWorkspace: String? = null,
    val serverVersion: String? = null,
    val serverProduct: String? = null,
    val serverInstanceName: String? = null,
)

data class SeqCapabilityReport(
    val authContext: SeqAuthContext,
    val canQueryEvents: Boolean,
    val canResolveCurrentUser: Boolean,
    val canCreatePermalinks: Boolean,
    val eventsEndpoint: String? = null,
    val currentUserEndpoint: String? = null,
    val permalinkEndpoint: String? = null,
    val notes: List<String> = emptyList(),
)

@Service(Service.Level.APP)
class SeqApiService : SeqMcpBackend {
    private val settings = SeqMcpSettingsService.getInstance()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
        require(request.count > 0) { "count must be greater than 0" }
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
                notes += "Event query check failed: ${error.message ?: "unknown error"}"
                false
            }

            val canResolveCurrentUser = currentUser?.stringValue("Id") != null
            val canCreatePermalinks = permalinksGroup != null && canResolveCurrentUser

            if (!canCreatePermalinks && permalinksGroup != null && !canResolveCurrentUser) {
                notes += "Permalink creation requires a Seq user identity; the current credential did not resolve one."
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
    ): JsonArray {
        return withContext(Dispatchers.IO) {
            val resourceGroup = getResourceGroup(groupName, workspace)
            val uri = toWebSocketUri(resolveResourceLink(resourceGroup, linkName, parameters))
            collectWebSocketMessages(uri, workspace, maxMessages, timeout)
        }
    }

    private fun collectWebSocketMessages(uri: URI, workspace: String?, maxMessages: Int, timeout: Duration): JsonArray {
        val listener = QueuingWebSocketListener()
        val builder = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))

        resolveCredentialContext(workspace).apiKey?.let { builder.header(SEQ_API_KEY_HEADER, it) }

        val webSocket = builder.buildAsync(uri, listener).join()
        val elements = ArrayList<JsonElement>(maxMessages)
        val deadlineNanos = System.nanoTime() + timeout.toNanos()

        try {
            while (elements.size < maxMessages) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    break
                }

                when (val next = listener.poll(Duration.ofNanos(remainingNanos))) {
                    null -> break
                    is WebSocketMessage.Text -> elements += parseJson(next.payload)
                    is WebSocketMessage.Closed -> break
                    is WebSocketMessage.Failure -> throw IOException("Seq stream failed", next.error)
                }
            }
        } finally {
            runCatching {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
            }
        }

        return JsonArray(elements)
    }

    private fun getApiRoot(workspace: String?): JsonObject {
        return parseJson(sendRequest(resolveAgainstBase("api"), workspace).body()).jsonObject
    }

    private fun getResourceGroup(groupName: String, workspace: String?, root: JsonObject? = null): JsonObject {
        val resolvedRoot = root ?: getApiRoot(workspace)
        return parseJson(sendRequest(resolveResourceLink(resolvedRoot, "${groupName}Resources"), workspace).body()).jsonObject
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
        val base = currentServerUri()
        return if (URI(target).isAbsolute) {
            URI(target)
        } else {
            base.resolve(target)
        }
    }

    private fun sendRequest(uri: URI, workspace: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/json")
            .apply {
                resolveCredentialContext(workspace).apiKey?.let { header(SEQ_API_KEY_HEADER, it) }
            }
            .GET()
            .build()

        return sendChecked(request)
    }

    private fun sendDeleteRequest(uri: URI, workspace: String?, body: JsonObject): HttpResponse<String> {
        return sendJsonRequest(
            method = "DELETE",
            uri = uri,
            workspace = workspace,
            body = body,
        )
    }

    private fun sendJsonRequest(
        method: String,
        uri: URI,
        workspace: String?,
        body: JsonObject,
        accept: String = "application/json",
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", accept)
            .header("Content-Type", "application/json")
            .apply {
                resolveCredentialContext(workspace).apiKey?.let { header(SEQ_API_KEY_HEADER, it) }
            }
            .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build()

        return sendChecked(request)
    }

    private fun sendChecked(request: HttpRequest): HttpResponse<String> {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() in 200..299) {
            return response
        }

        val payload = runCatching { parseJson(response.body()).jsonObject }.getOrNull()
        val error = payload?.get("Error")?.jsonPrimitive?.contentOrNull
        val suggestion = payload?.get("Suggestion")?.jsonPrimitive?.contentOrNull
        val reasons = payload?.get("Reasons")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val suffix = error?.let { ": $it" }.orEmpty()
        throw SeqApiException(
            message = "Seq API request failed with ${response.statusCode()}$suffix",
            statusCode = response.statusCode(),
            seqError = error,
            seqSuggestion = suggestion,
            seqReasons = reasons,
            responseBody = response.body(),
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
        val configured = settings.seqServerUrl.trim()
        require(configured.isNotBlank()) { "SEQ_SERVER_URL is not configured" }
        val normalized = if (configured.endsWith('/')) configured else "$configured/"
        return URI(normalized)
    }

    private fun resolveCredentialContext(workspaceId: String?): SeqCredentialContext {
        val normalizedWorkspaceId = workspaceId?.takeIf { it.isNotBlank() }
        val workspaceApiKey = normalizedWorkspaceId
            ?.let(settings::getApiKey)
            ?.takeIf { it.isNotBlank() }
        val defaultApiKey = settings.getApiKey()?.takeIf { it.isNotBlank() }

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

    private fun loadResourceGroupOrNull(
        groupName: String,
        workspace: String?,
        root: JsonObject,
        notes: MutableList<String>,
    ): JsonObject? {
        return runCatching { getResourceGroup(groupName, workspace, root) }.getOrElse { error ->
            notes += "$groupName resource group unavailable: ${error.message ?: "unknown error"}"
            null
        }
    }

    private fun getCurrentUser(usersGroup: JsonObject, workspace: String?): JsonObject? {
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

    companion object {
        private const val SEQ_API_KEY_HEADER = "X-Seq-ApiKey"
        private val QUERY_TEMPLATE = Regex("""\{([?&])([^}]+)}""")
        private val SIMPLE_TEMPLATE = Regex("""\{([^?&][^}]*)}""")

        fun getInstance(): SeqApiService = service<SeqApiService>()
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

    return URI(
        nextScheme,
        uri.userInfo,
        uri.host,
        uri.port,
        uri.path,
        uri.query,
        uri.fragment,
    )
}

private sealed interface WebSocketMessage {
    data class Text(val payload: String) : WebSocketMessage

    data class Closed(val statusCode: Int, val reason: String) : WebSocketMessage

    data class Failure(val error: Throwable) : WebSocketMessage
}

private class QueuingWebSocketListener : WebSocket.Listener {
    private val messages = LinkedBlockingQueue<WebSocketMessage>()
    private val currentText = StringBuilder()

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*> {
        currentText.append(data)
        if (last) {
            messages.offer(WebSocketMessage.Text(currentText.toString()))
            currentText.setLength(0)
        }
        webSocket.request(1)
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*> {
        messages.offer(WebSocketMessage.Closed(statusCode, reason))
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        messages.offer(WebSocketMessage.Failure(error))
    }

    fun poll(timeout: Duration): WebSocketMessage? {
        return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}
