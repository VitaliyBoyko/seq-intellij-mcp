package com.vitaliiboiko.seqmcp

import com.intellij.openapi.components.service
import com.intellij.mcpserver.McpTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vitaliiboiko.seqmcp.mcp.SeqMcpToolsProvider
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqAuthContext
import com.vitaliiboiko.seqmcp.services.SeqCapabilityReport
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectService
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import com.vitaliiboiko.seqmcp.services.SeqSqlQueryRequest
import com.vitaliiboiko.seqmcp.services.SeqStrictExpressionResult
import com.vitaliiboiko.seqmcp.services.SeqMcpToolService
import com.vitaliiboiko.seqmcp.services.WorkspaceApiKeyEntry
import com.vitaliiboiko.seqmcp.services.toWebSocketUri
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Path
import java.time.Instant

class SeqMcpPluginTest : BasePlatformTestCase() {

    fun testSettingsStartEmpty() {
        val settings = resetSettings()

        assertEquals("", settings.seqServerUrl)
        assertFalse(settings.isConfigured())
        assertTrue(settings.workspaceApiKeyEntries().isEmpty())
    }

    fun testProjectServiceStartsNotConfigured() {
        resetSettings()
        val projectService = project.service<SeqMcpProjectService>()

        assertEquals(SeqMcpBundle.message("status.notConfigured"), projectService.connectionStatus())
        assertEquals(SeqMcpBundle.message("value.notSet"), projectService.seqServerUrl())
    }

    fun testProjectServiceConfiguredWithUrlOnly() {
        val settings = resetSettings()
        settings.seqServerUrl = "http://localhost:5341"

        val projectService = project.service<SeqMcpProjectService>()

        assertEquals(SeqMcpBundle.message("status.configuredWithoutApiKey"), projectService.connectionStatus())
    }

    fun testProjectServiceReportsDisabledWhenProjectToggleIsOff() {
        resetSettings()
        project.service<SeqMcpProjectSettingsService>().enabled = false

        val projectService = project.service<SeqMcpProjectService>()

        assertEquals(SeqMcpBundle.message("status.disabled"), projectService.connectionStatus())
        assertEquals(
            SeqMcpBundle.message("toolWindow.nextStepsDisabled"),
            projectService.nextSteps(),
        )
    }

    fun testWorkspaceApiKeyEntriesRoundTrip() {
        val settings = resetSettings()

        settings.setWorkspaceApiKeyEntries(
            listOf(
                WorkspaceApiKeyEntry("production", "prod-key"),
                WorkspaceApiKeyEntry("staging", "stage-key"),
            ),
        )

        assertEquals("prod-key", settings.getApiKey("production"))
        assertEquals("stage-key", settings.getApiKey("staging"))
        assertEquals(2, settings.workspaceOverrideCount())
    }

