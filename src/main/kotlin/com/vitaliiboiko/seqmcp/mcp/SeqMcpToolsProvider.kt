package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.components.service
import com.vitaliiboiko.seqmcp.services.SeqApiService
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

class SeqMcpToolsProvider @JvmOverloads constructor(
    private val backend: SeqMcpBackend = service<SeqApiService>(),
) : McpToolsProvider {
    private val toolCategory = McpToolCategory(
        shortName = "seq",
        fullyQualifiedName = "com.vitaliiboiko.seqmcp",
    )

    override fun getTools(): List<McpTool> {
        return listOf(
            seqSearchTool(),
            seqToStrictFilterTool(),
            seqWaitForEventsTool(),
            signalListTool(),
        )
    }

    private fun seqSearchTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqSearch",
                description = "Search events in Seq, a structured log and observability server, using Seq filter expressions",
                inputSchema = inputSchema(
                    required = setOf("filter"),
                    properties = mapOf(
                        "filter" to stringSchema(
                            "A Seq filter expression used to search structured log events in Seq. Use an empty string to query all events.",
                        ),
                        "count" to intSchema(
                            description = "Maximum number of events to return.",
                            defaultValue = 100,
                            minimum = 1,
                        ),
                        "signalId" to stringSchema(
                            "Optional id of a saved Seq signal, which is a reusable event query/filter in Seq.",
                        ),
                        "fromDateUtc" to stringSchema(
                            "Optional inclusive start timestamp in ISO-8601 UTC, for example 2026-04-18T10:15:30Z.",
                        ),
                        "toDateUtc" to stringSchema(
                            "Optional exclusive end timestamp in ISO-8601 UTC, for example 2026-04-18T11:15:30Z.",
                        ),
                        "afterId" to stringSchema(
                            "Optional event id for pagination. Returns events strictly after this id.",
                        ),
                        "timeoutSeconds" to intSchema(
                            description = "Search timeout in seconds.",
                            defaultValue = 15,
                            minimum = 1,
                            maximum = 300,
                        ),
                        "workspace" to stringSchema(
                            "Optional workspace key used for future workspace-specific credentials.",
                        ),
                    ),
                ),
                outputSchema = seqSearchOutputSchema(),
            ),
        ) { arguments ->
            val request = buildSeqSearchRequest(arguments)
            backend.validateSignalIfNeeded(request.signalId, request.workspace)

            val events = try {
                backend.searchEvents(request)
            } catch (error: SeqApiException) {
                throw IllegalArgumentException(buildSeqSearchErrorMessage(error))
            }

            success(
                text = "SeqSearch returned ${events.size} event(s).",
                structured = buildJsonObject {
                    put("events", events)
                    putNullable("workspace", request.workspace)
                    putNullable("afterId", request.afterId)
                },
            )
        }
    }

    private fun seqWaitForEventsTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqWaitForEvents",
                description = "Wait for and capture live events from Seq, a structured log and event stream server (5-second timeout)",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "filter" to stringSchema("Optional Seq filter expression used to match incoming structured log events."),
                        "count" to intSchema(
                            description = "Maximum number of events to capture.",
                            defaultValue = 10,
                            minimum = 1,
                            maximum = 100,
                        ),
                        "workspace" to stringSchema(
                            "Optional workspace key used for future workspace-specific credentials.",
                        ),
                    ),
                ),
                outputSchema = eventsOutputSchema("capturedEvents"),
            ),
        ) { arguments ->
            val filter = optionalString(arguments, "filter")
            val count = optionalInt(arguments, "count") ?: 10
            val workspace = optionalString(arguments, "workspace")
            val events = backend.waitForEvents(filter, count, workspace)
            success(
                text = "SeqWaitForEvents captured ${events.size} event(s) within 5 seconds.",
                structured = buildJsonObject {
                    put("capturedEvents", events)
                    put("capturedCount", JsonPrimitive(events.size))
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    private fun seqToStrictFilterTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqToStrictFilter",
                description = "Convert a fuzzy search query into strict Seq filter syntax for Seq, a structured log and observability server",
                inputSchema = inputSchema(
                    required = setOf("fuzzy"),
                    properties = mapOf(
                        "fuzzy" to stringSchema(
                            "A relaxed search query for Seq, such as error, timeout, or @Level = 'Error', to convert into strict Seq syntax.",
                        ),
                        "workspace" to stringSchema(
                            "Optional workspace key used for future workspace-specific credentials.",
                        ),
                    ),
                ),
                outputSchema = buildSchema(
                    "strictExpression" to stringSchema("The converted strict Seq filter expression for searching Seq events."),
                    "matchedAsText" to booleanSchema("Whether Seq treated the fuzzy expression as plain-text search instead of structured filter syntax."),
                    "reasonIfMatchedAsText" to nullableStringSchema(
                        "Explanation returned by Seq when the expression was treated as text search.",
                    ),
                ),
            ),
        ) { arguments ->
            val fuzzy = requiredString(arguments, "fuzzy")
            val workspace = optionalString(arguments, "workspace")
            val result = backend.toStrictFilterExpression(fuzzy, workspace)

            success(
                text = "Converted fuzzy filter to strict Seq expression.",
                structured = buildJsonObject {
                    put("strictExpression", JsonPrimitive(result.strictExpression))
                    put("matchedAsText", JsonPrimitive(result.matchedAsText))
                    putNullable("reasonIfMatchedAsText", result.reasonIfMatchedAsText)
                },
            )
        }
    }

    private fun signalListTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SignalList",
                description = "List Seq signals, which are saved event queries and filters in the Seq server",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "workspace" to stringSchema(
                            "Optional workspace key used for future workspace-specific credentials.",
                        ),
                    ),
                ),
                outputSchema = buildSchema(
                    "signals" to arraySchema("List of Seq signals, which are saved event queries and filters."),
                    "workspace" to nullableStringSchema("Workspace key echoed back in the response."),
                ),
            ),
        ) { arguments ->
            val workspace = optionalString(arguments, "workspace")
            val signals = backend.listSignals(workspace)
            success(
                text = "SignalList returned ${signals.size} signal(s).",
                structured = buildJsonObject {
                    put("signals", signals)
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    private fun descriptor(
        name: String,
        description: String,
        inputSchema: McpToolSchema,
        outputSchema: McpToolSchema,
    ): McpToolDescriptor {
        return McpToolDescriptor(
            name = name,
            description = description,
            category = toolCategory,
            fullyQualifiedName = "${toolCategory.fullyQualifiedName}.$name",
            inputSchema = inputSchema,
            outputSchema = outputSchema,
        )
    }
}

private class JsonBackedTool(
    override val descriptor: McpToolDescriptor,
    private val invoke: suspend (JsonObject) -> McpToolCallResult,
) : McpTool {
    override suspend fun call(args: JsonObject): McpToolCallResult {
        return runCatching {
            invoke(args)
        }.getOrElse { error ->
            McpToolCallResult.Companion.error(
                error.message ?: "Unexpected Seq MCP error",
                buildJsonObject {
                    put("error", JsonPrimitive(error.message ?: "Unexpected Seq MCP error"))
                },
            )
        }
    }
}

private fun inputSchema(
    required: Set<String> = emptySet(),
    properties: Map<String, JsonElement>,
): McpToolSchema = buildSchema(*properties.entries.map { it.key to it.value }.toTypedArray(), required = required)

private fun eventsOutputSchema(eventsProperty: String): McpToolSchema {
    return buildSchema(
        eventsProperty to arraySchema("Matching Seq events."),
        "capturedCount" to intSchema("Number of captured events.", minimum = 0),
        "workspace" to nullableStringSchema("Workspace key echoed back in the response."),
    )
}

private fun seqSearchOutputSchema(): McpToolSchema {
    return buildSchema(
        "events" to arraySchema("Matching Seq events."),
        "workspace" to nullableStringSchema("Workspace key echoed back in the response."),
        "afterId" to nullableStringSchema("Pagination cursor echoed back in the response."),
    )
}

private fun buildSchema(
    vararg properties: Pair<String, JsonElement>,
    required: Set<String> = emptySet(),
): McpToolSchema {
    return McpToolSchema.Companion.ofPropertiesMap(
        properties = linkedMapOf(*properties),
        requiredProperties = required,
        definitions = emptyMap(),
        definitionsPath = McpToolSchema.DEFAULT_DEFINITIONS_PATH,
    )
}

private fun stringSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }
}

