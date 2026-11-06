// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vitaliiboiko.seqmcp.services.SeqApiClient
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import kotlinx.serialization.json.JsonArray
import com.vitaliiboiko.seqmcp.services.SeqMcpLogService
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.services.WorkspaceApiKeyEntry
import com.vitaliiboiko.seqmcp.settings.SeqMcpConfigurable
import com.vitaliiboiko.seqmcp.toolWindow.SeqMcpToolWindowFactory
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField

/** Exercises real Swing controls on the platform test EDT, without contacting a Seq server. */
class SeqMcpUiTest : BasePlatformTestCase() {
    private val settings get() = service<SeqMcpSettingsService>()
    private val projectSettings get() = project.service<SeqMcpProjectSettingsService>()
    private val log get() = project.service<SeqMcpLogService>()
    private val views = mutableListOf<Disposable>()
    private val configurables = mutableListOf<SeqMcpConfigurable>()
    private val notifications = mutableListOf<Notification>()

    override fun setUp() {
        super.setUp()
        settings.seqServerUrl = ""
        settings.setApiKey(null)
        settings.setWorkspaceApiKeyEntries(emptyList())
        projectSettings.enabled = true
        log.clear()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            // Drain deferred editor updates before disposing their viewers.
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            views.forEach(Disposer::dispose)
            configurables.forEach(SeqMcpConfigurable::disposeUIResources)
            notifications.forEach(Notification::expire)
            settings.seqServerUrl = ""
            settings.setApiKey(null)
            settings.setWorkspaceApiKeyEntries(emptyList())
            projectSettings.enabled = true
        } finally {
            super.tearDown()
        }
    }

    fun testWorkspaceAddAndRemoveButtonsRestoreEmptyGrid() {
        val (configurable, panel) = settingsPanel()
        assertTrue(panel.labels().contains(message("settings.workspaceKeys.empty")))
        panel.button("settings.workspaceKeys.add").doClick()
        assertEquals(1, workspaceRows(panel).size)
        assertFalse(panel.labels().contains(message("settings.workspaceKeys.empty")))
        assertTrue("An added row must enable Apply so validation can explain missing fields", configurable.isModified())
        panel.button("settings.workspaceKeys.remove").doClick()
        assertTrue(workspaceRows(panel).isEmpty())
        assertTrue(panel.labels().contains(message("settings.workspaceKeys.empty")))
        assertFalse(configurable.isModified())
    }

    fun testWorkspaceGridKeepsOtherRowsWhenMiddleRowIsRemoved() {
        val (configurable, panel) = settingsPanel()
        repeat(3) { index ->
            panel.button("settings.workspaceKeys.add").doClick()
            fillRow(workspaceRows(panel).last(), "workspace-$index", "synthetic-key-$index")
        }
        val rows = workspaceRows(panel)
        rows.forEach { row ->
            row.setSize(900, 70)
            row.doLayout()
            assertEquals(3, row.componentCount)
            assertEquals(row.components[0].width, row.components[1].width)
            assertEquals(row.components[1].width, row.components[2].width)
            assertTrue(row.labels().contains(message("settings.workspaceKeys.workspace")))
            assertTrue(row.labels().contains(message("settings.workspaceKeys.apiKey")))
            assertTrue(row.descendants().filterIsInstance<JPasswordField>().single().echoChar != '\u0000')
        }
        rows[1].button("settings.workspaceKeys.remove").doClick()
        assertTrue(configurable.isModified())
        configurable.apply()
        assertEquals(listOf("workspace-0", "workspace-2"), settings.workspaceApiKeyEntries().map { it.workspaceId })
        assertFalse(configurable.isModified())
        configurable.reset()
        assertEquals(listOf("workspace-0", "workspace-2"), workspaceRows(panel).map { workspaceField(it).text })
    }

    fun testWorkspaceValidationRejectsInvalidRowsBeforeSavingAnySettings() {
        settings.seqServerUrl = "https://saved.example.invalid"
        settings.setWorkspaceApiKeyEntries(listOf(WorkspaceApiKeyEntry("saved", "synthetic-saved")))
        val (configurable, panel) = settingsPanel()
        panel.descendants().filterIsInstance<JCheckBox>().single().doClick()
        panel.button("settings.workspaceKeys.add").doClick()
        val row = workspaceRows(panel).last()
        for ((id, key, error) in listOf(
            Triple("", "synthetic-orphan", "Enter a workspace ID."),
            Triple("other", "", "Enter an API key."),
            Triple(" saved ", "synthetic-duplicate", "Workspace IDs must be unique."),
        )) {
            fillRow(row, id, key)
            assertTrue(configurable.isModified())
            assertTrue("Validation must appear while editing", row.labels().contains(error))
            try {
                configurable.apply()
                fail("Invalid workspace rows must block Apply")
            } catch (_: ConfigurationException) {
                assertTrue("Expected inline error: $error", row.labels().contains(error))
            }
            assertTrue(projectSettings.enabled)
            assertEquals("https://saved.example.invalid", settings.seqServerUrl)
            assertEquals(listOf(WorkspaceApiKeyEntry("saved", "synthetic-saved")), settings.workspaceApiKeyEntries())
        }
        fillRow(row, " other ", " synthetic-other ")
        assertFalse(row.labels().any { it.contains("must be unique") || it.startsWith("Enter ") })
        configurable.apply()
        assertEquals(listOf("saved", "other"), settings.workspaceApiKeyEntries().map { it.workspaceId })
        assertFalse(configurable.isModified())
    }

    fun testSettingsResetDiscardsEditsAndApplyPersistsProjectCheckbox() {
        settings.seqServerUrl = "https://seq.example.invalid"
        settings.setWorkspaceApiKeyEntries(listOf(WorkspaceApiKeyEntry("saved", "synthetic-saved")))
        val (configurable, panel) = settingsPanel()
        val enabled = panel.descendants().filterIsInstance<JCheckBox>().single()
        enabled.doClick()
        panel.button("settings.workspaceKeys.remove").doClick()
        assertTrue(configurable.isModified())
        configurable.reset()
        assertTrue(enabled.isSelected)
        assertEquals("saved", workspaceField(workspaceRows(panel).single()).text)
        assertFalse(configurable.isModified())
        enabled.doClick()
        configurable.apply()
        assertFalse(projectSettings.enabled)
        assertFalse(SeqMcpToolWindowFactory().shouldBeAvailable(project))
        enabled.doClick()
        configurable.apply()
        assertTrue(SeqMcpToolWindowFactory().shouldBeAvailable(project))
    }

    fun testToolWindowButtonsHaveEqualGridBoundsAndTooltips() {
        val panel = toolWindowPanel()
        val actions = listOf("toolWindow.testConnection", "toolWindow.fetchLast10Events", "toolWindow.refresh", "toolWindow.openSettings").map { panel.button(it) }
        val row = actions.first().parent
        assertEquals(actions, row.components.toList())
        row.setSize(900, 60)
        row.doLayout()
        actions.forEach { button ->
            assertEquals(actions.first().size, button.size)
            assertTrue(button.width > 0)
            assertEquals(actions.first().preferredSize, button.preferredSize)
        }
        assertEquals(message("toolWindow.fetchLast10EventsDescription"), actions[1].toolTipText)
        assertEquals(message("toolWindow.clearSeqEventsDescription"), panel.button("toolWindow.clearSeqEvents").toolTipText)
        assertEquals(message("toolWindow.clearLogDescription"), panel.button("toolWindow.clearLog").toolTipText)
    }

    fun testRefreshButtonUpdatesStatusAndAppendsVisibleLog() {
        val panel = toolWindowPanel()
        settings.seqServerUrl = "https://seq.example.invalid"
        panel.button("toolWindow.refresh").doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(panel.labels().contains(message("toolWindow.url", settings.seqServerUrl)))
        assertEquals(1, log.recordCount())
        assertEquals(log.snapshot().text, logEditorText())
        projectSettings.enabled = false
        panel.button("toolWindow.refresh").doClick()
        assertTrue(panel.labels().contains(message("toolWindow.status", message("status.disabled"))))
    }

    fun testClearLogButtonClearsVisibleLogAndHandlesEmptyLog() {
        val panel = toolWindowPanel()
        log.append("Synthetic UI test entry")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(logEditorText().contains("Synthetic UI test entry"))
        panel.button("toolWindow.clearLog").doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(0, log.recordCount())
        assertEquals("", logEditorText())
        assertEquals(message("notification.logCleared", 1), notifications.last().content)
        panel.button("toolWindow.clearLog").doClick()
        assertEquals(message("notification.logAlreadyEmpty"), notifications.last().content)
    }

    fun testFetchAndClearEventsButtonsRejectDisabledProject() {
        settings.seqServerUrl = "https://seq.example.invalid"
        projectSettings.enabled = false
        val panel = toolWindowPanel()
        panel.button("toolWindow.fetchLast10Events").doClick()
        assertEquals(message("notification.seqFetchDisabled"), notifications.last().content)
        panel.button("toolWindow.clearSeqEvents").doClick()
        assertEquals(message("notification.seqClearDisabled"), notifications.last().content)
        assertEquals(0, log.recordCount())
    }

    fun testFetchAndClearEventsButtonsRejectMissingServer() {
        val panel = toolWindowPanel()
        panel.button("toolWindow.fetchLast10Events").doClick()
        assertEquals(message("notification.seqFetchNotConfigured"), notifications.last().content)
        panel.button("toolWindow.clearSeqEvents").doClick()
        assertEquals(message("notification.seqClearNotConfigured"), notifications.last().content)
        assertEquals(0, log.recordCount())
    }

    fun testClearEventsButtonRequiresConfirmationAndCancelLeavesLogUntouched() {
        settings.seqServerUrl = "https://seq.example.invalid"
        val panel = toolWindowPanel()
        log.append("Synthetic entry retained after cancel")
        var confirmation: String? = null
        TestDialogManager.setTestDialog(TestDialog { text ->
            confirmation = text
            Messages.NO
        }, testRootDisposable)
        panel.button("toolWindow.clearSeqEvents").doClick()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(confirmation!!.contains(settings.seqServerUrl))
        assertTrue(confirmation.contains("permanent"))
        assertEquals(1, log.recordCount())
        assertEquals(log.snapshot().text, logEditorText())
        assertTrue(notifications.isEmpty())
    }

    fun testDeletionRequiresNewConfirmationIfSettingsChangeInDialog() {
        settings.seqServerUrl = "https://old.example.invalid"
        val panel = toolWindowPanel()
        TestDialogManager.setTestDialog(TestDialog {
            settings.seqServerUrl = "https://new.example.invalid"
            Messages.YES
        }, testRootDisposable)
        panel.button("toolWindow.clearSeqEvents").doClick()
        assertEquals("Connection settings changed. Review the server and confirm deletion again.", notifications.last().content)
        assertEquals(0, log.recordCount())
    }

    fun testDeletionIsSeparateFromEverydayActionsAndClearlyNamed() {
        val panel = toolWindowPanel()
        val delete = panel.button("toolWindow.clearSeqEvents")
        assertEquals("Delete all server events…", delete.text)
        assertNotSame(panel.button("toolWindow.fetchLast10Events").parent, delete.parent)
        assertTrue(delete.parent.parent.let { it as Container }.labels().contains("Advanced — server data"))
    }

    fun testUnconfiguredViewHasFinishedTitleAndOpensSettings() {
        var opened = false
        val panel = toolWindowPanel(openSettings = { opened = true })
        assertTrue(panel.labels().contains("Seq MCP"))
        assertFalse(panel.labels().any { it.contains("scaffold", ignoreCase = true) })
        val configure = panel.button("toolWindow.openSettings")
        assertEquals("Configure Seq…", configure.text)
        configure.doClick()
        assertTrue(opened)
    }

    fun testConnectionButtonReportsActualSuccessAndInvalidatesAfterCredentialChange() {
        settings.seqServerUrl = "https://seq.example.invalid"
        var recordedRequest: SeqSearchRequest? = null
        val client = object : SeqApiClient({ settings.seqServerUrl }, { null }) {
            override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
                recordedRequest = request
                assertFalse(javax.swing.SwingUtilities.isEventDispatchThread())
                return JsonArray(emptyList())
            }
        }
        val panel = toolWindowPanel(client)
        assertTrue(panel.labels().contains("Configuration: Configured without API key"))
        assertTrue(panel.labels().contains("Connection test: Not tested"))
        panel.button("toolWindow.testConnection").doClick()
        PlatformTestUtil.waitWithEventsDispatching("Connection result", { panel.labels().contains("Connection test: Successful") }, 10)
        assertEquals(1, recordedRequest!!.count)
        assertTrue(panel.button("toolWindow.testConnection").isEnabled)
        settings.setApiKey("synthetic-rotated")
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(panel.labels().contains("Connection test: Not tested"))
    }

    fun testConnectionFailureIsVisibleAndCanBeRetried() {
        settings.seqServerUrl = "https://seq.example.invalid"
        var fail = true
        val client = object : SeqApiClient({ settings.seqServerUrl }, { null }) {
            override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
                if (fail) throw SeqApiException("Synthetic connection failure")
                return JsonArray(emptyList())
            }
        }
        val panel = toolWindowPanel(client)
        panel.button("toolWindow.testConnection").doClick()
        PlatformTestUtil.waitWithEventsDispatching("Connection failure", { panel.labels().contains("Connection test: Failed — Synthetic connection failure") }, 10)
        fail = false
        panel.button("toolWindow.testConnection").doClick()
        PlatformTestUtil.waitWithEventsDispatching("Connection retry", { panel.labels().contains("Connection test: Successful") }, 10)
    }

    fun testConnectionRejectsMissingConfigurationAndDisabledProjectWithoutNetwork() {
        val client = object : SeqApiClient({ settings.seqServerUrl }, { null }) {
            override suspend fun searchEvents(request: SeqSearchRequest): JsonArray = error("Unexpected network request")
        }
        val panel = toolWindowPanel(client)
        panel.button("toolWindow.testConnection").doClick()
        assertTrue(panel.labels().contains("Connection test: Configure the Seq server URL first."))
        settings.seqServerUrl = "https://seq.example.invalid"
        projectSettings.enabled = false
        panel.button("toolWindow.testConnection").doClick()
        assertTrue(panel.labels().contains("Connection test: Enable Seq MCP for this project first."))
    }

    fun testConnectionIgnoresResultIfSettingsChangeWhileTesting() {
        settings.seqServerUrl = "https://old.example.invalid"
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var wasDisabled = false
        lateinit var panel: JComponent
        val client = object : SeqApiClient({ settings.seqServerUrl }, { null }) {
            override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    wasDisabled = !panel.button("toolWindow.testConnection").isEnabled
                    settings.seqServerUrl = "https://new.example.invalid"
                    completed.complete(Unit)
                }
                completed.await()
                return JsonArray(emptyList())
            }
        }
        panel = toolWindowPanel(client)
        panel.button("toolWindow.testConnection").doClick()
        PlatformTestUtil.waitWithEventsDispatching("Connection result", { completed.isCompleted && panel.button("toolWindow.testConnection").isEnabled }, 10)
        assertTrue(wasDisabled)
        assertTrue(panel.labels().contains("Connection test: Not tested"))
        assertTrue(panel.labels().contains(message("toolWindow.url", settings.seqServerUrl)))
    }

    fun testCancelledConnectionCanBeRetried() {
        settings.seqServerUrl = "https://seq.example.invalid"
        val client = object : SeqApiClient({ settings.seqServerUrl }, { null }) {
            override suspend fun searchEvents(request: SeqSearchRequest): JsonArray {
                throw com.intellij.openapi.progress.ProcessCanceledException()
            }
        }
        val panel = toolWindowPanel(client)
        panel.button("toolWindow.testConnection").doClick()
        PlatformTestUtil.waitWithEventsDispatching("Connection cancellation", { panel.labels().contains("Connection test: Cancelled") }, 10)
        assertTrue(panel.button("toolWindow.testConnection").isEnabled)
    }

    private fun settingsPanel(): Pair<SeqMcpConfigurable, JComponent> {
        val configurable = SeqMcpConfigurable(project).also(configurables::add)
        val panel = configurable.createComponent()
        configurable.reset()
        return configurable to panel
    }

    private fun toolWindowPanel(client: SeqApiClient? = null, openSettings: (() -> Unit)? = null): JComponent {
        // Keep production visibility unchanged: only construction crosses the private view boundary.
        // Assertions and interactions use the real component tree and public services.
        if (client != null) Disposer.register(testRootDisposable, Disposable { client.close() })
        val type = Class.forName("com.vitaliiboiko.seqmcp.toolWindow.SeqMcpToolWindow")
        val view = if (client == null && openSettings == null) {
            type.getDeclaredConstructor(Project::class.java).apply { isAccessible = true }.newInstance(project)
        } else {
            type.getDeclaredConstructor(Project::class.java, SeqApiClient::class.java, Function0::class.java)
                .apply { isAccessible = true }.newInstance(project, client ?: service<com.vitaliiboiko.seqmcp.services.SeqApiService>(), openSettings ?: {})
        }
        views += view as Disposable
        return type.getDeclaredMethod("content").apply { isAccessible = true }.invoke(view) as JComponent
    }

    private fun logEditorText(): String = EditorFactory.getInstance().allEditors.single { it.project == project && it.isViewer }.document.text

    private fun workspaceRows(panel: Container): List<Container> = panel.descendants().filterIsInstance<JButton>()
        .filter { it.text == message("settings.workspaceKeys.remove") }.map { it.parent.parent }.toList()

    private fun workspaceField(row: Container): JTextField = row.descendants().filterIsInstance<JTextField>()
        .single { it !is JPasswordField }

    private fun fillRow(row: Container, id: String, key: String) {
        workspaceField(row).text = id
        row.descendants().filterIsInstance<JPasswordField>().single().text = key
    }

    private fun Container.button(key: String): JButton = descendants().filterIsInstance<JButton>().single { it.text == message(key) }
    private fun Container.labels(): List<String> = descendants().filterIsInstance<JLabel>().map { it.text }.toList()
    private fun Container.descendants(): Sequence<Component> = components.asSequence().flatMap {
        sequenceOf(it) + if (it is Container) it.descendants() else emptySequence()
    }
    private fun message(key: String, vararg params: Any): String = SeqMcpBundle.message(key, *params)
}
