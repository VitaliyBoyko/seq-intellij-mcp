package com.vitaliiboiko.seqmcp.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.vitaliiboiko.seqmcp.SeqMcpBundle
import com.vitaliiboiko.seqmcp.settings.SeqMcpConfigurable
import com.vitaliiboiko.seqmcp.services.SeqMcpLogService
import com.vitaliiboiko.seqmcp.services.SeqMcpLogSnapshot
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectService
import java.awt.BorderLayout
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

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private class SeqMcpToolWindow(private val project: Project) : Disposable {

    private val projectService = project.service<SeqMcpProjectService>()
    private val logService = project.service<SeqMcpLogService>()
    private val statusLabel = JBLabel()
    private val urlLabel = JBLabel()
    private val apiKeyLabel = JBLabel()
    private val toolsLabel = JBLabel()
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
            add(JBLabel(SeqMcpBundle.message("toolWindow.nextSteps")))
        }

        val settingsActions = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JButton(SeqMcpBundle.message("toolWindow.openSettings")).apply {
                addActionListener {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SeqMcpConfigurable::class.java)
                    refresh()
                    logService.append(SeqMcpBundle.message("log.settingsOpened"))
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
        val clearLogAction = ClearLogAction(project, logService)
        val logToolbar = ActionManager.getInstance()
            .createActionToolbar(
                "SeqMcpLogToolbar",
                DefaultActionGroup().apply {
                    ActionManager.getInstance().getAction(GOTO_LINE_ACTION_ID)?.let { add(it) }
                    addSeparator()
                    add(clearLogAction)
                },
                true,
            ).apply {
                targetComponent = logEditor.contentComponent
            }
        installLogPopupMenu(clearLogAction)

        val logHeader = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBLabel(SeqMcpBundle.message("toolWindow.logTitle")), BorderLayout.WEST)
            add(logToolbar.component, BorderLayout.EAST)
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

    private fun installLogPopupMenu(clearLogAction: ClearLogAction) {
        val popupActionGroup = DefaultActionGroup().apply {
            (ActionManager.getInstance().getAction(EDITOR_POPUP_MENU_ACTION_ID) as? ActionGroup)
                ?.getChildren(null)
                ?.forEach(::add)
            addSeparator()
            add(clearLogAction)
        }
        PopupHandler.installPopupMenu(logEditor.contentComponent, popupActionGroup, EDITOR_POPUP_MENU_ACTION_ID)
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

    companion object {
        private const val GOTO_LINE_ACTION_ID = "GotoLine"
        private const val EDITOR_POPUP_MENU_ACTION_ID = "EditorPopupMenu"
    }
}

private class ClearLogAction(
    private val project: Project,
    private val logService: SeqMcpLogService,
) : DumbAwareAction(
    SeqMcpBundle.message("toolWindow.clearLog"),
    SeqMcpBundle.message("toolWindow.clearLogDescription"),
    AllIcons.Actions.GC,
) {

    override fun actionPerformed(event: AnActionEvent) {
        val removedCount = logService.clear()
        val content = if (removedCount == 0) {
            SeqMcpBundle.message("notification.logAlreadyEmpty")
        } else {
            SeqMcpBundle.message("notification.logCleared", removedCount)
        }

        Notification(
            "Seq MCP",
            SeqMcpBundle.message("notification.title"),
            content,
            NotificationType.INFORMATION,
        ).notify(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = logService.recordCount() > 0
    }
}
