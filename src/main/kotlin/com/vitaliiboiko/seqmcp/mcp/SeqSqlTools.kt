// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolSchema
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqSqlQueryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class SeqSqlTools(
    private val backend: SeqMcpBackend,
    private val enabledProjectResolver: (String?) -> Project?,
) {
    fun seqQuerySqlTool(): McpTool {
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
                    if (error.statusCode != 400) throw error
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
                    if (error.statusCode != 400) throw error
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

    fun seqDescribeSqlDialectTool(): McpTool {
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

    fun seqSqlQueryListTool(): McpTool {
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

    fun seqSqlQueryGetTool(): McpTool {
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
}

private fun seqQuerySqlOutputSchema(): McpToolSchema {
    return buildSchema(
        "format" to stringSchema("The output format returned by the tool, either json or csv."),
        "query" to stringSchema("The SQL query that was executed."),
        "columns" to buildJsonObject {
            put("description", JsonPrimitive("Column names or descriptors returned by Seq."))
            put("type", JsonPrimitive("array"))
            put("items", buildJsonObject {})
        },
        "rows" to buildJsonObject {
            put("description", JsonPrimitive("Rows of values corresponding to the returned columns."))
            put("type", JsonPrimitive("array"))
            put("items", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject {})
            })
        },
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
