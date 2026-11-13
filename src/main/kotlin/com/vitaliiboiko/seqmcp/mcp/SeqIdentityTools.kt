// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqAuthContext
import com.vitaliiboiko.seqmcp.services.SeqCapabilityReport
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SeqIdentityTools(
    private val backend: SeqMcpBackend,
    private val enabledProjectResolver: (String?) -> Project?,
) {
    fun seqWhoAmITool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqWhoAmI",
                description = "Resolve the current Seq auth context and user identity for the configured credential",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "authContext" to objectSchema("Resolved Seq authentication context and current user metadata."),
                    "capabilities" to objectSchema("Capability checks performed against the current Seq credential."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val workspace = optionalString(arguments, "workspace")
            val capabilityReport = backend.getCapabilities(workspace)
            val message = capabilityReport.authContext.resolvedUserId?.let { userId ->
                "SeqWhoAmI resolved Seq user `$userId`."
            } ?: "SeqWhoAmI could not resolve a Seq user identity for the current credential."

            success(
                text = message,
                structured = buildJsonObject {
                    put("authContext", serializeAuthContext(capabilityReport.authContext))
                    put("capabilities", serializeCapabilityReport(capabilityReport))
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    fun seqCapabilitiesTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqCapabilities",
                description = "Probe Seq query, identity, and permalink capabilities for the configured credential",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "authContext" to objectSchema("Resolved Seq authentication context and current user metadata."),
                    "capabilities" to objectSchema("Capability checks performed against the current Seq credential."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val workspace = optionalString(arguments, "workspace")
            val capabilityReport = backend.getCapabilities(workspace)

            success(
                text = "SeqCapabilities checked event query, identity, and permalink support.",
                structured = buildJsonObject {
                    put("authContext", serializeAuthContext(capabilityReport.authContext))
                    put("capabilities", serializeCapabilityReport(capabilityReport))
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    fun seqCreatePermalinkTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqCreatePermalink",
                description = "Create a stable Seq permalink for a specific event id",
                inputSchema = inputSchema(
                    required = setOf("eventId"),
                    properties = mapOf(
                        "eventId" to stringSchema("The Seq event id to permalink."),
                        "includeEvent" to booleanSchema("If true, include the event payload in the returned permalink entity."),
                        "renderEvent" to booleanSchema("If true and includeEvent is also true, render the event message."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "permalink" to objectSchema("The created Seq permalink metadata."),
                    "authContext" to objectSchema("Resolved Seq authentication context and current user metadata."),
                    "capabilities" to objectSchema("Capability checks performed against the current Seq credential."),
                    "fallback" to objectSchema("Fallback event metadata returned when Seq permalink creation is unavailable."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val eventId = requiredString(arguments, "eventId")
            val includeEvent = optionalBoolean(arguments, "includeEvent") ?: false
            val renderEvent = optionalBoolean(arguments, "renderEvent") ?: false
            require(!renderEvent || includeEvent) { "renderEvent can only be true when includeEvent is true" }
            val workspace = optionalString(arguments, "workspace")
            val capabilityReport = backend.getCapabilities(workspace)

            if (!capabilityReport.canCreatePermalinks) {
                permalinkFailureResult(
                    message = buildPermalinkFailureMessage(capabilityReport),
                    workspace = workspace,
                    capabilityReport = capabilityReport,
                    fallback = buildPermalinkFallback(
                        backend = backend,
                        eventId = eventId,
                        workspace = workspace,
                        includeEvent = includeEvent,
                        renderEvent = renderEvent,
                    ),
                )
            } else {
                val permalinkResult = runCatching {
                    backend.createPermalink(
                        eventId = eventId,
                        workspace = workspace,
                        includeEvent = includeEvent,
                        renderEvent = renderEvent,
                    )
                }

                val error = permalinkResult.exceptionOrNull() as? SeqApiException
                if (error != null) {
                    permalinkFailureResult(
                        message = buildPermalinkFailureMessage(capabilityReport, error),
                        workspace = workspace,
                        capabilityReport = capabilityReport,
                        fallback = buildPermalinkFallback(
                            backend = backend,
                            eventId = eventId,
                            workspace = workspace,
                            includeEvent = includeEvent,
                            renderEvent = renderEvent,
                        ),
                    )
                } else {
                    val permalink = permalinkResult.getOrThrow()
                    success(
                        text = "SeqCreatePermalink created a permalink for event `$eventId`.",
                        structured = buildJsonObject {
                            put("permalink", summarizePermalink(permalink))
                            put("authContext", serializeAuthContext(capabilityReport.authContext))
                            put("capabilities", serializeCapabilityReport(capabilityReport))
                            putNullable("workspace", workspace)
                        },
                    )
                }
            }
        }
    }
}

private fun serializeAuthContext(authContext: SeqAuthContext): JsonObject {
    return buildJsonObject {
        put("authMode", JsonPrimitive(authContext.authMode))
        put("credentialSource", JsonPrimitive(authContext.credentialSource))
        putNullable("resolvedUserId", authContext.resolvedUserId)
        putNullable("resolvedUsername", authContext.resolvedUsername)
        putNullable("resolvedUserDisplayName", authContext.resolvedUserDisplayName)
        putNullable("resolvedWorkspace", authContext.resolvedWorkspace)
        putNullable("serverVersion", authContext.serverVersion)
        putNullable("serverProduct", authContext.serverProduct)
        putNullable("serverInstanceName", authContext.serverInstanceName)
    }
}

private fun serializeCapabilityReport(report: SeqCapabilityReport): JsonObject {
    return buildJsonObject {
        put("canQueryEvents", JsonPrimitive(report.canQueryEvents))
        put("canResolveCurrentUser", JsonPrimitive(report.canResolveCurrentUser))
        put("canCreatePermalinks", JsonPrimitive(report.canCreatePermalinks))
        putNullable("eventsEndpoint", report.eventsEndpoint)
        putNullable("currentUserEndpoint", report.currentUserEndpoint)
        putNullable("permalinkEndpoint", report.permalinkEndpoint)
        put(
            "notes",
            buildJsonArray {
                report.notes.forEach { note -> add(JsonPrimitive(note)) }
            },
        )
    }
}

private fun buildPermalinkFailureMessage(
    capabilityReport: SeqCapabilityReport,
    error: SeqApiException? = null,
): String {
    val rawMessage = error?.seqError?.takeIf { it.isNotBlank() }
        ?: error?.message?.takeIf { it.isNotBlank() }
    val anonymousMode = capabilityReport.authContext.authMode == "anonymous"
    val missingUserContext = !capabilityReport.canResolveCurrentUser ||
        rawMessage?.contains("valid user id", ignoreCase = true) == true

    return when {
        anonymousMode -> {
            "Permalink creation is unavailable in anonymous mode. Seq may return a display identity from /api/users/current, but /api/permalinks requires an authenticated user-scoped credential."
        }

        missingUserContext -> {
            "Permalink creation is unavailable: current Seq credential has no user identity. Search/query work, but permalink APIs require a user-scoped session or compatible API key."
        }

        rawMessage != null -> "Permalink creation failed: $rawMessage"
        else -> "Permalink creation is unavailable: the current Seq credential did not pass the permalink capability check."
    }
}

private suspend fun buildPermalinkFallback(
    backend: SeqMcpBackend,
    eventId: String,
    workspace: String?,
    includeEvent: Boolean,
    renderEvent: Boolean = false,
): JsonObject {
    val event = runCatching {
        backend.getEvent(
            id = eventId,
            workspace = workspace,
            render = includeEvent || renderEvent,
        )
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        null
    }

    return buildJsonObject {
        put("eventId", JsonPrimitive(eventId))
        putNullable("timestamp", event?.stringValue("Timestamp"))
        put("suggestedFilter", JsonPrimitive(buildEventIdFilter(eventId)))
        putNullable(
            "eventApiUrl",
            event?.get("ResolvedLinks")
                ?.jsonObject
                ?.get("Self")
                ?.jsonPrimitive
                ?.contentOrNull,
        )
        putNullable(
            "renderedMessage",
            event?.get("RenderedMessage")
                ?.jsonPrimitive
                ?.contentOrNull,
        )
        if (includeEvent && event != null) {
            put("event", event)
        }
    }
}

private fun permalinkFailureResult(
    message: String,
    workspace: String?,
    capabilityReport: SeqCapabilityReport,
    fallback: JsonObject,
): McpToolCallResult {
    return failure(
        text = message,
        structured = buildJsonObject {
            put("error", JsonPrimitive(message))
            put("authContext", serializeAuthContext(capabilityReport.authContext))
            put("capabilities", serializeCapabilityReport(capabilityReport))
            put("fallback", fallback)
            putNullable("workspace", workspace)
        },
    )
}

private fun buildEventIdFilter(eventId: String): String {
    return "@Id = \"${eventId.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

private fun summarizePermalink(entity: JsonObject): JsonObject {
    return buildJsonObject {
        putNullable("id", entity.stringValue("Id"))
        putNullable("eventId", entity.stringValue("EventId"))
        putNullable("ownerId", entity.stringValue("OwnerId"))
        putNullable("createdUtc", entity.stringValue("CreatedUtc"))
        entity["ResolvedLinks"]?.let { put("resolvedLinks", it) }
        entity["Links"]?.let { put("links", it) }
        entity["Event"]?.let { put("event", it) }
    }
}
