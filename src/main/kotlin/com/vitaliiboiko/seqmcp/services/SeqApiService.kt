package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
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

class SeqApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class SeqStrictExpressionResult(
    val strictExpression: String,
    val matchedAsText: Boolean,
    val reasonIfMatchedAsText: String? = null,
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

        currentApiKey(workspace)?.let { builder.header(SEQ_API_KEY_HEADER, it) }

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

    private fun getResourceGroup(groupName: String, workspace: String?): JsonObject {
        val root = parseJson(sendRequest(resolveAgainstBase("api"), workspace).body()).jsonObject
        return parseJson(sendRequest(resolveResourceLink(root, "${groupName}Resources"), workspace).body()).jsonObject
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
                currentApiKey(workspace)?.let { header(SEQ_API_KEY_HEADER, it) }
            }
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        if (response.statusCode() in 200..299) {
            return response
        }

        val payload = runCatching { parseJson(response.body()).jsonObject }.getOrNull()
        val error = payload?.get("Error")?.jsonPrimitive?.contentOrNull
        val suffix = error?.let { ": $it" }.orEmpty()
        throw SeqApiException(
            message = "Seq API request failed with ${response.statusCode()}$suffix",
            statusCode = response.statusCode(),
        )
    }

    private fun parseJson(payload: String): JsonElement = json.parseToJsonElement(payload)

    private fun currentServerUri(): URI {
        val configured = settings.seqServerUrl.trim()
        require(configured.isNotBlank()) { "SEQ_SERVER_URL is not configured" }
        val normalized = if (configured.endsWith('/')) configured else "$configured/"
        return URI(normalized)
    }

    private fun currentApiKey(workspaceId: String?): String? {
        return settings.getApiKey(workspaceId)?.takeIf { it.isNotBlank() }
            ?: settings.getApiKey()?.takeIf { it.isNotBlank() }
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
