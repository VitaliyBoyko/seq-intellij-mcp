// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal class SeqResourceTools(
    private val backend: SeqMcpBackend,
    private val enabledProjectResolver: (String?) -> Project?,
) {
    fun signalListTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SignalList",
                description = "List Seq signals, which are saved event queries and filters in the Seq server",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "signals" to arraySchema("List of Seq signals, which are saved event queries and filters."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
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

    fun seqWorkspaceListTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqWorkspaceList",
                description = "List Seq workspaces",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "ownerId" to stringSchema("Optional owner id to filter personal workspaces."),
                        "shared" to booleanSchema("Include shared workspaces. Defaults to true."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "workspaces" to arraySchema("List of Seq workspaces."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val ownerId = optionalString(arguments, "ownerId")
            val shared = optionalBoolean(arguments, "shared") ?: true
            val workspace = optionalString(arguments, "workspace")
            val workspaces = backend.listWorkspaces(ownerId, shared, workspace)
            success(
                text = "SeqWorkspaceList returned ${workspaces.size} workspace item(s).",
                structured = buildJsonObject {
                    put("workspaces", buildJsonArray {
                        workspaces.forEach { add(summarizeWorkspace(it.jsonObject, detailed = false)) }
                    })
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    fun seqWorkspaceGetTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqWorkspaceGet",
                description = "Get a Seq workspace by id",
                inputSchema = inputSchema(
                    required = setOf("id"),
                    properties = mapOf(
                        "id" to stringSchema("The id of the Seq workspace."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "workspaceEntity" to objectSchema("Detailed Seq workspace metadata and included content ids."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val id = requiredString(arguments, "id")
            val workspace = optionalString(arguments, "workspace")
            val workspaceEntity = backend.getWorkspace(id, workspace)
            success(
                text = "SeqWorkspaceGet returned workspace `$id`.",
                structured = buildJsonObject {
                    put("workspaceEntity", summarizeWorkspace(workspaceEntity, detailed = true))
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    fun seqDashboardListTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqDashboardList",
                description = "List Seq dashboards",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "ownerId" to stringSchema("Optional owner id to filter personal dashboards."),
                        "shared" to booleanSchema("Include shared dashboards. Defaults to true."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "dashboards" to arraySchema("List of Seq dashboards."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val ownerId = optionalString(arguments, "ownerId")
            val shared = optionalBoolean(arguments, "shared") ?: true
            val workspace = optionalString(arguments, "workspace")
            val dashboards = backend.listDashboards(ownerId, shared, workspace)
            success(
                text = "SeqDashboardList returned ${dashboards.size} dashboard item(s).",
                structured = buildJsonObject {
                    put("dashboards", buildJsonArray {
                        dashboards.forEach { add(summarizeDashboard(it.jsonObject, detailed = false)) }
                    })
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    fun seqDashboardGetTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqDashboardGet",
                description = "Get a Seq dashboard by id",
                inputSchema = inputSchema(
                    required = setOf("id"),
                    properties = mapOf(
                        "id" to stringSchema("The id of the Seq dashboard."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "dashboard" to objectSchema("Detailed Seq dashboard metadata and chart summaries."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val id = requiredString(arguments, "id")
            val workspace = optionalString(arguments, "workspace")
            val dashboard = backend.getDashboard(id, workspace)
            success(
                text = "SeqDashboardGet returned dashboard `$id`.",
                structured = buildJsonObject {
                    put("dashboard", summarizeDashboard(dashboard, detailed = true))
                    putNullable("workspace", workspace)
                },
            )
        }
    }
}

private fun summarizeWorkspace(entity: JsonObject, detailed: Boolean): JsonObject {
    val content = entity["Content"]?.jsonObject
    val signalIds = content?.get("SignalIds")?.jsonArray ?: JsonArray(emptyList())
    val queryIds = content?.get("QueryIds")?.jsonArray ?: JsonArray(emptyList())
    val dashboardIds = content?.get("DashboardIds")?.jsonArray ?: JsonArray(emptyList())

    return buildJsonObject {
        putNullable("id", entity.stringValue("Id"))
        putNullable("title", entity.stringValue("Title"))
        putNullable("description", entity.stringValue("Description"))
        putNullable("ownerId", entity.stringValue("OwnerId"))
        put("shared", JsonPrimitive(entity.stringValue("OwnerId") == null))
        put("isProtected", JsonPrimitive(entity.booleanValue("IsProtected")))
        putNullable("defaultSignalExpression", entity["DefaultSignalExpression"]?.toSignalExpressionString())
        put("signalCount", JsonPrimitive(signalIds.size))
        put("queryCount", JsonPrimitive(queryIds.size))
        put("dashboardCount", JsonPrimitive(dashboardIds.size))
        if (detailed) {
            put("signalIds", signalIds)
            put("queryIds", queryIds)
            put("dashboardIds", dashboardIds)
        }
    }
}

private fun summarizeDashboard(entity: JsonObject, detailed: Boolean): JsonObject {
    val charts = entity["Charts"]?.jsonArray ?: JsonArray(emptyList())

    return buildJsonObject {
        putNullable("id", entity.stringValue("Id"))
        putNullable("title", entity.stringValue("Title"))
        putNullable("ownerId", entity.stringValue("OwnerId"))
        put("shared", JsonPrimitive(entity.stringValue("OwnerId") == null))
        put("isProtected", JsonPrimitive(entity.booleanValue("IsProtected")))
        putNullable("signalExpression", entity["SignalExpression"]?.toSignalExpressionString())
        put("chartCount", JsonPrimitive(charts.size))
        if (detailed) {
            put("charts", buildJsonArray {
                charts.forEach { chart ->
                    val chartObject = chart.jsonObject
                    add(
                        buildJsonObject {
                            putNullable("id", chartObject.stringValue("Id"))
                            putNullable("title", chartObject.stringValue("Title"))
                            putNullable("description", chartObject.stringValue("Description"))
                            put("queryCount", JsonPrimitive(chartObject["Queries"]?.jsonArray?.size ?: 0))
                            putNullable("signalExpression", chartObject["SignalExpression"]?.toSignalExpressionString())
                        },
                    )
                }
            })
        }
    }
}
