package com.vitaliiboiko.seqmcp.startup

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.vitaliiboiko.seqmcp.SeqMcpBundle
import com.vitaliiboiko.seqmcp.services.SeqMcpLogService
import com.vitaliiboiko.seqmcp.services.SeqMcpProjectService

class SeqMcpProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val projectService = project.service<SeqMcpProjectService>()
        project.service<SeqMcpLogService>().append(
            SeqMcpBundle.message("log.initialized", projectService.projectName(), projectService.connectionStatus()),
        )
        thisLogger().info(
            SeqMcpBundle.message("startup.initialized", projectService.projectName(), projectService.connectionStatus()),
        )
    }
}
