package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class SeqMcpToolService {
    fun supportedTools(): List<SeqMcpToolDefinition> = SUPPORTED_TOOLS

    companion object {
        private val SUPPORTED_TOOLS = listOf(
            SeqMcpToolDefinition(
                name = "SeqSearch",
                description = "Search events in Seq, a structured log and observability server, using Seq filter expressions",
            ),
            SeqMcpToolDefinition(
                name = "SeqQuerySql",
                description = "Execute Seq SQL queries and return structured rows or CSV",
            ),
            SeqMcpToolDefinition(
                name = "SeqDescribeSqlDialect",
                description = "Describe the Seq SQL surface with examples and common pitfalls",
            ),
            SeqMcpToolDefinition(
                name = "SeqToStrictFilter",
                description = "Convert a loose search query into strict Seq filter syntax for Seq, a structured log server",
            ),
            SeqMcpToolDefinition(
                name = "SeqWaitForEvents",
                description = "Wait for and capture live events from Seq, a structured log and event stream server",
            ),
            SeqMcpToolDefinition(
                name = "SignalList",
                description = "List Seq signals, which are saved event queries and filters in the Seq server",
            ),
            SeqMcpToolDefinition(
                name = "SeqWhoAmI",
                description = "Resolve the current Seq auth context and user identity",
            ),
            SeqMcpToolDefinition(
                name = "SeqCapabilities",
                description = "Probe Seq query, identity, and permalink capabilities",
            ),
            SeqMcpToolDefinition(
                name = "SeqSqlQueryList",
                description = "List saved Seq SQL queries",
            ),
            SeqMcpToolDefinition(
                name = "SeqSqlQueryGet",
                description = "Get a saved Seq SQL query by id",
            ),
            SeqMcpToolDefinition(
                name = "SeqWorkspaceList",
                description = "List Seq workspaces",
            ),
            SeqMcpToolDefinition(
                name = "SeqWorkspaceGet",
                description = "Get a Seq workspace by id",
            ),
            SeqMcpToolDefinition(
                name = "SeqDashboardList",
                description = "List Seq dashboards",
            ),
            SeqMcpToolDefinition(
                name = "SeqDashboardGet",
                description = "Get a Seq dashboard by id",
            ),
            SeqMcpToolDefinition(
                name = "SeqCreatePermalink",
                description = "Create a stable Seq permalink for an event id",
            ),
        )

        fun getInstance(): SeqMcpToolService = service<SeqMcpToolService>()
    }
}

data class SeqMcpToolDefinition(
    val name: String,
    val description: String,
)