    fun testToolsProviderCallsSeqSearchBackend() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive("""@Level = "Error""""))
                put("count", JsonPrimitive(25))
                put("workspace", JsonPrimitive("production"))
            },
        )

        assertEquals("""@Level = "Error"""", backend.searchRequest?.filter)
        assertEquals(25, backend.searchRequest?.count)
        assertEquals("production", backend.searchRequest?.workspace)
        assertEquals(1, result.structuredContent!!["events"]?.jsonArray?.size)
    }

    fun testSeqSearchNormalizesWildcardFilter() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive("*"))
            },
        )

        assertEquals("", backend.searchRequest?.filter)
    }

    fun testSeqSearchPassesDateRangePaginationAndTimeout() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive(""))
                put("signalId", JsonPrimitive("signal-1"))
                put("fromDateUtc", JsonPrimitive("2026-04-18T10:15:30Z"))
                put("toDateUtc", JsonPrimitive("2026-04-18T11:15:30Z"))
                put("afterId", JsonPrimitive("event-123"))
                put("timeoutSeconds", JsonPrimitive(45))
            },
        )

        assertEquals("signal-1", backend.searchRequest?.signalId)
        assertEquals(Instant.parse("2026-04-18T10:15:30Z"), backend.searchRequest?.fromDateUtc)
        assertEquals(Instant.parse("2026-04-18T11:15:30Z"), backend.searchRequest?.toDateUtc)
        assertEquals("event-123", backend.searchRequest?.afterId)
        assertEquals(45, backend.searchRequest?.timeoutSeconds)
    }

    fun testToolsAdvertiseOptionalProjectPathInEveryInputSchema() {
        val tools = createToolsProvider(RecordingBackend()).getTools()

        tools.forEach { tool ->
            val projectPathSchema = tool.descriptor.inputSchema.propertiesSchema["projectPath"]?.jsonObject
            assertNotNull("Expected ${tool.descriptor.name} to advertise projectPath", projectPathSchema)
            assertEquals("string", projectPathSchema!!["type"]?.jsonPrimitive?.content)
            assertFalse(tool.descriptor.inputSchema.requiredProperties.contains("projectPath"))
        }
    }

    fun testToolsForwardProjectPathToResolver() = runBlocking {
        val backend = RecordingBackend()
        val expectedProjectPath = Path.of(project.basePath!!).toAbsolutePath().normalize().toString()
        var resolvedProjectPath: String? = null
        val tool = SeqMcpToolsProvider(
            backend = backend,
            enabledProjectResolver = { projectPath ->
                resolvedProjectPath = projectPath
                project
            },
        ).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("projectPath", JsonPrimitive(expectedProjectPath))
                put("filter", JsonPrimitive(""))
            },
        )

        assertFalse(result.isError)
        assertEquals(expectedProjectPath, resolvedProjectPath)
        assertEquals("", backend.searchRequest?.filter)
    }

    fun testSeqSearchRejectsInvalidDateRange() = runBlocking<Unit> {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive(""))
                put("fromDateUtc", JsonPrimitive("2026-04-18T11:15:30Z"))
                put("toDateUtc", JsonPrimitive("2026-04-18T10:15:30Z"))
            },
        )

        assertTrue(result.isError)
        assertStringContains(result.structuredContent!!["error"]!!.jsonPrimitive.content, "fromDateUtc")
    }

    fun testSeqSearchRejectsInvalidDateFormat() = runBlocking<Unit> {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive(""))
                put("fromDateUtc", JsonPrimitive("2026-04-18 10:15:30"))
            },
        )

        assertTrue(result.isError)
        assertStringContains(result.structuredContent!!["error"]!!.jsonPrimitive.content, "ISO-8601 UTC")
    }

    fun testSeqSearchRejectsUnknownSignal() = runBlocking<Unit> {
        val backend = RecordingBackend().apply {
            availableSignals = buildJsonArray { }
        }
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive(""))
                put("signalId", JsonPrimitive("missing-signal"))
            },
        )

        assertTrue(result.isError)
        assertStringContains(result.structuredContent!!["error"]!!.jsonPrimitive.content, "Unknown signalId")
    }

    fun testSeqSearchProvidesFilterSyntaxGuidance() = runBlocking<Unit> {
        val backend = RecordingBackend().apply {
            searchException = SeqApiException("Filter syntax error near `@Level =`", 400)
        }
        val tool = createToolsProvider(backend).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive("@Level ="))
            },
        )

        assertTrue(result.isError)
        assertStringContains(result.structuredContent!!["error"]!!.jsonPrimitive.content, "Seq rejected the filter syntax")
    }

    fun testSeqToStrictFilterReturnsStrictExpression() = runBlocking {
        val backend = RecordingBackend().apply {
            strictExpressionResult = SeqStrictExpressionResult(
                strictExpression = """@Message like '%error%'""",
                matchedAsText = false,
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqToStrictFilter")

        val result = tool.call(
            buildJsonObject {
                put("fuzzy", JsonPrimitive("error"))
                put("workspace", JsonPrimitive("production"))
            },
        )

        assertEquals("error", backend.strictExpressionFuzzy)
        assertEquals("production", backend.strictExpressionWorkspace)
        assertEquals("""@Message like '%error%'""", result.structuredContent!!["strictExpression"]?.jsonPrimitive?.content)
        assertEquals("false", result.structuredContent!!["matchedAsText"]?.jsonPrimitive?.content)
    }

    fun testSeqToStrictFilterReturnsTextSearchFallback() = runBlocking {
        val backend = RecordingBackend().apply {
            strictExpressionResult = SeqStrictExpressionResult(
                strictExpression = "\"timeout\"",
                matchedAsText = true,
                reasonIfMatchedAsText = "No structured syntax was recognized.",
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqToStrictFilter")

        val result = tool.call(
            buildJsonObject {
                put("fuzzy", JsonPrimitive("timeout"))
            },
        )

        assertEquals("\"timeout\"", result.structuredContent!!["strictExpression"]?.jsonPrimitive?.content)
        assertEquals("true", result.structuredContent!!["matchedAsText"]?.jsonPrimitive?.content)
        assertEquals("No structured syntax was recognized.", result.structuredContent!!["reasonIfMatchedAsText"]?.jsonPrimitive?.content)
    }

    fun testToolsProviderUsesWaitDefaults() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqWaitForEvents")

        val result = tool.call(buildJsonObject {})

        assertNull(backend.waitFilter)
        assertEquals(10, backend.waitCount)
        assertNull(backend.waitWorkspace)
        assertEquals(1, result.structuredContent!!["capturedEvents"]?.jsonArray?.size)
    }

    fun testToolsProviderCallsSignalListBackend() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SignalList")

        val result = tool.call(
            buildJsonObject {
                put("workspace", JsonPrimitive("shared"))
            },
        )

        assertEquals("shared", backend.signalWorkspace)
        assertEquals("signal-1", result.structuredContent!!["signals"]?.jsonArray?.first()?.jsonObject?.get("Id")?.jsonPrimitive?.content)
    }

    fun testSeqQuerySqlReturnsStructuredRows() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqQuerySql")

        val result = tool.call(
            buildJsonObject {
                put("query", JsonPrimitive("select count(*) as Errors from stream"))
                put("signalId", JsonPrimitive("signal-1"))
                put("workspace", JsonPrimitive("production"))
                put("timeoutSeconds", JsonPrimitive(30))
            },
        )

        assertEquals("select count(*) as Errors from stream", backend.sqlQueryRequest?.query)
        assertEquals("signal-1", backend.sqlQueryRequest?.signalId)
        assertEquals("production", backend.sqlQueryRequest?.workspace)
        assertEquals(30, backend.sqlQueryRequest?.timeoutSeconds)
        assertEquals(1, result.structuredContent!!["rowCount"]?.jsonPrimitive?.content?.toInt())
    }

    fun testSeqQuerySqlReturnsCsvWhenRequested() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqQuerySql")

        val result = tool.call(
            buildJsonObject {
                put("query", JsonPrimitive("select count(*) as Errors from stream"))
                put("format", JsonPrimitive("csv"))
            },
        )

        assertEquals("select count(*) as Errors from stream", backend.sqlCsvRequest?.query)
        assertEquals("csv", result.structuredContent!!["format"]?.jsonPrimitive?.content)
        assertStringContains(result.structuredContent!!["csv"]!!.jsonPrimitive.content, "Errors")
    }

    fun testSeqQuerySqlReturnsStructuredGuidanceOnHttp400() = runBlocking {
        val backend = RecordingBackend().apply {
            sqlQueryException = SeqApiException(
                message = "Seq API request failed with 400: The query could not be executed.",
                statusCode = 400,
                seqError = "The query could not be executed.",
                seqSuggestion = "Use limit instead of top.",
                seqReasons = listOf("`top` is not valid in Seq SQL.", "Queries should read from `stream`."),
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqQuerySql")

        val result = tool.call(
            buildJsonObject {
                put("query", JsonPrimitive("select top 50 Timestamp, @Level, @Message from stream"))
            },
        )

        assertTrue(result.isError)
        assertStringContains(result.structuredContent!!["error"]!!.jsonPrimitive.content, "Seq rejected the SQL query")
        assertEquals("Use limit instead of top.", result.structuredContent!!["suggestion"]?.jsonPrimitive?.content)
        val reasons = result.structuredContent!!["reasons"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(reasons.any { it.contains("top") })
        val dialect = result.structuredContent!!["dialect"]!!.jsonObject
        assertStringContains(dialect["rowLimit"]!!.jsonPrimitive.content, "limit")
        val examples = result.structuredContent!!["exampleQueries"]!!.jsonArray
        assertEquals(5, examples.size)
        assertStringContains(
            result.structuredContent!!["messageTextSearch"]!!.jsonObject["useSeqSearch"]!!.jsonPrimitive.content,
            "SeqSearch",
        )
    }

    fun testSeqDescribeSqlDialectReturnsExamplesAndGuidance() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqDescribeSqlDialect")

        val result = tool.call(buildJsonObject {})

        assertFalse(result.isError)
        val dialect = result.structuredContent!!["dialect"]!!.jsonObject
        assertStringContains(dialect["source"]!!.jsonPrimitive.content, "`from stream`")
        assertStringContains(dialect["rowLimit"]!!.jsonPrimitive.content, "limit")
        val firstExample = result.structuredContent!!["exampleQueries"]!!.jsonArray.first().jsonObject
        assertEquals("Hello world count", firstExample["title"]?.jsonPrimitive?.content)
        assertEquals("select count(*) as Events from stream", firstExample["query"]?.jsonPrimitive?.content)
        val textSearch = result.structuredContent!!["messageTextSearch"]!!.jsonObject
        assertStringContains(textSearch["inSql"]!!.jsonPrimitive.content, "@Message like")
    }

    fun testSeqSqlQueryListSummarizesSavedQueries() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqSqlQueryList")

        val result = tool.call(
            buildJsonObject {
                put("ownerId", JsonPrimitive("user-1"))
                put("shared", JsonPrimitive(false))
            },
        )

        assertEquals("user-1", backend.sqlQueryListOwnerId)
        assertEquals(false, backend.sqlQueryListShared)
        val first = result.structuredContent!!["queries"]!!.jsonArray.first().jsonObject
        assertEquals("query-1", first["id"]?.jsonPrimitive?.content)
        assertEquals("Errors by service", first["title"]?.jsonPrimitive?.content)
        assertEquals("false", first["shared"]?.jsonPrimitive?.content)
    }

    fun testSeqWorkspaceGetReturnsWorkspaceContentSummary() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqWorkspaceGet")

        val result = tool.call(
            buildJsonObject {
                put("id", JsonPrimitive("workspace-1"))
            },
        )

        assertEquals("workspace-1", backend.workspaceGetId)
        val workspaceEntity = result.structuredContent!!["workspaceEntity"]!!.jsonObject
        assertEquals("Payments", workspaceEntity["title"]?.jsonPrimitive?.content)
        assertEquals(2, workspaceEntity["signalCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1, workspaceEntity["dashboardCount"]?.jsonPrimitive?.content?.toInt())
    }

    fun testSeqDashboardGetReturnsChartSummaries() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqDashboardGet")

        val result = tool.call(
            buildJsonObject {
                put("id", JsonPrimitive("dashboard-1"))
            },
        )

        assertEquals("dashboard-1", backend.dashboardGetId)
        val dashboard = result.structuredContent!!["dashboard"]!!.jsonObject
        assertEquals(1, dashboard["chartCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals("Latency", dashboard["charts"]!!.jsonArray.first().jsonObject["title"]?.jsonPrimitive?.content)
    }

    fun testSeqCreatePermalinkReturnsMetadata() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqCreatePermalink")

        val result = tool.call(
            buildJsonObject {
                put("eventId", JsonPrimitive("event-123"))
                put("includeEvent", JsonPrimitive(true))
            },
        )

        assertEquals("event-123", backend.permalinkEventId)
        assertEquals(true, backend.permalinkIncludeEvent)
        val permalink = result.structuredContent!!["permalink"]!!.jsonObject
        assertEquals("permalink-1", permalink["id"]?.jsonPrimitive?.content)
        assertEquals("event-123", permalink["eventId"]?.jsonPrimitive?.content)
        assertStringContains(permalink["resolvedLinks"]!!.jsonObject["Self"]!!.jsonPrimitive.content, "permalink-1")
        val authContext = result.structuredContent!!["authContext"]!!.jsonObject
        assertEquals("user-1", authContext["resolvedUserId"]?.jsonPrimitive?.content)
        val capabilities = result.structuredContent!!["capabilities"]!!.jsonObject
        assertEquals("true", capabilities["canCreatePermalinks"]?.jsonPrimitive?.content)
    }

    fun testSeqWhoAmIReturnsAuthContext() = runBlocking {
        val backend = RecordingBackend()
        val tool = createToolsProvider(backend).findTool("SeqWhoAmI")

        val result = tool.call(
            buildJsonObject {
                put("workspace", JsonPrimitive("production"))
            },
        )

        assertEquals("production", backend.capabilityWorkspace)
        val authContext = result.structuredContent!!["authContext"]!!.jsonObject
        assertEquals("api_key", authContext["authMode"]?.jsonPrimitive?.content)
        assertEquals("workspace_override", authContext["credentialSource"]?.jsonPrimitive?.content)
        assertEquals("production", authContext["resolvedWorkspace"]?.jsonPrimitive?.content)
        assertEquals("user-1", authContext["resolvedUserId"]?.jsonPrimitive?.content)
    }

    fun testSeqCapabilitiesReturnsCapabilityChecks() = runBlocking {
        val backend = RecordingBackend().apply {
            capabilityReport = capabilityReport.copy(
                canQueryEvents = true,
                canResolveCurrentUser = false,
                canCreatePermalinks = false,
                notes = listOf("No Seq user identity was resolved for the current credential."),
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqCapabilities")

        val result = tool.call(buildJsonObject {})

        val capabilities = result.structuredContent!!["capabilities"]!!.jsonObject
        assertEquals("true", capabilities["canQueryEvents"]?.jsonPrimitive?.content)
        assertEquals("false", capabilities["canResolveCurrentUser"]?.jsonPrimitive?.content)
        assertEquals("false", capabilities["canCreatePermalinks"]?.jsonPrimitive?.content)
        assertEquals(
            "No Seq user identity was resolved for the current credential.",
            capabilities["notes"]!!.jsonArray.first().jsonPrimitive.content,
        )
    }

    fun testSeqCreatePermalinkReturnsNormalizedFallbackWhenUserIdentityMissing() = runBlocking {
        val backend = RecordingBackend().apply {
            capabilityReport = capabilityReport.copy(
                authContext = capabilityReport.authContext.copy(
                    resolvedUserId = null,
                    resolvedUsername = null,
                    resolvedUserDisplayName = null,
                ),
                canResolveCurrentUser = false,
                canCreatePermalinks = false,
                notes = listOf("Permalink creation requires a Seq user identity; the current credential did not resolve one."),
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqCreatePermalink")

        val result = tool.call(
            buildJsonObject {
                put("eventId", JsonPrimitive("event-123"))
            },
        )

        assertTrue(result.isError)
        assertNull(backend.permalinkEventId)
        assertEquals("event-123", backend.eventLookupId)
        assertStringContains(
            result.structuredContent!!["error"]!!.jsonPrimitive.content,
            "current Seq credential has no user identity",
        )
        val fallback = result.structuredContent!!["fallback"]!!.jsonObject
        assertEquals("event-123", fallback["eventId"]?.jsonPrimitive?.content)
        assertEquals("2026-04-18T10:15:30Z", fallback["timestamp"]?.jsonPrimitive?.content)
        assertEquals("""@Id = "event-123"""", fallback["suggestedFilter"]?.jsonPrimitive?.content)
    }

    fun testSeqCreatePermalinkNormalizesRawServerUserIdFailure() = runBlocking {
        val backend = RecordingBackend().apply {
            permalinkException = SeqApiException(
                message = "Seq API request failed with 400: A valid user id is required",
                statusCode = 400,
                seqError = "A valid user id is required",
            )
        }
        val tool = createToolsProvider(backend).findTool("SeqCreatePermalink")

        val result = tool.call(
            buildJsonObject {
                put("eventId", JsonPrimitive("event-123"))
            },
        )

        assertTrue(result.isError)
        assertEquals("event-123", backend.permalinkEventId)
        assertStringContains(
            result.structuredContent!!["error"]!!.jsonPrimitive.content,
            "current Seq credential has no user identity",
        )
    }

    fun testToolsProviderRejectsCallsWhenProjectIsDisabled() = runBlocking {
        val backend = RecordingBackend()
        val tool = SeqMcpToolsProvider(backend, enabledProjectResolver = { null }).findTool("SeqSearch")

        val result = tool.call(
            buildJsonObject {
                put("filter", JsonPrimitive(""))
            },
        )

        assertTrue(result.isError)
        assertStringContains(
            result.structuredContent!!["error"]!!.jsonPrimitive.content,
            "disabled for the active project",
        )
    }

    fun testSupportedToolsContainExpectedNames() {
        val tools = service<SeqMcpToolService>()

        assertEquals(
            listOf(
                "SeqSearch",
                "SeqQuerySql",
                "SeqDescribeSqlDialect",
                "SeqToStrictFilter",
                "SeqWaitForEvents",
                "SignalList",
                "SeqWhoAmI",
                "SeqCapabilities",
                "SeqSqlQueryList",
                "SeqSqlQueryGet",
                "SeqWorkspaceList",
                "SeqWorkspaceGet",
                "SeqDashboardList",
                "SeqDashboardGet",
                "SeqCreatePermalink",
            ),
            tools.supportedTools().map { it.name },
        )
    }

    fun testToWebSocketUriRewritesHttpSchemes() {
        assertEquals(
            URI("ws://localhost:5341/api/events/stream?render=true"),
            toWebSocketUri(URI("http://localhost:5341/api/events/stream?render=true")),
        )
        assertEquals(
            URI("wss://seq.example.test/api/events/scan?count=1"),
            toWebSocketUri(URI("https://seq.example.test/api/events/scan?count=1")),
        )
    }

    fun testToWebSocketUriPreservesWebSocketSchemes() {
        assertEquals(
            URI("ws://localhost:5341/api/events/stream?render=true"),
            toWebSocketUri(URI("ws://localhost:5341/api/events/stream?render=true")),
        )
        assertEquals(
            URI("wss://seq.example.test/api/events/scan?count=1"),
            toWebSocketUri(URI("wss://seq.example.test/api/events/scan?count=1")),
        )
    }

    private fun SeqMcpToolsProvider.findTool(name: String): McpTool {
        return getTools().first { it.descriptor.name == name }
    }

    private fun createToolsProvider(backend: SeqMcpBackend): SeqMcpToolsProvider {
        return SeqMcpToolsProvider(backend, enabledProjectResolver = { project })
    }

    private fun resetSettings(): SeqMcpSettingsService {
        project.service<SeqMcpProjectSettingsService>().enabled = true
        return service<SeqMcpSettingsService>().also {
            it.seqServerUrl = ""
            it.setApiKey(null)
            it.setWorkspaceApiKeyEntries(emptyList())
        }
    }

    private fun assertStringContains(actual: String, expectedSubstring: String) {
        assertTrue("Expected <$actual> to contain <$expectedSubstring>", actual.contains(expectedSubstring))
    }

    private class RecordingBackend : SeqMcpBackend {
        var searchRequest: SeqSearchRequest? = null
        var searchException: SeqApiException? = null

        var waitFilter: String? = null
        var waitCount: Int? = null
        var waitWorkspace: String? = null

        var signalWorkspace: String? = null
        var availableSignals: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("Id", JsonPrimitive("signal-1"))
                },
            )
        }
        var strictExpressionFuzzy: String? = null
        var strictExpressionWorkspace: String? = null
        var strictExpressionResult = SeqStrictExpressionResult(
            strictExpression = "",
            matchedAsText = false,
        )
        var sqlQueryRequest: SeqSqlQueryRequest? = null
        var sqlCsvRequest: SeqSqlQueryRequest? = null
        var sqlQueryException: SeqApiException? = null
        var sqlCsvException: SeqApiException? = null
        var sqlQueryListOwnerId: String? = null
        var sqlQueryListShared: Boolean? = null
        var sqlQueryGetId: String? = null
        var workspaceListOwnerId: String? = null
        var workspaceListShared: Boolean? = null
        var workspaceGetId: String? = null
        var dashboardListOwnerId: String? = null
        var dashboardListShared: Boolean? = null
        var dashboardGetId: String? = null
        var capabilityWorkspace: String? = null
        var eventLookupId: String? = null
        var eventLookupWorkspace: String? = null
        var eventLookupRender: Boolean? = null
        var permalinkEventId: String? = null
        var permalinkIncludeEvent: Boolean? = null
        var permalinkRenderEvent: Boolean? = null
        var permalinkException: SeqApiException? = null
        var sqlJsonResult = buildJsonObject {
            put("Columns", buildJsonArray {
                add(JsonPrimitive("Errors"))
            })
            put("Rows", buildJsonArray {
                add(buildJsonArray {
                    add(JsonPrimitive(42))
                })
            })
            put(
                "Statistics",
                buildJsonObject {
                    put("ElapsedMilliseconds", JsonPrimitive(12.5))
                },
            )
        }
        var sqlCsvResult = "Errors\n42\n"
        var sqlQueryListResult: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("Id", JsonPrimitive("query-1"))
                    put("Title", JsonPrimitive("Errors by service"))
                    put("Description", JsonPrimitive("Counts errors"))
                    put("Sql", JsonPrimitive("select count(*) from stream"))
                    put("OwnerId", JsonPrimitive("user-1"))
                    put("IsProtected", JsonPrimitive(true))
                },
            )
        }
        var sqlQueryGetResult: kotlinx.serialization.json.JsonObject = sqlQueryListResult.first().jsonObject
        var workspaceListResult: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("Id", JsonPrimitive("workspace-1"))
                    put("Title", JsonPrimitive("Payments"))
                    put("Description", JsonPrimitive("Payments incidents"))
                    put("IsProtected", JsonPrimitive(false))
                    put(
                        "Content",
                        buildJsonObject {
                            put("SignalIds", buildJsonArray { add(JsonPrimitive("signal-1")); add(JsonPrimitive("signal-2")) })
                            put("QueryIds", buildJsonArray { add(JsonPrimitive("query-1")) })
                            put("DashboardIds", buildJsonArray { add(JsonPrimitive("dashboard-1")) })
                        },
                    )
                },
            )
        }
        var workspaceGetResult: kotlinx.serialization.json.JsonObject = workspaceListResult.first().jsonObject
        var dashboardListResult: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("Id", JsonPrimitive("dashboard-1"))
                    put("Title", JsonPrimitive("Payments overview"))
                    put("IsProtected", JsonPrimitive(false))
                    put(
                        "Charts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("Id", JsonPrimitive("chart-1"))
                                    put("Title", JsonPrimitive("Latency"))
                                    put("Description", JsonPrimitive("P95 latency"))
                                    put("Queries", buildJsonArray { add(buildJsonObject {}) })
                                },
                            )
                        },
                    )
                },
            )
        }
        var dashboardGetResult: kotlinx.serialization.json.JsonObject = dashboardListResult.first().jsonObject
        var capabilityReport = SeqCapabilityReport(
            authContext = SeqAuthContext(
                authMode = "api_key",
                credentialSource = "workspace_override",
                resolvedUserId = "user-1",
                resolvedUsername = "alice",
                resolvedUserDisplayName = "Alice",
                resolvedWorkspace = null,
                serverVersion = "2026.1.0",
                serverProduct = "Seq",
                serverInstanceName = "local-seq",
            ),
            canQueryEvents = true,
            canResolveCurrentUser = true,
            canCreatePermalinks = true,
            eventsEndpoint = "http://localhost:5341/api/events/scan?count=1&render=true",
            currentUserEndpoint = "http://localhost:5341/api/users/current",
            permalinkEndpoint = "http://localhost:5341/api/permalinks",
            notes = emptyList(),
        )
        var eventLookupResult: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("Id", JsonPrimitive("event-123"))
            put("Timestamp", JsonPrimitive("2026-04-18T10:15:30Z"))
            put("RenderedMessage", JsonPrimitive("Timeout while calling payments"))
            put(
                "ResolvedLinks",
                buildJsonObject {
                    put("Self", JsonPrimitive("http://localhost:5341/api/events/event-123"))
                },
            )
        }
        var permalinkResult: kotlinx.serialization.json.JsonObject = buildJsonObject {
            put("Id", JsonPrimitive("permalink-1"))
            put("EventId", JsonPrimitive("event-123"))
            put(
                "ResolvedLinks",
                buildJsonObject {
                    put("Self", JsonPrimitive("http://localhost:5341/api/permalinks/permalink-1"))
                },
            )
            put(
                "Event",
                buildJsonObject {
                    put("Id", JsonPrimitive("event-123"))
                },
            )
        }

        override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
            searchException?.let { throw it }
            searchRequest = request
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("Id", JsonPrimitive("event-1"))
                    },
                )
            }
        }

        override suspend fun waitForEvents(filter: String?, count: Int, workspace: String?): JsonArray {
            waitFilter = filter
            waitCount = count
            waitWorkspace = workspace
            return buildJsonArray {
                add(
                    buildJsonObject {
                        put("Id", JsonPrimitive("live-1"))
                    },
                )
            }
        }

        override suspend fun listSignals(workspace: String?): JsonArray {
            signalWorkspace = workspace
            return availableSignals
        }

        override suspend fun toStrictFilterExpression(fuzzy: String, workspace: String?): SeqStrictExpressionResult {
            strictExpressionFuzzy = fuzzy
            strictExpressionWorkspace = workspace
            return strictExpressionResult
        }

        override suspend fun querySql(request: SeqSqlQueryRequest): kotlinx.serialization.json.JsonObject {
            sqlQueryException?.let { throw it }
            sqlQueryRequest = request
            return sqlJsonResult
        }

        override suspend fun querySqlCsv(request: SeqSqlQueryRequest): String {
            sqlCsvException?.let { throw it }
            sqlCsvRequest = request
            return sqlCsvResult
        }

        override suspend fun listSqlQueries(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
            sqlQueryListOwnerId = ownerId
            sqlQueryListShared = shared
            return sqlQueryListResult
        }

        override suspend fun getSqlQuery(id: String, workspace: String?): kotlinx.serialization.json.JsonObject {
            sqlQueryGetId = id
            return sqlQueryGetResult
        }

        override suspend fun listWorkspaces(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
            workspaceListOwnerId = ownerId
            workspaceListShared = shared
            return workspaceListResult
        }

        override suspend fun getWorkspace(id: String, workspace: String?): kotlinx.serialization.json.JsonObject {
            workspaceGetId = id
            return workspaceGetResult
        }

        override suspend fun listDashboards(ownerId: String?, shared: Boolean, workspace: String?): JsonArray {
            dashboardListOwnerId = ownerId
            dashboardListShared = shared
            return dashboardListResult
        }

        override suspend fun getDashboard(id: String, workspace: String?): kotlinx.serialization.json.JsonObject {
            dashboardGetId = id
            return dashboardGetResult
        }

        override suspend fun getCapabilities(workspace: String?): SeqCapabilityReport {
            capabilityWorkspace = workspace
            return capabilityReport.copy(
                authContext = capabilityReport.authContext.copy(
                    resolvedWorkspace = workspace,
                ),
            )
        }

        override suspend fun getEvent(id: String, workspace: String?, render: Boolean): kotlinx.serialization.json.JsonObject {
            eventLookupId = id
            eventLookupWorkspace = workspace
            eventLookupRender = render
            return eventLookupResult
        }

        override suspend fun createPermalink(
            eventId: String,
            workspace: String?,
            includeEvent: Boolean,
            renderEvent: Boolean,
        ): kotlinx.serialization.json.JsonObject {
            permalinkEventId = eventId
            permalinkIncludeEvent = includeEvent
            permalinkRenderEvent = renderEvent
            permalinkException?.let { throw it }
            return permalinkResult
        }
    }
}
