// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.mcp

import com.intellij.mcpserver.McpTool
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal class SeqConnectionTools(
    private val enabledProjectResolver: (String?) -> Project?,
) {
    fun connectionSettingsTool(update: Boolean): McpTool {
        return JsonBackedTool(
            descriptor = descriptor(
                name = if (update) "SeqConfigureConnection" else "SeqGetConnectionSettings",
                description = if (update) {
                    "Update application-wide Seq connection settings. Requires an enabled project. API keys are stored in Password Safe and never returned. Omitted values are preserved."
                } else {
                    "Inspect application-wide Seq connection settings without returning API keys"
                },
                inputSchema = inputSchema(properties = if (update) mapOf(
                    "serverUrl" to stringSchema("Absolute HTTP(S) Seq URL. No embedded credentials, query or fragment."),
                    "apiKey" to stringSchema("API key to store in Password Safe; empty string removes the selected key. Omit to preserve."),
                    "workspace" to stringSchema("Workspace override to update; omit to update the default API key."),
                ) else emptyMap()),
                outputSchema = buildSchema(
                    "serverUrl" to stringSchema("Configured Seq server URL."),
                    "hasDefaultApiKey" to booleanSchema("Whether a default API key is stored."),
                    "workspaceOverrideCount" to intSchema("Number of workspace credential overrides.", minimum = 0),
                ),
            ),
        ) { arguments ->
            ensureEnabledProject(arguments, enabledProjectResolver)
            fun settingString(name: String): String? {
                if (name !in arguments) return null
                val value = arguments[name] as? JsonPrimitive
                require(value != null && value.isString) { "$name must be a string." }
                return value.content
            }
            val url = settingString("serverUrl")?.trim()
            val key = settingString("apiKey")
            val workspace = settingString("workspace")?.trim()
            if (update) {
                require(url != null || key != null) { "Provide serverUrl or apiKey to update." }
                require(workspace == null || (workspace.isNotEmpty() && key != null)) {
                    "workspace must be non-empty and supplied with apiKey."
                }
                if (url != null) {
                    val uri = runCatching { java.net.URI(url) }.getOrNull()
                    require(uri != null && uri.scheme?.lowercase() in setOf("http", "https") &&
                        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
                        uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
                        "serverUrl must be an absolute HTTP(S) URL without credentials, query or fragment."
                    }
                }
            }
            withContext(Dispatchers.IO) {
                val settings = service<SeqMcpSettingsService>()
                if (update) {
                    try {
                        if (key != null) settings.setApiKey(key, workspace)
                        if (url != null) settings.seqServerUrl = url
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        // Credential-provider diagnostics must not reach MCP clients.
                        throw IllegalStateException("Unable to save Seq connection settings. Check IDE Password Safe settings.")
                    }
                }
                success(
                    text = if (update) "Seq connection settings updated." else "Seq connection settings retrieved.",
                    structured = buildJsonObject {
                        put("serverUrl", JsonPrimitive(settings.seqServerUrl))
                        put("hasDefaultApiKey", JsonPrimitive(settings.hasDefaultApiKey()))
                        put("workspaceOverrideCount", JsonPrimitive(settings.workspaceOverrideCount()))
                    },
                )
            }
        }
    }
}
