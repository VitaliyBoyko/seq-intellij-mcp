package com.vitaliiboiko.seqmcp

import com.intellij.openapi.components.service
import com.intellij.mcpserver.McpTool
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vitaliiboiko.seqmcp.mcp.SeqMcpToolsProvider
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqMcpBackend
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectService
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
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

        assertEquals(listOf("SeqSearch", "SeqToStrictFilter", "SeqWaitForEvents", "SignalList"), tools.supportedTools().map { it.name })
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
    }
}
