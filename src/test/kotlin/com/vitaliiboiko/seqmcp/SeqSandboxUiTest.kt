// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService
import com.vitaliiboiko.seqmcp.services.SeqMcpSettingsService
import com.vitaliiboiko.seqmcp.settings.SeqMcpConfigurable
import java.awt.Component
import java.awt.Container
import java.net.URI
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPasswordField
import javax.swing.JTextField

/** Opt-in real Seq test: settings UI -> Password Safe -> production service -> HTTP/WebSocket -> UI result. */
class SeqSandboxUiTest : BasePlatformTestCase() {
    private var view: Disposable? = null
    private var configurable: SeqMcpConfigurable? = null
    private val settings get() = service<SeqMcpSettingsService>()

    override fun tearDown() {
        try {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            view?.let(Disposer::dispose)
            configurable?.disposeUIResources()
            settings.seqServerUrl = ""
            settings.setApiKey(null)
            settings.setWorkspaceApiKeyEntries(emptyList())
        } finally {
            super.tearDown()
        }
    }

    fun testConnectionAgainstAuthenticatedSandboxAndRecoveryFromInvalidKey() {
        val server = requiredEnvironment("SEQ_TEST_SERVER_URL")
        val key = requiredEnvironment("SEQ_TEST_API_KEY")
        val uri = URI(server)
        val localHttp = System.getenv("SEQ_TEST_ALLOW_LOCAL_HTTP") == "true" &&
            uri.scheme.equals("http", ignoreCase = true) && uri.host in setOf("localhost", "127.0.0.1", "[::1]")
        require((uri.scheme.equals("https", ignoreCase = true) || localHttp) && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Use HTTPS or explicitly allow loopback HTTP with SEQ_TEST_ALLOW_LOCAL_HTTP=true."
        }
        settings.seqServerUrl = ""
        settings.setApiKey(null)
        settings.setWorkspaceApiKeyEntries(emptyList())
        project.service<SeqMcpProjectSettingsService>().enabled = true
        val configuration = SeqMcpConfigurable(project).also { configurable = it }
        val settingsPanel = configuration.createComponent()
        configuration.reset()
        settingsPanel.descendants().filterIsInstance<JTextField>().single { it !is JPasswordField }.text = server
        val keyField = settingsPanel.descendants().filterIsInstance<JPasswordField>().single()
        keyField.text = key
        configuration.apply()

        // Use the normal production constructor: no backend or transport substitution.
        val type = Class.forName("com.vitaliiboiko.seqmcp.toolWindow.SeqMcpToolWindow")
        val instance = type.getDeclaredConstructor(Project::class.java).apply { isAccessible = true }.newInstance(project)
        view = instance as Disposable
        val panel = type.getDeclaredMethod("content").apply { isAccessible = true }.invoke(instance) as JComponent
        val testButton = panel.descendants().filterIsInstance<JButton>()
            .single { it.text == SeqMcpBundle.message("toolWindow.testConnection") }
        val success = SeqMcpBundle.message("toolWindow.connectionTest", SeqMcpBundle.message("connection.success"))
        val notTested = SeqMcpBundle.message("toolWindow.connectionTest", SeqMcpBundle.message("connection.notTested"))
        assertTrue(panel.labels().contains(notTested))
        testButton.doClick()
        waitForResult(testButton)
        assertTrue("Real sandbox event access must succeed", panel.labels().contains(success))

        keyField.text = "invalid-${UUID.randomUUID()}"
        configuration.apply()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(panel.labels().contains(notTested))
        testButton.doClick()
        waitForResult(testButton)
        val failurePrefix = SeqMcpBundle.message("toolWindow.connectionTest", SeqMcpBundle.message("connection.failed", ""))
        assertTrue("Sandbox must enforce authentication and display a failed connection test", panel.labels().any { it.startsWith(failurePrefix) })

        keyField.text = key
        configuration.apply()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        testButton.doClick()
        waitForResult(testButton)
        assertTrue("Corrected credentials must allow a successful retry", panel.labels().contains(success))
    }

    private fun waitForResult(button: JButton) {
        PlatformTestUtil.waitWithEventsDispatching("Real Seq connection test did not finish", { button.isEnabled }, 60)
    }

    private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
        ?: error("Set $name before running sandboxUiTest; see docs/development.md. This test is not silently skipped.")

    private fun Container.labels() = descendants().filterIsInstance<JLabel>().map { it.text }.toList()
    private fun Container.descendants(): Sequence<Component> = components.asSequence().flatMap {
        sequenceOf(it) + if (it is Container) it.descendants() else emptySequence()
    }
}
