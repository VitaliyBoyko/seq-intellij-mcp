// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Instant

interface SeqMcpBackend {
    suspend fun searchEvents(request: SeqSearchRequest): JsonArray

    suspend fun waitForEvents(filter: String?, count: Int, workspace: String?): JsonArray

    suspend fun listSignals(workspace: String?): JsonArray

    suspend fun toStrictFilterExpression(fuzzy: String, workspace: String?): SeqStrictExpressionResult

    suspend fun querySql(request: SeqSqlQueryRequest): JsonObject

    suspend fun querySqlCsv(request: SeqSqlQueryRequest): String

    suspend fun listSqlQueries(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getSqlQuery(id: String, workspace: String?): JsonObject

    suspend fun listWorkspaces(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getWorkspace(id: String, workspace: String?): JsonObject

    suspend fun listDashboards(ownerId: String?, shared: Boolean, workspace: String?): JsonArray

    suspend fun getDashboard(id: String, workspace: String?): JsonObject

    suspend fun getCapabilities(workspace: String? = null): SeqCapabilityReport

    suspend fun getEvent(id: String, workspace: String? = null, render: Boolean = false): JsonObject

    suspend fun createPermalink(
        eventId: String,
        workspace: String? = null,
        includeEvent: Boolean = false,
        renderEvent: Boolean = false,
    ): JsonObject
}

data class SeqSearchRequest(
    val filter: String,
    val count: Int = 100,
    val workspace: String? = null,
    val signalId: String? = null,
    val fromDateUtc: Instant? = null,
    val toDateUtc: Instant? = null,
    val afterId: String? = null,
    val timeoutSeconds: Int = 15,
)

data class SeqSqlQueryRequest(
    val query: String,
    val workspace: String? = null,
    val signalId: String? = null,
    val rangeStartUtc: Instant? = null,
    val rangeEndUtc: Instant? = null,
    val timeoutSeconds: Int? = null,
)

class SeqApiException(
    message: String,
    val statusCode: Int? = null,
    val seqError: String? = null,
    val seqSuggestion: String? = null,
    val seqReasons: List<String> = emptyList(),
    val responseBody: String? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class SeqStrictExpressionResult(
    val strictExpression: String,
    val matchedAsText: Boolean,
    val reasonIfMatchedAsText: String? = null,
)

data class SeqAuthContext(
    val authMode: String,
    val credentialSource: String,
    val resolvedUserId: String? = null,
    val resolvedUsername: String? = null,
    val resolvedUserDisplayName: String? = null,
    val resolvedWorkspace: String? = null,
    val serverVersion: String? = null,
    val serverProduct: String? = null,
    val serverInstanceName: String? = null,
)

data class SeqCapabilityReport(
    val authContext: SeqAuthContext,
    val canQueryEvents: Boolean,
    val canResolveCurrentUser: Boolean,
    val canCreatePermalinks: Boolean,
    val eventsEndpoint: String? = null,
    val currentUserEndpoint: String? = null,
    val permalinkEndpoint: String? = null,
    val notes: List<String> = emptyList(),
)

@Service(Service.Level.APP)
class SeqApiService : SeqApiClient(
    serverUrl = { SeqMcpSettingsService.getInstance().seqServerUrl },
    apiKey = { workspace -> SeqMcpSettingsService.getInstance().getApiKey(workspace) },
), Disposable {
    override fun dispose() = close()

    companion object {
        fun getInstance(): SeqApiService = service<SeqApiService>()
    }
}
