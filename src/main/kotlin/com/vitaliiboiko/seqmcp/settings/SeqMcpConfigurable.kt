// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp.settings

import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import javax.swing.event.DocumentEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.vitaliiboiko.seqmcp.SeqMcpBundle
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsSnapshot
import com.vitaliiboiko.seqmcp.services.WorkspaceApiKeyEntry
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.JPanel

class SeqMcpConfigurable(private val project: Project) : SearchableConfigurable, Configurable.NoScroll {

    private var settingsComponent: SeqMcpSettingsComponent? = null

    override fun getId(): String = "com.vitaliiboiko.seqmcp.settings"

    override fun getDisplayName(): String = SeqMcpBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        return SeqMcpSettingsComponent().also {
            settingsComponent = it
        }.panel
    }

    override fun isModified(): Boolean {
        val component = settingsComponent ?: return false
        return component.isModified()
    }

    override fun apply() {
        settingsComponent?.apply(
            project = project,
            projectSettings = SeqMcpProjectSettingsService.getInstance(project),
            settings = SeqMcpSettingsService.getInstance(),
        )
    }

    override fun reset() {
        settingsComponent?.reset(
            projectSettings = SeqMcpProjectSettingsService.getInstance(project),
            settings = SeqMcpSettingsService.getInstance(),
        )
    }

    override fun disposeUIResources() {
        settingsComponent = null
    }
}

private class SeqMcpSettingsComponent {
    private val enabledForProjectCheckBox = JBCheckBox(SeqMcpBundle.message("settings.enableProject.label"))
    private val seqServerUrlField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val workspaceRows = mutableListOf<WorkspaceApiKeyRow>()
    private var loadedEnabledForProject = true
    private var loadedSnapshot = SeqMcpSettingsSnapshot("", null, emptyList())
    private val workspaceRowsPanel = JBPanel<JBPanel<*>>().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
    }
    private val workspaceContainer = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(workspaceRowsPanel, BorderLayout.NORTH)
    }

    val panel: DialogPanel = panel {
        group(SeqMcpBundle.message("settings.group.project")) {
            row {
                cell(enabledForProjectCheckBox)
                    .comment(SeqMcpBundle.message("settings.enableProject.comment"))
            }
        }
        group(SeqMcpBundle.message("settings.group.connection")) {
            row(SeqMcpBundle.message("settings.url.label")) {
                cell(seqServerUrlField)
                    .align(AlignX.FILL)
                    .comment(SeqMcpBundle.message("settings.url.comment"))
            }
            row(SeqMcpBundle.message("settings.apiKey.label")) {
                cell(apiKeyField)
                    .align(AlignX.FILL)
                    .comment(SeqMcpBundle.message("settings.apiKey.comment"))
            }
            row(SeqMcpBundle.message("settings.workspaceKeys.label")) {
                cell(workspaceContainer)
                    .align(AlignX.FILL)
                    .comment(SeqMcpBundle.message("settings.workspaceKeys.comment"))
            }
            row {
                button(SeqMcpBundle.message("settings.workspaceKeys.add")) {
                    addWorkspaceRow()
                }
            }
        }
    }

    fun isModified(): Boolean {
        val currentRows = workspaceRows.map { row ->
            WorkspaceApiKeyEntry(row.workspaceId(), row.apiKey())
        }

        return enabledForProjectCheckBox.isSelected != loadedEnabledForProject ||
            seqServerUrlField.text != loadedSnapshot.seqServerUrl ||
            String(apiKeyField.password) != loadedSnapshot.defaultApiKey.orEmpty() ||
            currentRows != loadedSnapshot.workspaceApiKeyEntries
    }

    fun apply(project: Project, projectSettings: SeqMcpProjectSettingsService, settings: SeqMcpSettingsService) {
        if (!validateWorkspaceRows()) {
            throw ConfigurationException(SeqMcpBundle.message("settings.workspaceKeys.invalid"))
        }
        projectSettings.enabled = enabledForProjectCheckBox.isSelected
        settings.seqServerUrl = seqServerUrlField.text.trim()
        settings.setApiKey(String(apiKeyField.password).trim())
        settings.setWorkspaceApiKeyEntries(
            workspaceRows.map { row ->
                WorkspaceApiKeyEntry(row.workspaceId(), row.apiKey())
            },
        )
        ToolWindowManager.getInstance(project).getToolWindow("Seq MCP")?.setAvailable(projectSettings.enabled)
        loadedEnabledForProject = projectSettings.enabled
        loadedSnapshot = settings.loadSnapshot()
    }

    fun reset(projectSettings: SeqMcpProjectSettingsService, settings: SeqMcpSettingsService) {
        loadedEnabledForProject = projectSettings.enabled
        enabledForProjectCheckBox.isSelected = loadedEnabledForProject
        seqServerUrlField.text = settings.seqServerUrl
        loadedSnapshot = loadSnapshot(settings)
        apiKeyField.text = loadedSnapshot.defaultApiKey.orEmpty()
        workspaceRows.clear()
        workspaceRowsPanel.removeAll()
        loadedSnapshot.workspaceApiKeyEntries.forEach { addWorkspaceRow(it) }
        refreshWorkspaceRows()
    }

    private fun loadSnapshot(settings: SeqMcpSettingsService): SeqMcpSettingsSnapshot {
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<SeqMcpSettingsSnapshot, RuntimeException> {
                settings.loadSnapshot()
            },
            SeqMcpBundle.message("settings.displayName"),
            false,
            null,
        )
    }

    private fun addWorkspaceRow(entry: WorkspaceApiKeyEntry = WorkspaceApiKeyEntry("", "")) {
        val row = WorkspaceApiKeyRow(entry, onChange = { validateWorkspaceRows() }) {
            workspaceRows.remove(it)
            refreshWorkspaceRows()
        }
        workspaceRows += row
        workspaceRowsPanel.add(row.component)
        refreshWorkspaceRows()
    }

    private fun validateWorkspaceRows(): Boolean {
        val counts = workspaceRows.groupingBy { it.workspaceId() }.eachCount()
        var valid = true
        workspaceRows.forEach { row ->
            val workspaceError = when {
                row.workspaceId().isEmpty() -> SeqMcpBundle.message("settings.workspaceKeys.missingWorkspace")
                counts.getValue(row.workspaceId()) > 1 -> SeqMcpBundle.message("settings.workspaceKeys.duplicate")
                else -> null
            }
            val keyError = if (row.apiKey().isEmpty()) SeqMcpBundle.message("settings.workspaceKeys.missingKey") else null
            row.showErrors(workspaceError, keyError)
            if (workspaceError != null || keyError != null) valid = false
        }
        return valid
    }

    private fun refreshWorkspaceRows() {
        workspaceRowsPanel.removeAll()
        if (workspaceRows.isEmpty()) {
            workspaceRowsPanel.add(
                JBLabel(SeqMcpBundle.message("settings.workspaceKeys.empty")).also {
                    it.border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 4, 0)
                },
            )
        } else {
            workspaceRows.map { it.component }.forEach(workspaceRowsPanel::add)
        }
        validateWorkspaceRows()
        workspaceRowsPanel.revalidate()
        workspaceRowsPanel.repaint()
    }
}

