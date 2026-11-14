// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.APP)
@State(
    name = "com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService",
    storages = [Storage("SeqMcpSettings.xml")],
)
class SeqMcpSettingsService : SimplePersistentStateComponent<SeqMcpSettingsService.State>(State()) {
    private val connectionLock = Any()

    private fun <T> withSettingsLock(operation: () -> T): T = runOutsideReadAction {
        synchronized(connectionLock) { operation() }
    }

    private val connectionRevision = AtomicLong()
    val connectionSettingsRevision: Long get() = connectionRevision.get()

    private fun connectionSettingsChanged() {
        connectionRevision.incrementAndGet()
        ApplicationManager.getApplication().messageBus.syncPublisher(CONNECTION_SETTINGS_CHANGED).changed()
    }

    @Volatile
    private var defaultApiKeyPresent = state.hasDefaultApiKey

    init {
        AppExecutorUtil.getAppExecutorService().execute {
            withSettingsLock {
                val hasDefaultApiKey = !SeqMcpCredentialStore.getApiKey().isNullOrBlank()
                defaultApiKeyPresent = hasDefaultApiKey
                state.hasDefaultApiKey = hasDefaultApiKey
            }
        }
    }

    class State : BaseState() {
        var seqServerUrl by string("")
        var hasDefaultApiKey by property(false)
        var workspaceIds by string("")
    }

    var seqServerUrl: String
        get() = synchronized(connectionLock) { state.seqServerUrl.orEmpty() }
        set(value) = synchronized(connectionLock) {
            if (state.seqServerUrl != value) {
                state.seqServerUrl = value
                connectionSettingsChanged()
            }
        }

    fun hasDefaultApiKey(): Boolean = defaultApiKeyPresent

    fun getApiKey(workspaceId: String? = null): String? = withSettingsLock {
        SeqMcpCredentialStore.getApiKey(workspaceId)
    }

    fun setApiKey(value: String?, workspaceId: String? = null) = withSettingsLock {
        require(workspaceId == null || ('\r' !in workspaceId && '\n' !in workspaceId)) {
            "workspace must not contain line breaks."
        }
        val normalizedValue = value?.trim()?.takeIf { it.isNotEmpty() }
        SeqMcpCredentialStore.setApiKey(workspaceId, normalizedValue)

        if (workspaceId.isNullOrBlank()) {
            defaultApiKeyPresent = normalizedValue != null
            state.hasDefaultApiKey = defaultApiKeyPresent
        } else {
            val nextWorkspaceIds = workspaceIds().toMutableSet()
            if (normalizedValue == null) {
                nextWorkspaceIds.remove(workspaceId)
            } else {
                nextWorkspaceIds += workspaceId
            }
            state.workspaceIds = nextWorkspaceIds.joinToString("\n")
        }
        connectionSettingsChanged()
    }

    /** Serialize credentials, metadata, URL and the returned summary across all callers. */
    fun updateConnection(serverUrl: String?, apiKey: String?, workspaceId: String?): SeqConnectionSummary = withSettingsLock {
        if (apiKey != null) setApiKey(apiKey, workspaceId)
        if (serverUrl != null) seqServerUrl = serverUrl
        connectionSummary()
    }

    fun connectionSummary(): SeqConnectionSummary = withSettingsLock {
        SeqConnectionSummary(
            serverUrl = seqServerUrl.takeIf(::isSafeSeqServerUrl).orEmpty(),
            hasDefaultApiKey = hasDefaultApiKey(),
            workspaceOverrideCount = workspaceOverrideCount(),
        )
    }

    fun applySnapshot(snapshot: SeqMcpSettingsSnapshot): SeqMcpSettingsSnapshot = withSettingsLock {
        require(snapshot.workspaceApiKeyEntries.none { '\r' in it.workspaceId || '\n' in it.workspaceId }) {
            "workspace must not contain line breaks."
        }
        setApiKey(snapshot.defaultApiKey)
        setWorkspaceApiKeyEntries(snapshot.workspaceApiKeyEntries)
        seqServerUrl = snapshot.seqServerUrl
        loadSnapshot()
    }

    fun hasAnyApiKey(): Boolean = hasDefaultApiKey() || workspaceIds().isNotEmpty()