private fun nullableStringSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", buildJsonArray {
            add(JsonPrimitive("string"))
            add(JsonPrimitive("null"))
        })
        put("description", JsonPrimitive(description))
    }
}

private fun intSchema(
    description: String,
    defaultValue: Int? = null,
    minimum: Int? = null,
    maximum: Int? = null,
): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
        defaultValue?.let { put("default", JsonPrimitive(it)) }
        minimum?.let { put("minimum", JsonPrimitive(it)) }
        maximum?.let { put("maximum", JsonPrimitive(it)) }
    }
}

private fun booleanSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }
}

private fun arraySchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put(
            "items",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
            },
        )
    }
}

private fun success(text: String, structured: JsonObject): McpToolCallResult {
    return McpToolCallResult.Companion.text(text, structured)
}

private fun requiredString(arguments: JsonObject, name: String): String {
    return arguments[name]?.jsonPrimitive?.content
        ?: throw IllegalArgumentException("Missing required parameter: $name")
}

private fun optionalString(arguments: JsonObject, name: String): String? {
    return arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun optionalInt(arguments: JsonObject, name: String): Int? {
    return arguments[name]?.jsonPrimitive?.intOrNull
}

private suspend fun SeqMcpBackend.validateSignalIfNeeded(signalId: String?, workspace: String?) {
    if (signalId == null) {
        return
    }

    val signalIds = listSignals(workspace).mapNotNull { signal ->
        signal.jsonObject["Id"]?.jsonPrimitive?.contentOrNull
    }.toSet()

    require(signalId in signalIds) {
        "Unknown signalId `$signalId`. Use SignalList to inspect available signals first."
    }
}

private fun buildSeqSearchRequest(arguments: JsonObject): SeqSearchRequest {
    val filter = normalizeFilter(requiredString(arguments, "filter"))
    val count = optionalInt(arguments, "count") ?: 100
    val workspace = optionalString(arguments, "workspace")
    val signalId = optionalString(arguments, "signalId")
    val fromDateUtc = optionalInstant(arguments, "fromDateUtc")
    val toDateUtc = optionalInstant(arguments, "toDateUtc")
    val afterId = optionalString(arguments, "afterId")
    val timeoutSeconds = optionalInt(arguments, "timeoutSeconds") ?: 15

    require(count > 0) { "count must be greater than 0" }
    require(timeoutSeconds in 1..300) { "timeoutSeconds must be between 1 and 300" }
    if (fromDateUtc != null && toDateUtc != null) {
        require(!fromDateUtc.isAfter(toDateUtc)) {
            "fromDateUtc must be earlier than or equal to toDateUtc"
        }
    }

    return SeqSearchRequest(
        filter = filter,
        count = count,
        workspace = workspace,
        signalId = signalId,
        fromDateUtc = fromDateUtc,
        toDateUtc = toDateUtc,
        afterId = afterId,
        timeoutSeconds = timeoutSeconds,
    )
}

private fun normalizeFilter(filter: String): String {
    return if (filter.trim() == "*") "" else filter
}

private fun optionalInstant(arguments: JsonObject, name: String): Instant? {
    val value = optionalString(arguments, name) ?: return null
    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        throw IllegalArgumentException("$name must be an ISO-8601 UTC timestamp like 2026-04-18T10:15:30Z")
    }
}

private fun buildSeqSearchErrorMessage(error: SeqApiException): String {
    val message = error.message.orEmpty()
    return if (error.statusCode == 400 && looksLikeFilterSyntaxError(message)) {
        "Seq rejected the filter syntax. Try \"\" for all events, \"error\" for text search, or @Level = \"Error\". Original error: $message"
    } else {
        message.ifBlank { "SeqSearch failed." }
    }
}

private fun looksLikeFilterSyntaxError(message: String): Boolean {
    val lower = message.lowercase()
    return "filter" in lower || "syntax" in lower || "parse" in lower || "unexpected" in lower
}

private fun JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}
