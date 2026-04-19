package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "com.vitaliiboiko.seqmcp.services.SeqMcpProjectSettingsService",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class SeqMcpProjectSettingsService : SimplePersistentStateComponent<SeqMcpProjectSettingsService.State>(State()) {
    class State : BaseState() {
        var enabled by property(true)
    }

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    companion object {
        fun getInstance(project: Project): SeqMcpProjectSettingsService = project.service<SeqMcpProjectSettingsService>()
    }
}
