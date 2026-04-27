package com.vitaliiboiko.seqmcp.mcp

import com.intellij.ide.impl.ProjectUtil
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCategory
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSchema
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.vitaliiboiko.seqmcp.services.SeqApiService
import com.vitaliiboiko.seqmcp.services.SeqAuthContext
import com.vitaliiboiko.seqmcp.services.SeqCapabilityReport
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import com.vitaliiboiko.seqmcp.services.SeqSqlQueryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

class SeqMcpToolsProvider @JvmOverloads constructor(
    private val backend: SeqMcpBackend = service<SeqApiService>(),
    private val enabledProjectResolver: (String?) -> Project? = ::resolveEnabledProject,
) : McpToolsProvider {
    private val toolCategory = McpToolCategory(
        shortName = "seq",
        fullyQualifiedName = "com.vitaliiboiko.seqmcp",
    )

    override fun getTools(): List<McpTool> {
        return listOf(
            seqSearchTool(),
            seqQuerySqlTool(),
            seqDescribeSqlDialectTool(),
            seqToStrictFilterTool(),
            seqWaitForEventsTool(),
            signalListTool(),
            seqWhoAmITool(),
            seqCapabilitiesTool(),
            seqSqlQueryListTool(),
            seqSqlQueryGetTool(),
            seqWorkspaceListTool(),
            seqWorkspaceGetTool(),
            seqDashboardListTool(),
            seqDashboardGetTool(),
            seqCreatePermalinkTool(),
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

    private fun signalListTool(): McpTool {
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

    private fun seqWhoAmITool(): McpTool {
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

    private fun seqCapabilitiesTool(): McpTool {
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

    private fun seqQuerySqlTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqQuerySql",
                description = "Execute a Seq SQL query and return either structured rows or CSV output. Seq event queries read from `stream`, use `limit` instead of `top`, and should use `@Message like '%text%' ci` or SeqSearch for message text.",
                inputSchema = inputSchema(
                    required = setOf("query"),
                    properties = mapOf(
                        "query" to stringSchema(
                            """
                            A Seq SQL query to execute.
                            Working examples:
                            select count(*) as Events from stream
                            select @Timestamp, @Level, @Message from stream where @Level = 'Error' order by @Timestamp desc limit 50
                            select count(*) as Errors from stream where @Level = 'Error'
                            select percentile(Elapsed, 99) as P99 from stream group by RequestPath order by P99 desc limit 20
                            Use `from stream` as the source for event queries. Use `limit`, not `top`. For message text search in SQL, use `@Message like '%timeout%' ci`; for exploratory text search use SeqSearch.
                            """.trimIndent(),
                        ),
                        "rangeStartUtc" to stringSchema(
                            "Optional inclusive start timestamp in ISO-8601 UTC for the query time window.",
                        ),
                        "rangeEndUtc" to stringSchema(
                            "Optional exclusive end timestamp in ISO-8601 UTC for the query time window.",
                        ),
                        "signalId" to stringSchema(
                            "Optional id of a Seq signal used to scope the SQL query.",
                        ),
                        "workspace" to workspaceInputSchema(),
                        "timeoutSeconds" to intSchema(
                            description = "Optional SQL query timeout in seconds.",
                            minimum = 1,
                            maximum = 300,
                        ),
                        "format" to stringSchema(
                            "Optional output format. Use json for structured rows or csv for raw CSV output. Defaults to json.",
                        ),
                    ),
                ),
                outputSchema = seqQuerySqlOutputSchema(),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val request = buildSeqSqlQueryRequest(arguments)
            backend.validateSignalIfNeeded(request.signalId, request.workspace)

            val format = optionalString(arguments, "format")?.lowercase() ?: "json"
            require(format == "json" || format == "csv") { "format must be either json or csv" }

            if (format == "csv") {
                val csvResult = runCatching { backend.querySqlCsv(request) }
                val error = csvResult.exceptionOrNull() as? SeqApiException
                if (error != null) {
                    seqSqlFailureResult(request, error, format = "csv")
                } else {
                    val csv = csvResult.getOrThrow()
                    success(
                        text = "SeqQuerySql returned CSV output.",
                        structured = buildJsonObject {
                            put("format", JsonPrimitive("csv"))
                            put("query", JsonPrimitive(request.query))
                            put("csv", JsonPrimitive(csv))
                            putNullable("workspace", request.workspace)
                            putNullable("signalId", request.signalId)
                        },
                    )
                }
            } else {
                val queryResult = runCatching { backend.querySql(request) }
                val error = queryResult.exceptionOrNull() as? SeqApiException
                if (error != null) {
                    seqSqlFailureResult(request, error, format = "json")
                } else {
                    val result = queryResult.getOrThrow()
                    result["Error"]?.jsonPrimitive?.contentOrNull?.let { errorMessage ->
                        seqSqlFailureResult(
                            request = request,
                            error = buildSeqSqlErrorMessage(result, errorMessage),
                            suggestion = result["Suggestion"]?.jsonPrimitive?.contentOrNull,
                            reasons = result["Reasons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                            format = "json",
                        )
                    } ?: success(
                        text = "SeqQuerySql returned ${estimateQueryRowCount(result)} row(s).",
                        structured = buildJsonObject {
                            put("format", JsonPrimitive("json"))
                            put("query", JsonPrimitive(request.query))
                            put("columns", result["Columns"] ?: JsonArray(emptyList()))
                            result["Rows"]?.let { put("rows", it) }
                            result["Slices"]?.let { put("slices", it) }
                            result["Series"]?.let { put("series", it) }
                            result["Variables"]?.let { put("variables", it) }
                            result["Statistics"]?.let { put("statistics", it) }
                            result["Suggestion"]?.let { put("suggestion", it) }
                            result["Reasons"]?.let { put("reasons", it) }
                            put("rowCount", JsonPrimitive(estimateQueryRowCount(result)))
                            putNullable("workspace", request.workspace)
                            putNullable("signalId", request.signalId)
                        },
                    )
                }
            }
        }
    }

    private fun seqDescribeSqlDialectTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqDescribeSqlDialect",
                description = "Describe the Seq SQL surface accepted by SeqQuerySql, with working examples and common pitfalls",
                inputSchema = inputSchema(properties = emptyMap()),
                outputSchema = buildSchema(
                    "dialect" to objectSchema("Rules and common gotchas for Seq SQL queries."),
                    "exampleQueries" to arraySchema("Working Seq SQL query examples."),
                    "messageTextSearch" to objectSchema("Guidance for message text search in SQL vs SeqSearch."),
                ),
            ),
        ) {
            ensureEnabledProject(it, enabledProjectResolver)
            success(
                text = "SeqDescribeSqlDialect returned the Seq SQL contract and example queries.",
                structured = buildJsonObject {
                    put("dialect", buildSeqSqlDialectContract())
                    put("exampleQueries", buildSeqSqlExamples())
                    put("messageTextSearch", buildSeqSqlMessageSearchGuidance())
                },
            )
        }
    }

    private fun seqSqlQueryListTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqSqlQueryList",
                description = "List saved Seq SQL queries",
                inputSchema = inputSchema(
                    properties = mapOf(
                        "ownerId" to stringSchema("Optional owner id to filter personal SQL queries."),
                        "shared" to booleanSchema("Include shared SQL queries. Defaults to true."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "queries" to arraySchema("List of saved Seq SQL queries."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val ownerId = optionalString(arguments, "ownerId")
            val shared = optionalBoolean(arguments, "shared") ?: true
            val workspace = optionalString(arguments, "workspace")
            val queries = backend.listSqlQueries(ownerId, shared, workspace)
            success(
                text = "SeqSqlQueryList returned ${queries.size} query item(s).",
                structured = buildJsonObject {
                    put("queries", buildJsonArray {
                        queries.forEach { add(summarizeSqlQuery(it.jsonObject, includeSql = false)) }
                    })
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    private fun seqSqlQueryGetTool(): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = "SeqSqlQueryGet",
                description = "Get a saved Seq SQL query by id",
                inputSchema = inputSchema(
                    required = setOf("id"),
                    properties = mapOf(
                        "id" to stringSchema("The id of the saved Seq SQL query."),
                        "workspace" to workspaceInputSchema(),
                    ),
                ),
                outputSchema = buildSchema(
                    "query" to objectSchema("Saved Seq SQL query metadata and SQL text."),
                    "workspace" to workspaceOutputSchema(),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            val id = requiredString(arguments, "id")
            val workspace = optionalString(arguments, "workspace")
            val query = backend.getSqlQuery(id, workspace)
            success(
                text = "SeqSqlQueryGet returned query `$id`.",
                structured = buildJsonObject {
                    put("query", summarizeSqlQuery(query, includeSql = true))
                    putNullable("workspace", workspace)
                },
            )
        }
    }

    private fun seqWorkspaceListTool(): McpTool {
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

    private fun seqWorkspaceGetTool(): McpTool {
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

    private fun seqDashboardListTool(): McpTool {
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

    private fun seqDashboardGetTool(): McpTool {
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

    private fun seqCreatePermalinkTool(): McpTool {
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
): McpToolSchema {
    val propertiesWithProjectPath = linkedMapOf(
        "projectPath" to projectPathInputSchema(),
    ).apply {
        putAll(properties)
    }
    return buildSchema(*propertiesWithProjectPath.entries.map { it.key to it.value }.toTypedArray(), required = required)
}

private const val WORKSPACE_PARAMETER_DESCRIPTION =
    "Optional workspace credential key. If it matches a configured workspace API key override, that credential is used; otherwise the default Seq API key is used."

private const val WORKSPACE_OUTPUT_DESCRIPTION = "Workspace credential key echoed back in the response."

private const val PROJECT_PATH_PARAMETER_DESCRIPTION =
    "Optional absolute IDE project path used by JetBrains MCP clients to resolve the target project before dispatch. Seq MCP also uses it to prefer that project when multiple projects are open."

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

private fun seqQuerySqlOutputSchema(): McpToolSchema {
    return buildSchema(
        "format" to stringSchema("The output format returned by the tool, either json or csv."),
        "query" to stringSchema("The SQL query that was executed."),
        "columns" to arraySchema("Columns returned by the Seq SQL query."),
        "rows" to arraySchema("Tabular rows returned by the Seq SQL query."),
        "slices" to arraySchema("Time slices returned by time-grouped Seq SQL queries."),
        "series" to arraySchema("Time series returned by grouped Seq SQL queries."),
        "variables" to objectSchema("Variables returned by the query, if any."),
        "statistics" to objectSchema("Execution statistics reported by Seq."),
        "csv" to nullableStringSchema("CSV output when format=csv."),
        "rowCount" to intSchema("Estimated number of rows returned.", minimum = 0),
        "workspace" to workspaceOutputSchema(),
        "signalId" to nullableStringSchema("Signal id echoed back in the response."),
        "suggestion" to nullableStringSchema("Query suggestion returned by Seq, if any."),
        "reasons" to stringArraySchema("Additional reasons returned by Seq when a query fails."),
        "error" to nullableStringSchema("Normalized SQL execution error, if the query failed."),
        "dialect" to objectSchema("Seq SQL rules and common gotchas relevant to the query."),
        "exampleQueries" to arraySchema("Working Seq SQL query examples relevant to the query."),
        "messageTextSearch" to objectSchema("Guidance for text search in Seq SQL vs SeqSearch."),
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

private fun workspaceInputSchema(): JsonElement = stringSchema(WORKSPACE_PARAMETER_DESCRIPTION)

private fun workspaceOutputSchema(): JsonElement = nullableStringSchema(WORKSPACE_OUTPUT_DESCRIPTION)

private fun projectPathInputSchema(): JsonElement = stringSchema(PROJECT_PATH_PARAMETER_DESCRIPTION)

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

private fun stringArraySchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("description", JsonPrimitive(description))
        put(
            "items",
            buildJsonObject {
                put("type", JsonPrimitive("string"))
            },
        )
    }
}

private fun objectSchema(description: String): JsonElement {
    return buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("description", JsonPrimitive(description))
    }
}

private fun success(text: String, structured: JsonObject): McpToolCallResult {
    return McpToolCallResult.Companion.text(text, structured)
}

private fun failure(text: String, structured: JsonObject): McpToolCallResult {
    return McpToolCallResult.Companion.error(text, structured)
}

private fun seqSqlFailureResult(
    request: SeqSqlQueryRequest,
    error: SeqApiException,
    format: String,
): McpToolCallResult {
    return seqSqlFailureResult(
        request = request,
        error = buildSeqSqlErrorMessage(error),
        suggestion = error.seqSuggestion,
        reasons = error.seqReasons,
        format = format,
    )
}

private fun seqSqlFailureResult(
    request: SeqSqlQueryRequest,
    error: String,
    suggestion: String?,
    reasons: List<String>,
    format: String,
): McpToolCallResult {
    return failure(
        text = error,
        structured = buildJsonObject {
            put("error", JsonPrimitive(error))
            put("format", JsonPrimitive(format))
            put("query", JsonPrimitive(request.query))
            putNullable("workspace", request.workspace)
            putNullable("signalId", request.signalId)
            putNullable("suggestion", suggestion)
            put(
                "reasons",
                buildJsonArray {
                    reasons.forEach { reason -> add(JsonPrimitive(reason)) }
                },
            )
            put("dialect", buildSeqSqlDialectContract())
            put("exampleQueries", buildSeqSqlExamples())
            put("messageTextSearch", buildSeqSqlMessageSearchGuidance())
        },
    )
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

private fun optionalBoolean(arguments: JsonObject, name: String): Boolean? {
    return arguments[name]?.jsonPrimitive?.booleanOrNull
        ?: arguments[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
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
    val missingUserContext = !capabilityReport.canResolveCurrentUser ||
        rawMessage?.contains("valid user id", ignoreCase = true) == true

    return when {
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
    }.getOrNull()

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

private fun buildSeqSqlQueryRequest(arguments: JsonObject): SeqSqlQueryRequest {
    val query = requiredString(arguments, "query").trim()
    val workspace = optionalString(arguments, "workspace")
    val signalId = optionalString(arguments, "signalId")
    val rangeStartUtc = optionalInstant(arguments, "rangeStartUtc")
    val rangeEndUtc = optionalInstant(arguments, "rangeEndUtc")
    val timeoutSeconds = optionalInt(arguments, "timeoutSeconds")

    if (rangeStartUtc != null && rangeEndUtc != null) {
        require(!rangeStartUtc.isAfter(rangeEndUtc)) {
            "rangeStartUtc must be earlier than or equal to rangeEndUtc"
        }
    }

    return SeqSqlQueryRequest(
        query = query,
        workspace = workspace,
        signalId = signalId,
        rangeStartUtc = rangeStartUtc,
        rangeEndUtc = rangeEndUtc,
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

private fun buildSeqSqlErrorMessage(result: JsonObject, error: String): String {
    val reasons = result["Reasons"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    val suggestion = result["Suggestion"]?.jsonPrimitive?.contentOrNull
    return buildString {
        append("Seq rejected the SQL query: ")
        append(error)
        if (reasons.isNotEmpty()) {
            append(". Reasons: ")
            append(reasons.joinToString("; "))
        }
        if (!suggestion.isNullOrBlank()) {
            append(". Suggestion: ")
            append(suggestion)
        }
    }
}

private fun buildSeqSqlErrorMessage(error: SeqApiException): String {
    val baseMessage = error.seqError
        ?.takeIf { it.isNotBlank() }
        ?: error.responseBody
            ?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("{") }
        ?: error.message
        ?.takeIf { it.isNotBlank() }
        ?: "The query could not be executed."
    return buildString {
        append("Seq rejected the SQL query: ")
        append(baseMessage)
        if (error.seqReasons.isNotEmpty()) {
            append(". Reasons: ")
            append(error.seqReasons.joinToString("; "))
        }
        if (!error.seqSuggestion.isNullOrBlank()) {
            append(". Suggestion: ")
            append(error.seqSuggestion)
        }
        append(". Seq SQL event queries use `from stream` and `limit <n>`; use `@Message like '%text%' ci` for message text, or SeqSearch for exploratory text queries.")
    }
}

private fun buildSeqSqlDialectContract(): JsonObject {
    return buildJsonObject {
        put("source", JsonPrimitive("Use `from stream` for event queries. SeqQuerySql runs over the event stream and any active signal/workspace scope."))
        put("rowLimit", JsonPrimitive("Use `limit <n>` at the end of the query, not `top <n>`."))
        put("operators", JsonPrimitive("Use SQL-style operators and single-quoted strings in queries: `and`, `or`, `not`, `=` and `like`."))
        put("ordering", JsonPrimitive("Use `order by <column-or-alias> asc|desc`; for latest events, order by `@Timestamp desc`."))
        put("messageText", JsonPrimitive("SQL queries do not support free-text fragments like `\"timeout\"`; use `@Message like '%timeout%' ci` or SeqSearch."))
    }
}

private fun buildSeqSqlExamples(): JsonArray {
    return buildJsonArray {
        addSqlExample(
            title = "Hello world count",
            query = "select count(*) as Events from stream",
            note = "Minimal query that verifies the SQL surface is working.",
        )
        addSqlExample(
            title = "Latest error events",
            query = "select @Timestamp, @Level, @Message from stream where @Level = 'Error' order by @Timestamp desc limit 50",
            note = "Use `limit`, not `top`, when you want the latest rows.",
        )
        addSqlExample(
            title = "Message text search in SQL",
            query = "select @Timestamp, @Level, @Message from stream where @Message like '%timeout%' ci order by @Timestamp desc limit 20",
            note = "For message text in SQL, use `like`; for broader exploratory text search, prefer SeqSearch.",
        )
        addSqlExample(
            title = "Grouped error counts",
            query = "select count(*) as Errors from stream where @Level = 'Error' group by RequestPath order by Errors desc limit 20",
            note = "Example aggregation with grouping and ordering by an alias.",
        )
        addSqlExample(
            title = "P99 latency by endpoint",
            query = "select percentile(Elapsed, 99) as P99 from stream group by RequestPath order by P99 desc limit 20",
            note = "Example percentile aggregation using Seq's SQL-like query language.",
        )
    }
}

private fun buildSeqSqlMessageSearchGuidance(): JsonObject {
    return buildJsonObject {
        put("inSql", JsonPrimitive("Use SQL predicates like `@Message like '%timeout%' ci` when the text search needs to participate in a SQL query."))
        put("useSeqSearch", JsonPrimitive("Use SeqSearch for fuzzy or exploratory text search, especially when you are starting from an error string or mixed filter conditions."))
        put("notSupportedInSql", JsonPrimitive("Free-text fragments such as `\"timeout\"` are not valid inside Seq SQL queries."))
    }
}

private fun JsonArrayBuilder.addSqlExample(title: String, query: String, note: String) {
    add(
        buildJsonObject {
            put("title", JsonPrimitive(title))
            put("query", JsonPrimitive(query))
            put("note", JsonPrimitive(note))
        },
    )
}

private fun looksLikeFilterSyntaxError(message: String): Boolean {
    val lower = message.lowercase()
    return "filter" in lower || "syntax" in lower || "parse" in lower || "unexpected" in lower
}

private fun JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun summarizeSqlQuery(entity: JsonObject, includeSql: Boolean): JsonObject {
    return buildJsonObject {
        putNullable("id", entity.stringValue("Id"))
        putNullable("title", entity.stringValue("Title"))
        putNullable("description", entity.stringValue("Description"))
        putNullable("ownerId", entity.stringValue("OwnerId"))
        put("shared", JsonPrimitive(entity.stringValue("OwnerId") == null))
        put("isProtected", JsonPrimitive(entity.booleanValue("IsProtected")))
        if (includeSql) {
            putNullable("sql", entity.stringValue("Sql"))
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

private fun estimateQueryRowCount(result: JsonObject): Int {
    result["Rows"]?.jsonArray?.let { return it.size }
    result["Slices"]?.jsonArray?.let { slices ->
        return slices.sumOf { slice ->
            slice.jsonObject["Rows"]?.jsonArray?.size ?: 0
        }
    }
    result["Series"]?.jsonArray?.let { series ->
        return series.sumOf { seriesEntry ->
            seriesEntry.jsonObject["Slices"]?.jsonArray?.sumOf { slice ->
                slice.jsonObject["Rows"]?.jsonArray?.size ?: 0
            } ?: 0
        }
    }
    return 0
}

private fun JsonObject.stringValue(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean {
    return this[name]?.jsonPrimitive?.booleanOrNull
        ?: this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        ?: false
}

private fun JsonElement.toSignalExpressionString(): String? {
    return when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> when {
            this["SignalId"] != null -> this["SignalId"]?.jsonPrimitive?.contentOrNull
            else -> this.toString()
        }
        else -> toString()
    }
}

private fun ensureEnabledProject(arguments: JsonObject, enabledProjectResolver: (String?) -> Project?): Project {
    val requestedProjectPath = optionalString(arguments, "projectPath")
    return enabledProjectResolver(requestedProjectPath) ?: throw IllegalStateException(
        buildProjectDisabledMessage(requestedProjectPath),
    )
}

private fun resolveEnabledProject(projectPath: String?): Project? {
    val openProjects = ProjectManager.getInstance().openProjects.filterNot(Project::isDisposed)
    normalizeProjectPath(projectPath)?.let { normalizedProjectPath ->
        openProjects.firstOrNull { project ->
            project.service<SeqMcpProjectSettingsService>().enabled &&
                normalizeProjectPath(project.basePath) == normalizedProjectPath
        }?.let { return it }
    }

    val activeProject = ProjectUtil.getActiveProject()
    if (activeProject != null && activeProject in openProjects && activeProject.service<SeqMcpProjectSettingsService>().enabled) {
        return activeProject
    }

    val enabledProjects = openProjects.filter { it.service<SeqMcpProjectSettingsService>().enabled }
    return enabledProjects.singleOrNull()
}

private fun buildProjectDisabledMessage(projectPath: String?): String {
    val normalizedProjectPath = normalizeProjectPath(projectPath)
    return if (normalizedProjectPath != null) {
        "Seq MCP is disabled for project `$normalizedProjectPath`, or that project is not open. Enable it in Settings | Tools | Seq MCP."
    } else {
        "Seq MCP is disabled for the active project. Enable it in Settings | Tools | Seq MCP."
    }
}

private fun normalizeProjectPath(projectPath: String?): String? {
    return projectPath
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { path ->
            runCatching { java.nio.file.Path.of(path).toAbsolutePath().normalize().toString() }.getOrDefault(path)
        }
}
