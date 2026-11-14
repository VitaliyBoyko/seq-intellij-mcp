// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolSchema
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqApiClient
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class SeqEventTools(
    private val backend: SeqMcpBackend,
    private val enabledProjectResolver: (String?) -> Project?,
) {
    fun seqSearchTool(): McpTool {
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
                            maximum = SeqApiClient.MAX_SEARCH_EVENTS,
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
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = seqSearchOutputSchema(),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val request = buildSeqSearchRequest(arguments)
            backend.validateSignalIfNeeded(request.signalId, request.workspace)

            val events = try {
                backend.searchEvents(request)
            } catch (error: SeqApiException) {
                if (error.statusCode != 400) throw error
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

    fun seqWaitForEventsTool(): McpTool {
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
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = eventsOutputSchema("capturedEvents"),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
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

    fun seqToStrictFilterTool(): McpTool {
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
                        "workspace" to workspaceInputSchema(),
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
            ensureEnabledProject(arguments, enabledProjectResolver)
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
}

private fun eventsOutputSchema(eventsProperty: String): McpToolSchema {
    return buildSchema(
        eventsProperty to arraySchema("Matching Seq events."),
        "capturedCount" to intSchema("Number of captured events.", minimum = 0),
        "workspace" to workspaceOutputSchema(),
    )
}

private fun seqSearchOutputSchema(): McpToolSchema {
    return buildSchema(
        "events" to arraySchema("Matching Seq events."),
        "workspace" to workspaceOutputSchema(),
        "afterId" to nullableStringSchema("Pagination cursor echoed back in the response."),
    )
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

    require(count in 1..SeqApiClient.MAX_SEARCH_EVENTS) { "count must be between 1 and ${SeqApiClient.MAX_SEARCH_EVENTS}" }
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