    fun loadSnapshot(): SeqMcpSettingsSnapshot = withSettingsLock {
        return@withSettingsLock SeqMcpSettingsSnapshot(
            seqServerUrl = seqServerUrl,
            defaultApiKey = getApiKey(),
            workspaceApiKeyEntries = workspaceIds().mapNotNull { workspaceId ->
                val apiKey = getApiKey(workspaceId) ?: return@mapNotNull null
                WorkspaceApiKeyEntry(workspaceId, apiKey)
            },
        )
    }

    fun workspaceApiKeyEntries(): List<WorkspaceApiKeyEntry> = withSettingsLock {
        return@withSettingsLock workspaceIds().mapNotNull { workspaceId ->
            val apiKey = getApiKey(workspaceId) ?: return@mapNotNull null
            WorkspaceApiKeyEntry(workspaceId, apiKey)
        }
    }

    fun setWorkspaceApiKeyEntries(entries: List<WorkspaceApiKeyEntry>) = withSettingsLock {
        require(entries.none { '\r' in it.workspaceId || '\n' in it.workspaceId }) {
            "workspace must not contain line breaks."
        }
        val normalized = entries
            .mapNotNull { entry ->
                val workspaceId = entry.workspaceId.trim()
                val apiKey = entry.apiKey.trim()
                if (workspaceId.isEmpty()) {
                    null
                } else if (apiKey.isEmpty()) {
                    null
                } else {
                    WorkspaceApiKeyEntry(workspaceId, apiKey)
                }
            }
            .distinctBy { it.workspaceId }

        val existingIds = workspaceIds().toSet()
        val nextIds = normalized.map { it.workspaceId }.toSet()

        existingIds.subtract(nextIds).forEach { workspaceId ->
            setApiKey(null, workspaceId)
        }

        normalized.forEach { entry ->
            setApiKey(entry.apiKey, entry.workspaceId)
        }

        state.workspaceIds = normalized.joinToString("\n") { it.workspaceId }
    }

    fun workspaceOverrideCount(): Int = withSettingsLock { workspaceIds().size }

    fun isConfigured(): Boolean = seqServerUrl.isNotBlank()

    private fun workspaceIds(): List<String> = withSettingsLock {
        return@withSettingsLock state.workspaceIds
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    }

    companion object {
        val CONNECTION_SETTINGS_CHANGED = Topic.create("Seq MCP connection settings", ConnectionSettingsListener::class.java)

        fun getInstance(): SeqMcpSettingsService = service<SeqMcpSettingsService>()
    }
}

private fun <T> runOutsideReadAction(operation: () -> T): T {
    val application = ApplicationManager.getApplication()
    if (!application.isReadAccessAllowed) {
        return operation()
    }

    return try {
        java.util.concurrent.CompletableFuture.supplyAsync(
            operation,
            AppExecutorUtil.getAppExecutorService(),
        ).join()
    } catch (error: CompletionException) {
        throw error.cause ?: error
    }
}

data class WorkspaceApiKeyEntry(
    val workspaceId: String,
    val apiKey: String,
)

data class SeqMcpSettingsSnapshot(
    val seqServerUrl: String,
    val defaultApiKey: String?,
    val workspaceApiKeyEntries: List<WorkspaceApiKeyEntry>,
)

private object SeqMcpCredentialStore {
    private const val DEFAULT_API_KEY_ACCOUNT = "SEQ_API_KEY"
    private const val SERVICE_NAME = "Seq MCP"

    fun getApiKey(workspaceId: String? = null): String? {
        return PasswordSafe.instance.getPassword(attributes(workspaceId))
    }

    fun setApiKey(workspaceId: String? = null, value: String?) {
        val normalizedValue = value?.takeIf { it.isNotBlank() }
        val credentials = normalizedValue?.let { Credentials(accountName(workspaceId), it) }
        PasswordSafe.instance.set(attributes(workspaceId), credentials)
    }

    private fun attributes(workspaceId: String? = null): CredentialAttributes {
        val serviceName = generateServiceName(SERVICE_NAME, accountName(workspaceId))
        return CredentialAttributes(serviceName)
    }

    private fun accountName(workspaceId: String?): String {
        return workspaceId?.takeIf { it.isNotBlank() }?.let { "SEQ_API_KEY::$it" } ?: DEFAULT_API_KEY_ACCOUNT
    }
}

fun interface ConnectionSettingsListener {
    fun changed()
}

data class SeqConnectionSummary(
    val serverUrl: String,
    val hasDefaultApiKey: Boolean,
    val workspaceOverrideCount: Int,
)

internal fun isSafeSeqServerUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
}
