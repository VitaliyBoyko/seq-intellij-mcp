package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.SeqMcpBundle

@Service(Service.Level.PROJECT)
class SeqMcpProjectService(private val project: Project) {
    private val projectSettings = SeqMcpProjectSettingsService.getInstance(project)
    private val settings = SeqMcpSettingsService.getInstance()
    private val tools = SeqMcpToolService.getInstance()

    fun projectName(): String = project.name

    fun isEnabledForProject(): Boolean = projectSettings.enabled

    fun connectionStatus(): String {
        if (!isEnabledForProject()) {
            return SeqMcpBundle.message("status.disabled")
        }
        if (!settings.isConfigured()) {
            return SeqMcpBundle.message("status.notConfigured")
        }

        return if (settings.hasAnyApiKey()) {
            SeqMcpBundle.message("status.configuredWithApiKey")
        } else {
            SeqMcpBundle.message("status.configuredWithoutApiKey")
        }
    }

    fun seqServerUrl(): String = settings.seqServerUrl.ifBlank { SeqMcpBundle.message("value.notSet") }

    fun apiKeyStatus(): String {
        val workspaceOverrideCount = settings.workspaceOverrideCount()
        return if (!settings.hasDefaultApiKey()) {
            if (workspaceOverrideCount > 0) {
                SeqMcpBundle.message("apiKey.workspaceOverridesOnly", workspaceOverrideCount)
            } else {
                SeqMcpBundle.message("apiKey.notConfigured")
            }
        } else if (workspaceOverrideCount > 0) {
            SeqMcpBundle.message("apiKey.configuredWithOverrides", workspaceOverrideCount)
        } else {
            SeqMcpBundle.message("apiKey.configured")
        }
    }

    fun supportedTools(): String {
        if (!isEnabledForProject()) {
            return SeqMcpBundle.message("toolWindow.toolsDisabledValue")
        }
        return tools.supportedTools().joinToString(", ") { it.name }
    }

    fun nextSteps(): String {
        return if (isEnabledForProject()) {
            SeqMcpBundle.message("toolWindow.nextSteps")
        } else {
            SeqMcpBundle.message("toolWindow.nextStepsDisabled")
        }
    }
}
