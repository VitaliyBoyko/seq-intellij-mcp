// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqApiService
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend

class SeqMcpToolsProvider @JvmOverloads constructor(
    private val backend: SeqMcpBackend = service<SeqApiService>(),
    private val enabledProjectResolver: (String?) -> Project? = ::resolveEnabledProject,
) : McpToolsProvider {
    override fun getTools(): List<McpTool> {
        val event = SeqEventTools(backend, enabledProjectResolver)
        val sql = SeqSqlTools(backend, enabledProjectResolver)
        val resource = SeqResourceTools(backend, enabledProjectResolver)
        val identity = SeqIdentityTools(backend, enabledProjectResolver)
        val connection = SeqConnectionTools(enabledProjectResolver)
        return listOf(
            event.seqSearchTool(),
            sql.seqQuerySqlTool(),
            sql.seqDescribeSqlDialectTool(),
            event.seqToStrictFilterTool(),
            event.seqWaitForEventsTool(),
            resource.signalListTool(),
            identity.seqWhoAmITool(),
            identity.seqCapabilitiesTool(),
            sql.seqSqlQueryListTool(),
            sql.seqSqlQueryGetTool(),
            resource.seqWorkspaceListTool(),
            resource.seqWorkspaceGetTool(),
            resource.seqDashboardListTool(),
            resource.seqDashboardGetTool(),
            identity.seqCreatePermalinkTool(),
            connection.connectionSettingsTool(false),
            connection.connectionSettingsTool(true),
        )
    }
}
