package dev.admcp.core.session

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.SessionId
import dev.admcp.core.model.SessionSnapshot
import dev.admcp.core.model.SessionState
import dev.admcp.core.model.WorkspaceRef
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DebugSession(
    val id: SessionId,
    val deviceId: DeviceId,
    val packageName: String?,
    val workspace: WorkspaceRef?,
    val process: ProcessRef?,
    val state: SessionState,
    val createdAt: Instant,
    val cleanup: () -> Unit,
)

class SessionManager {
    private val sessions = ConcurrentHashMap<SessionId, DebugSession>()

    fun create(deviceId: DeviceId, packageName: String?, workspace: WorkspaceRef?, cleanup: () -> Unit): DebugSession {
        val session = DebugSession(
            id = SessionId("session-" + UUID.randomUUID()),
            deviceId = deviceId,
            packageName = packageName,
            workspace = workspace,
            process = null,
            state = SessionState.READY,
            createdAt = Instant.now(),
            cleanup = cleanup,
        )
        sessions[session.id] = session
        return session
    }

    fun require(id: SessionId): DebugSession =
        sessions[id] ?: throw IllegalStateException("unknown or closed session: ${id.value}")

    fun attachProcess(id: SessionId, process: ProcessRef) {
        val session = require(id)
        require(session.deviceId == process.deviceId) { "process device does not match session device" }
        sessions[id] = session.copy(process = process, state = SessionState.RUNNING)
    }

    fun list(): List<DebugSession> = sessions.values.sortedBy { it.createdAt }

    fun close(id: SessionId) {
        val session = sessions.remove(id) ?: return
        runCatching { session.cleanup() }
    }

    fun snapshot(id: SessionId): SessionSnapshot {
        val session = require(id)
        return SessionSnapshot(session.id, session.state, session.deviceId, session.process, emptyList())
    }
}
