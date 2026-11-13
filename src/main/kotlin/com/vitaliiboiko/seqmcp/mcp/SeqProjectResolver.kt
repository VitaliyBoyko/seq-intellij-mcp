// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import kotlinx.serialization.json.JsonObject

internal fun ensureEnabledProject(arguments: JsonObject, enabledProjectResolver: (String?) -> Project?): Project {
    val requestedProjectPath = optionalString(arguments, "projectPath")
    return enabledProjectResolver(requestedProjectPath) ?: throw IllegalStateException(
        buildProjectDisabledMessage(requestedProjectPath),
    )
}

internal fun resolveEnabledProject(projectPath: String?): Project? {
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
