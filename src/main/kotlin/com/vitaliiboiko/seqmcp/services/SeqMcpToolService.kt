package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
class SeqMcpToolService {
    fun supportedTools(): List<SeqMcpToolDefinition> = SUPPORTED_TOOLS

    companion object {
        private val SUPPORTED_TOOLS = listOf(
            SeqMcpToolDefinition(
                name = "SeqSearch",
                description = "Search events in Seq, a structured log and observability server, using Seq filter expressions",
            ),
            SeqMcpToolDefinition(
                name = "SeqToStrictFilter",
                description = "Convert a loose search query into strict Seq filter syntax for Seq, a structured log server",
            ),
            SeqMcpToolDefinition(
                name = "SeqWaitForEvents",
                description = "Wait for and capture live events from Seq, a structured log and event stream server",
            ),
            SeqMcpToolDefinition(
                name = "SignalList",
                description = "List Seq signals, which are saved event queries and filters in the Seq server",
            ),
        )

        fun getInstance(): SeqMcpToolService = service<SeqMcpToolService>()
    }
}

data class SeqMcpToolDefinition(
    val name: String,
    val description: String,
)
