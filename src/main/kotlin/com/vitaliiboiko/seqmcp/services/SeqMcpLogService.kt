package com.vitaliiboiko.seqmcp.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class SeqMcpLogService {
    private val listeners = CopyOnWriteArrayList<(SeqMcpLogSnapshot) -> Unit>()
    private val records = mutableListOf<SeqMcpLogRecord>()
    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun snapshot(): SeqMcpLogSnapshot = synchronized(records) { snapshotLocked() }

    fun recordCount(): Int = synchronized(records) { records.size }

    fun append(message: String) {
        val snapshot = synchronized(records) {
            records += SeqMcpLogRecord(
                timestamp = Instant.now(),
                message = message,
            )
            snapshotLocked()
        }
        notifyListeners(snapshot)
    }

    fun clear(): Int {
        val removedCount: Int
        synchronized(records) {
            removedCount = records.size
            records.clear()
        }
        notifyListeners(SeqMcpLogSnapshot.EMPTY)
        return removedCount
    }

    fun addListener(parentDisposable: Disposable, listener: (SeqMcpLogSnapshot) -> Unit) {
        listeners += listener
        Disposer.register(parentDisposable) {
            listeners -= listener
        }
    }

    private fun snapshotLocked(): SeqMcpLogSnapshot {
        return SeqMcpLogSnapshot(
            text = records.joinToString(separator = "\n") { record ->
                "[${timestampFormatter.format(record.timestamp)}] ${record.message}"
            },
            recordCount = records.size,
        )
    }

    private fun notifyListeners(snapshot: SeqMcpLogSnapshot) {
        listeners.forEach { listener -> listener(snapshot) }
    }

    companion object {
        fun getInstance(project: Project): SeqMcpLogService = project.service<SeqMcpLogService>()
    }
}

data class SeqMcpLogSnapshot(
    val text: String,
    val recordCount: Int,
) {
    companion object {
        val EMPTY = SeqMcpLogSnapshot(
            text = "",
            recordCount = 0,
        )
    }
}

private data class SeqMcpLogRecord(
    val timestamp: Instant,
    val message: String,
)
