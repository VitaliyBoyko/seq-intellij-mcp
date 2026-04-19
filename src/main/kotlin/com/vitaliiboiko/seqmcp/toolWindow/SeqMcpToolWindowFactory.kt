package com.vitaliiboiko.seqmcp.toolWindow

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
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
import java.awt.FlowLayout
import java.awt.GridLayout
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

private class SeqMcpToolWindow(private val project: Project) : Disposable {

    private val projectService = project.service<SeqMcpProjectService>()
    private val apiService = service<SeqApiService>()
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
            add(urlLabel)
            add(apiKeyLabel)
            add(toolsLabel)
            add(JBLabel(SeqMcpBundle.message("toolWindow.project", projectService.projectName())))
            add(nextStepsLabel)
        }

        val settingsActions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JButton(SeqMcpBundle.message("toolWindow.openSettings")).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqMcpConfigurable::class.java)
                    refresh()
                    logService.append(SeqMcpBundle.message("log.settingsOpened"))
                }
            })
            add(JButton(SeqMcpBundle.message("toolWindow.fetchLast10Events")).apply {
                toolTipText = SeqMcpBundle.message("toolWindow.fetchLast10EventsDescription")
                styleButton(
                    background = JBColor(Color(0x0D47A1), Color(0x1565C0)),
                    foreground = JBColor(Color.WHITE, Color.WHITE),
                )
                addActionListener {
                    fetchLast10Events()
                }
            })
            add(JButton(SeqMcpBundle.message("toolWindow.clearSeqEvents")).apply {
                toolTipText = SeqMcpBundle.message("toolWindow.clearSeqEventsDescription")
                styleButton(
                    background = JBColor(Color(0xB71C1C), Color(0xC62828)),
                    foreground = JBColor(Color.WHITE, Color.WHITE),
                )
                addActionListener {
                    clearSeqEvents()
                }
            })
            add(JButton(SeqMcpBundle.message("toolWindow.refresh")).apply {
                addActionListener {
                    refresh()
                    logService.append(
                        SeqMcpBundle.message("log.refreshed", projectService.connectionStatus()),
                    )
                }
            })
        }

        configureLogEditor()

        val logHeader = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(SeqMcpBundle.message("toolWindow.logTitle")), BorderLayout.WEST)
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

        refresh()
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
        statusLabel.text = SeqMcpBundle.message("toolWindow.status", projectService.connectionStatus())
        urlLabel.text = SeqMcpBundle.message("toolWindow.url", projectService.seqServerUrl())
        apiKeyLabel.text = SeqMcpBundle.message("toolWindow.apiKey", projectService.apiKeyStatus())
        toolsLabel.text = SeqMcpBundle.message("toolWindow.tools", projectService.supportedTools())
        nextStepsLabel.text = projectService.nextSteps()
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

        val confirmed = Messages.showYesNoDialog(
            project,
            SeqMcpBundle.message("dialog.clearSeqEvents.message"),
            SeqMcpBundle.message("dialog.clearSeqEvents.title"),
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) {
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
                    apiService.deleteEvents()
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
        border = JBUI.Borders.empty(6, 10)
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
            if (project.isDisposed) {
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
        EditorFactory.getInstance().releaseEditor(logEditor)
    }
}
