// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-FileCopyrightText: 2000-2021 JetBrains s.r.o.
// SPDX-License-Identifier: MIT AND Apache-2.0
// License: see LICENSE and LICENSES/Apache-2.0.txt in the repository root.

package com.vitaliiboiko.seqmcp.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.vitaliiboiko.seqmcp.SeqMcpBundle
import com.vitaliiboiko.seqmcp.settings.SeqMcpConfigurable
import com.vitaliiboiko.seqmcp.services.SeqApiClient
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.services.ConnectionSettingsListener
import com.vitaliiboiko.seqmcp.services.SeqApiException
import com.vitaliiboiko.seqmcp.services.SeqApiService
import com.vitaliiboiko.seqmcp.services.SeqMcpLogService
import com.vitaliiboiko.seqmcp.services.SeqMcpLogSnapshot
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectService
import com.vitaliiboiko.seqmcp.services.SeqSearchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JButton

class SeqMcpToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowView = SeqMcpToolWindow(project)
        val content = ContentFactory.getInstance().createContent(toolWindowView.content(), null, false)
        content.setDisposer(toolWindowView)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = project.service<SeqMcpProjectService>().isEnabledForProject()
}

private class SeqMcpToolWindow @JvmOverloads constructor(
    private val project: Project,
    private val apiService: SeqApiClient = service<SeqApiService>(),
    private val openSettings: () -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqMcpConfigurable::class.java)
    },
) : Disposable {

    private val projectService = project.service<SeqMcpProjectService>()
    private val settings = service<SeqMcpSettingsService>()
    private var connectionRevision = settings.connectionSettingsRevision
    private var disposed = false
    private val connectionLabel = JBLabel()
    private val testConnectionButton = JButton(SeqMcpBundle.message("toolWindow.testConnection"))
    private val logService = project.service<SeqMcpLogService>()
    private val statusLabel = JBLabel()
    private val urlLabel = JBLabel()
    private val apiKeyLabel = JBLabel()
    private val toolsLabel = JBLabel()
    private val nextStepsLabel = JBLabel()
    private val logDocument = EditorFactory.getInstance().createDocument("")
    private val logEditor = EditorFactory.getInstance().createViewer(logDocument, project) as EditorEx

    fun content() = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(12)

        val details = JBPanel<JBPanel<*>>(GridLayout(0, 1, 0, 8)).apply {
            add(JBLabel(SeqMcpBundle.message("toolWindow.title")))
            add(JBLabel(SeqMcpBundle.message("toolWindow.subtitle")))
            add(statusLabel)
            add(connectionLabel)
            add(urlLabel)
            add(apiKeyLabel)
            add(toolsLabel)
            add(JBLabel(SeqMcpBundle.message("toolWindow.project", projectService.projectName())))
            add(nextStepsLabel)
        }

        testConnectionButton.apply {
            toolTipText = SeqMcpBundle.message("toolWindow.testConnectionDescription")
            styleButton(JBColor(Color(0x0D47A1), Color(0x1565C0)), JBColor(Color.WHITE, Color.WHITE))
            addActionListener { testConnection() }
        }
        val configureButton = JButton(SeqMcpBundle.message("toolWindow.openSettings")).apply {
            addActionListener {
                openSettings()
                refresh()
            }
        }
        val fetchLast10Button = JButton(SeqMcpBundle.message("toolWindow.fetchLast10Events")).apply {
            toolTipText = SeqMcpBundle.message("toolWindow.fetchLast10EventsDescription")
            styleButton(
                background = JBColor(Color(0x3C3F41), Color(0x4C5052)),
                foreground = JBColor(Color.WHITE, Color.WHITE),
            )
            addActionListener {
                fetchLast10Events()
            }
        }
        val clearSeqEventsButton = JButton(SeqMcpBundle.message("toolWindow.clearSeqEvents")).apply {
            toolTipText = SeqMcpBundle.message("toolWindow.clearSeqEventsDescription")
            styleButton(
                background = JBColor(Color(0xB71C1C), Color(0xC62828)),
                foreground = JBColor(Color.WHITE, Color.WHITE),
            )
            addActionListener {
                clearSeqEvents()
            }
        }
        val refreshButton = JButton(SeqMcpBundle.message("toolWindow.refresh")).apply {
            styleButton(
                background = JBColor(Color(0x3C3F41), Color(0x4C5052)),
                foreground = JBColor(Color(0xDFE1E5), Color(0xDFE1E5)),
            )
            addActionListener {
                refresh()
                logService.append(
                    SeqMcpBundle.message("log.refreshed", projectService.connectionStatus()),
                )
            }
        }

        normalizeButtonSizes(
            testConnectionButton,
            fetchLast10Button,
            refreshButton,
            configureButton,
        )

        val settingsActionsRow = JBPanel<JBPanel<*>>(GridLayout(0, 2, 8, 8)).apply {
            add(testConnectionButton)
            add(fetchLast10Button)
            add(refreshButton)
            add(configureButton)
        }

        val settingsActions = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(settingsActionsRow, BorderLayout.NORTH)
            add(JBPanel<JBPanel<*>>(BorderLayout(0, 4)).apply {
                border = JBUI.Borders.emptyTop(16)
                add(JBLabel(SeqMcpBundle.message("toolWindow.advanced")), BorderLayout.NORTH)
                add(JBPanel<JBPanel<*>>(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
                    add(clearSeqEventsButton)
                }, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }

        configureLogEditor()

        val clearLogButton = JButton(SeqMcpBundle.message("toolWindow.clearLog")).apply {
            toolTipText = SeqMcpBundle.message("toolWindow.clearLogDescription")
            addActionListener {
                clearLog()
            }
        }

        val logHeader = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(SeqMcpBundle.message("toolWindow.logTitle")), BorderLayout.WEST)
            add(clearLogButton, BorderLayout.EAST)
        }

        val logPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(12)
            add(logHeader, BorderLayout.NORTH)
            add(logEditor.component, BorderLayout.CENTER)
        }

        val topPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(details, BorderLayout.CENTER)
            add(settingsActions, BorderLayout.SOUTH)
        }

        setConnectionStatus(SeqMcpBundle.message("connection.notTested"))
        refresh()
        ApplicationManager.getApplication().messageBus.connect(this@SeqMcpToolWindow).subscribe(
            SeqMcpSettingsService.CONNECTION_SETTINGS_CHANGED,
            ConnectionSettingsListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) refresh()
                }
            },
        )
        renderLog(logService.snapshot())
        logService.addListener(this@SeqMcpToolWindow, ::renderLog)

        add(
            JBSplitter(true, 0.34f).apply {
                firstComponent = topPanel
                secondComponent = logPanel
            },
            BorderLayout.CENTER,
        )
    }

    private fun refresh() {
        if (connectionRevision != settings.connectionSettingsRevision) {
            connectionRevision = settings.connectionSettingsRevision
            setConnectionStatus(SeqMcpBundle.message("connection.notTested"))
        }
        statusLabel.text = SeqMcpBundle.message("toolWindow.status", projectService.connectionStatus())
        urlLabel.text = SeqMcpBundle.message("toolWindow.url", projectService.seqServerUrl())
        apiKeyLabel.text = SeqMcpBundle.message("toolWindow.apiKey", projectService.apiKeyStatus())
        toolsLabel.text = SeqMcpBundle.message("toolWindow.tools", projectService.supportedTools())
        nextStepsLabel.text = projectService.nextSteps()
    }

    private fun setConnectionStatus(status: String) {
        connectionLabel.text = SeqMcpBundle.message("toolWindow.connectionTest", status)
    }

    private fun testConnection() {
        refresh()
        if (!projectService.isEnabledForProject() || !settings.isConfigured()) {
            setConnectionStatus(SeqMcpBundle.message(
                if (!projectService.isEnabledForProject()) "connection.disabled" else "connection.notConfigured",
            ))
            return
        }
        val revision = settings.connectionSettingsRevision
        testConnectionButton.isEnabled = false
        setConnectionStatus(SeqMcpBundle.message("connection.testing"))
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, SeqMcpBundle.message("toolWindow.testConnection"), true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                runBlockingCancellable { apiService.searchEvents(SeqSearchRequest(filter = "", count = 1, timeoutSeconds = 15)) }
            }

            private fun finish(status: String) {
                if (disposed || project.isDisposed) return
                testConnectionButton.isEnabled = true
                refresh()
                // Do not attribute an older request's result to new server settings or credentials.
                if (revision == settings.connectionSettingsRevision && projectService.isEnabledForProject()) {
                    setConnectionStatus(status)
                } else {
                    setConnectionStatus(SeqMcpBundle.message("connection.notTested"))
                }
            }

            override fun onSuccess() = finish(SeqMcpBundle.message("connection.success"))

            override fun onThrowable(error: Throwable) = finish(SeqMcpBundle.message(
                "connection.failed",
                if (error is SeqApiException) error.message ?: SeqMcpBundle.message("connection.error")
                else SeqMcpBundle.message("connection.error"),
            ))

            override fun onCancel() = finish(SeqMcpBundle.message("connection.cancelled"))
        })
    }

    private fun clearSeqEvents() {
        if (!projectService.isEnabledForProject()) {
            showNotification(
                content = SeqMcpBundle.message("notification.seqClearDisabled"),
                type = NotificationType.WARNING,
            )
            return
        }

        if (projectService.seqServerUrl() == SeqMcpBundle.message("value.notSet")) {
            showNotification(
                content = SeqMcpBundle.message("notification.seqClearNotConfigured"),
                type = NotificationType.WARNING,
            )
            return
        }

        val targetServer = settings.seqServerUrl
        val revision = settings.connectionSettingsRevision
        val confirmed = Messages.showYesNoDialog(
            project,
            SeqMcpBundle.message("dialog.clearSeqEvents.message", targetServer),
            SeqMcpBundle.message("dialog.clearSeqEvents.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) {
            return
        }
        if (revision != settings.connectionSettingsRevision || !projectService.isEnabledForProject()) {
            showNotification(SeqMcpBundle.message("notification.deleteSettingsChanged"), NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            SeqMcpBundle.message("progress.clearSeqEvents"),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = SeqMcpBundle.message("progress.clearSeqEvents")
                runBlocking {
                    // Bind deletion to the exact server named in the confirmation, even if settings change.
                    val key = settings.getApiKey()
                    if (revision != settings.connectionSettingsRevision || !projectService.isEnabledForProject()) {
                        throw SeqApiException(SeqMcpBundle.message("notification.deleteSettingsChanged"))
                    }
                    SeqApiClient(serverUrl = { targetServer }, apiKey = { key }).use { it.deleteEvents() }
                }
            }

            override fun onSuccess() {
                logService.append(SeqMcpBundle.message("log.seqEventsCleared"))
                showNotification(
                    content = SeqMcpBundle.message("notification.seqEventsCleared"),
                    type = NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                val message = when (error) {
                    is SeqApiException -> error.message ?: SeqMcpBundle.message("notification.seqEventsClearFailed")
                    else -> SeqMcpBundle.message("notification.seqEventsClearFailed")
                }
                logService.append(SeqMcpBundle.message("log.seqEventsClearFailed", message))
                showNotification(
                    content = message,
                    type = NotificationType.ERROR,
                )
            }
        })
    }

    private fun fetchLast10Events() {
        if (!projectService.isEnabledForProject()) {
            showNotification(
                content = SeqMcpBundle.message("notification.seqFetchDisabled"),
                type = NotificationType.WARNING,
            )
            return
        }

        if (projectService.seqServerUrl() == SeqMcpBundle.message("value.notSet")) {
            showNotification(
                content = SeqMcpBundle.message("notification.seqFetchNotConfigured"),
                type = NotificationType.WARNING,
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            SeqMcpBundle.message("progress.fetchLast10Events"),
            true,
        ) {
            private var fetched: List<JsonObject> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.text = SeqMcpBundle.message("progress.fetchLast10Events")
                fetched = runBlocking {
                    apiService.searchEvents(
                        SeqSearchRequest(
                            filter = "",
                            count = 10,
                            timeoutSeconds = 15,
                        ),
                    )
                }.map { it as JsonObject }
            }

            override fun onSuccess() {
                logService.append(SeqMcpBundle.message("log.seqLast10EventsFetched", fetched.size))
                fetched.forEachIndexed { index, event ->
                    logService.append(SeqMcpBundle.message("log.seqLast10EventItem", index + 1, eventSummary(event)))
                }
                showNotification(
                    content = SeqMcpBundle.message("notification.seqLast10EventsFetched", fetched.size),
                    type = NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                val message = when (error) {
                    is SeqApiException -> error.message ?: SeqMcpBundle.message("notification.seqLast10EventsFailed")
                    else -> SeqMcpBundle.message("notification.seqLast10EventsFailed")
                }
                logService.append(SeqMcpBundle.message("log.seqLast10EventsFetchFailed", message))
                showNotification(
                    content = message,
                    type = NotificationType.ERROR,
                )
            }
        })
    }

    private fun clearLog() {
        val removedCount = logService.clear()
        if (removedCount == 0) {
            showNotification(
                content = SeqMcpBundle.message("notification.logAlreadyEmpty"),
                type = NotificationType.INFORMATION,
            )
            return
        }

        showNotification(
            content = SeqMcpBundle.message("notification.logCleared", removedCount),
            type = NotificationType.INFORMATION,
        )
    }

    private fun showNotification(content: String, type: NotificationType) {
        Notification(
            "Seq MCP",
            SeqMcpBundle.message("notification.title"),
            content,
            type,
        ).notify(project)
    }

    private fun eventSummary(event: JsonObject): String {
        val id = event["Id"]?.jsonPrimitive?.contentOrNull
        val rendered = event["RenderedMessage"]?.jsonPrimitive?.contentOrNull
        val fallback = event["MessageTemplate"]?.jsonPrimitive?.contentOrNull ?: "<no message>"
        val message = (rendered ?: fallback).replace('\n', ' ').take(80)
        return listOfNotNull(id).joinToString(prefix = "[", postfix = "] ") + message
    }

    private fun JButton.styleButton(background: Color, foreground: Color) {
        this.background = background
        this.foreground = foreground
        isOpaque = true
        isContentAreaFilled = true
        isBorderPainted = true
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(0x5E6164), Color(0x6B7073))),
            JBUI.Borders.empty(6, 12),
        )
    }

    private fun normalizeButtonSizes(vararg buttons: JButton) {
        val buttonSize = buttons
            .map { it.preferredSize }
            .fold(Dimension()) { maxSize, size ->
                Dimension(
                    maxOf(maxSize.width, size.width),
                    maxOf(maxSize.height, size.height),
                )
            }

        buttons.forEach { button ->
            button.preferredSize = Dimension(buttonSize)
            button.minimumSize = Dimension(buttonSize)
            button.maximumSize = Dimension(buttonSize)
        }
    }

    private fun configureLogEditor() {
        logEditor.apply {
            isViewer = true
            setHorizontalScrollbarVisible(true)
            setVerticalScrollbarVisible(true)
            settings.apply {
                isLineNumbersShown = true
                isLineMarkerAreaShown = false
                isFoldingOutlineShown = false
                additionalColumnsCount = 0
                additionalLinesCount = 0
            }
        }
    }

    private fun renderLog(snapshot: SeqMcpLogSnapshot) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) {
                return@invokeLater
            }

            ApplicationManager.getApplication().runWriteAction {
                logDocument.setText(snapshot.text)
            }
            logEditor.caretModel.moveToOffset(logDocument.textLength)
            logEditor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
        }
    }

    override fun dispose() {
        disposed = true
        EditorFactory.getInstance().releaseEditor(logEditor)
    }
}