private class WorkspaceApiKeyRow(
    entry: WorkspaceApiKeyEntry,
    onChange: () -> Unit,
    onRemove: (WorkspaceApiKeyRow) -> Unit,
) {
    private val workspaceField = JBTextField(entry.workspaceId)
    private val apiKeyField = JBPasswordField().apply {
        text = entry.apiKey
    }

    private val workspaceError = JBLabel().apply { foreground = JBColor.RED }
    private val keyError = JBLabel().apply { foreground = JBColor.RED }

    val component: JPanel = JBPanel<JBPanel<*>>(GridLayout(1, 0, 8, 0)).apply {
        add(labeledPanel(SeqMcpBundle.message("settings.workspaceKeys.workspace"), workspaceField, workspaceError))
        add(labeledPanel(SeqMcpBundle.message("settings.workspaceKeys.apiKey"), apiKeyField, keyError))
        add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 18)).apply {
            add(JButton(SeqMcpBundle.message("settings.workspaceKeys.remove")).apply {
                addActionListener { onRemove(this@WorkspaceApiKeyRow) }
            })
        })
    }

    init {
        val listener = object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = onChange()
        }
        workspaceField.document.addDocumentListener(listener)
        apiKeyField.document.addDocumentListener(listener)
    }

    fun showErrors(workspace: String?, key: String?) {
        workspaceError.text = workspace.orEmpty()
        workspaceError.isVisible = workspace != null
        keyError.text = key.orEmpty()
        keyError.isVisible = key != null
    }

    fun workspaceId(): String = workspaceField.text.trim()

    fun apiKey(): String = String(apiKeyField.password).trim()

    private fun labeledPanel(label: String, component: JComponent, error: JBLabel): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
            add(JBLabel(label), BorderLayout.NORTH)
            add(component, BorderLayout.CENTER)
            add(error, BorderLayout.SOUTH)
        }
    }
}
