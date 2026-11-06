// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        assertFalse("An incomplete row must not change persisted settings", configurable.isModified())
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

    fun testWorkspaceApplyNormalizesDuplicatesAndIncompleteRows() {
        val (configurable, panel) = settingsPanel()
        listOf(" workspace-a " to " synthetic-first ", "workspace-a" to "synthetic-duplicate", "" to "synthetic-orphan", "workspace-b" to "").forEach { (id, key) ->
            panel.button("settings.workspaceKeys.add").doClick()
            fillRow(workspaceRows(panel).last(), id, key)
        }
        configurable.apply()
        assertEquals(listOf(WorkspaceApiKeyEntry("workspace-a", "synthetic-first")), settings.workspaceApiKeyEntries())
        configurable.reset()
        assertEquals(1, workspaceRows(panel).size)
        panel.button("settings.workspaceKeys.remove").doClick()
        configurable.apply()
        assertTrue(settings.workspaceApiKeyEntries().isEmpty())
        assertNull(settings.getApiKey("workspace-a"))
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
        val actions = listOf("toolWindow.fetchLast10Events", "toolWindow.clearSeqEvents", "toolWindow.refresh").map { panel.button(it) }
        val row = actions.first().parent
        assertEquals(actions, row.components.toList())
        row.setSize(900, 60)
        row.doLayout()
        actions.forEach { button ->
            assertEquals(actions.first().size, button.size)
            assertTrue(button.width > 0)
            assertEquals(actions.first().preferredSize, button.preferredSize)
        }
        assertEquals(message("toolWindow.fetchLast10EventsDescription"), actions[0].toolTipText)
        assertEquals(message("toolWindow.clearSeqEventsDescription"), actions[1].toolTipText)
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
        assertEquals(message("dialog.clearSeqEvents.message"), confirmation)
        assertEquals(1, log.recordCount())
        assertEquals(log.snapshot().text, logEditorText())
        assertTrue(notifications.isEmpty())
    }

    private fun settingsPanel(): Pair<SeqMcpConfigurable, JComponent> {
        val configurable = SeqMcpConfigurable(project).also(configurables::add)
        val panel = configurable.createComponent()
        configurable.reset()
        return configurable to panel
    }

    private fun toolWindowPanel(): JComponent {
        // Keep production visibility unchanged: only construction crosses the private view boundary.
        // Assertions and interactions use the real component tree and public services.
        val type = Class.forName("com.vitaliiboiko.seqmcp.toolWindow.SeqMcpToolWindow")
        val view = type.getDeclaredConstructor(Project::class.java).apply { isAccessible = true }.newInstance(project)
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
