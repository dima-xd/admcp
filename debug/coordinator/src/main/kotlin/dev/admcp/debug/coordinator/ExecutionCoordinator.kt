package dev.admcp.debug.coordinator

import dev.admcp.core.model.BackendBlockReason
import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BackendLifecycle
import dev.admcp.core.model.BackendStatus
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.ExecutionSnapshot
import dev.admcp.core.model.ExecutionState
import dev.admcp.core.model.OperationId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.StopEpoch
import dev.admcp.core.model.StopReason
import dev.admcp.core.model.StopScope
import dev.admcp.core.model.SuspensionLease
import dev.admcp.core.model.SuspensionLeaseState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class StaleHandleException(message: String) : RuntimeException(message)

data class EventApplyResult(val ignored: Boolean, val snapshot: ExecutionSnapshot?)

data class CoordinatorSnapshot(
    val execution: ExecutionSnapshot,
    val leases: List<SuspensionLease>,
    val statuses: Map<BackendId, BackendStatus>,
)

data class ResumeResult(
    val execution: ExecutionSnapshot,
    val leases: List<SuspensionLease>,
)

class ExecutionCoordinator {
    private data class State(
        var execution: ExecutionSnapshot,
        var leases: MutableList<SuspensionLease> = mutableListOf(),
        val sequences: MutableMap<BackendId, Long> = mutableMapOf(),
    )

    private val states = ConcurrentHashMap<ProcessInstanceId, State>()

    @Synchronized
    fun onEvent(event: BackendExecutionEvent): EventApplyResult {
        val state = states.getOrPut(event.process.instanceId) {
            State(
                ExecutionSnapshot(
                    event.process,
                    ExecutionState.UNKNOWN,
                    StopScope.UNKNOWN,
                    StopEpoch(0),
                    null,
                    null,
                    emptySet(),
                    Instant.now(),
                ),
            )
        }
        val last = state.sequences[event.backendId]
        val sequence = event.backendSequence
        if (sequence != null && last != null && sequence <= last) {
            return EventApplyResult(true, state.execution)
        }
        state.sequences[event.backendId] = sequence ?: (last ?: 0)
        when (event.eventKind) {
            BackendExecutionEventKind.STOPPED -> applyStop(state, event)
            BackendExecutionEventKind.PROCESS_EXITED -> {
                state.leases.replaceAll { it.copy(state = SuspensionLeaseState.RELEASED, releaseSequence = event.backendSequence, releaseEvidence = "process exited") }
                state.leases.clear()
                state.execution = state.execution.copy(
                    state = ExecutionState.EXITED,
                    scope = StopScope.PROCESS,
                    stopEpoch = StopEpoch(state.execution.stopEpoch.value + 1),
                    stopOrigin = event.backendId,
                    stopReason = null,
                    stoppedThreadIds = emptySet(),
                    observedAt = Instant.now(),
                )
            }
            BackendExecutionEventKind.RESUMED, BackendExecutionEventKind.STATE_CHANGED,
            BackendExecutionEventKind.THREAD_CREATED, BackendExecutionEventKind.THREAD_EXITED -> Unit
        }
        return EventApplyResult(false, state.execution)
    }

    private fun applyStop(state: State, event: BackendExecutionEvent) {
        val existing = event.backendStopId?.let { id -> state.leases.firstOrNull { it.backendStopId == id && it.ownerBackend == event.backendId } }
        if (existing != null) {
            state.leases.replaceAll { if (it.leaseId == existing.leaseId) it.copy(acquiredSequence = event.backendSequence) else it }
            return
        }
        val wasStopped = state.execution.state == ExecutionState.STOPPED
        val sameBackendStep = wasStopped && event.reason == StopReason.STEP && state.leases.any {
            it.ownerBackend == event.backendId && it.state == SuspensionLeaseState.ACTIVE
        }
        val epoch = if (wasStopped && !sameBackendStep) state.execution.stopEpoch else StopEpoch(state.execution.stopEpoch.value + 1)
        if (sameBackendStep) {
            state.leases.replaceAll { lease ->
                if (lease.ownerBackend == event.backendId && lease.state == SuspensionLeaseState.ACTIVE) {
                    lease.copy(
                        state = SuspensionLeaseState.RELEASED,
                        releaseSequence = event.backendSequence,
                        releaseEvidence = "superseded by new stop",
                    )
                } else {
                    lease
                }
            }
        }
        val scope = if (event.scope == StopScope.PROCESS || state.execution.scope == StopScope.PROCESS) StopScope.PROCESS else StopScope.THREAD
        state.leases += SuspensionLease(
            leaseId = "${event.backendId.value}:${event.backendStopId ?: event.backendSequence}",
            ownerBackend = event.backendId,
            process = event.process,
            scope = event.scope,
            stoppedThreadIds = event.stoppedThreadIds,
            backendStopId = event.backendStopId,
            operationId = event.operationId,
            acquiredSequence = event.backendSequence,
            state = SuspensionLeaseState.ACTIVE,
        )
        state.execution = state.execution.copy(
            state = ExecutionState.STOPPED,
            scope = scope,
            stopEpoch = epoch,
            stopOrigin = if (sameBackendStep) event.backendId else state.execution.stopOrigin ?: event.backendId,
            stopReason = if (sameBackendStep) event.reason ?: StopReason.UNKNOWN else state.execution.stopReason ?: event.reason ?: StopReason.UNKNOWN,
            stoppedThreadIds = if (sameBackendStep) event.stoppedThreadIds else state.execution.stoppedThreadIds + event.stoppedThreadIds,
            observedAt = Instant.now(),
        )
    }

    @Synchronized
    fun snapshot(process: ProcessRef): List<ExecutionSnapshot> =
        states[process.instanceId]?.let { listOf(it.execution) } ?: emptyList()

    @Synchronized
    fun leases(process: ProcessRef): List<SuspensionLease> =
        states[process.instanceId]?.leases?.toList() ?: emptyList()

    @Synchronized
    fun backendStatus(process: ProcessRef, backendId: BackendId): BackendStatus? {
        val state = states[process.instanceId] ?: return null
        val owns = state.leases.filter { it.ownerBackend == backendId }
        val otherProcessStop = state.leases.any { it.ownerBackend != backendId && it.scope == StopScope.PROCESS }
        val blockedBy = if (state.execution.state == ExecutionState.STOPPED) {
            when {
                otherProcessStop -> BackendBlockReason.PROCESS_STOPPED_BY_OTHER_BACKEND
                owns.isEmpty() && state.execution.scope == StopScope.THREAD -> BackendBlockReason.THREAD_NOT_SUSPENDED
                owns.isEmpty() -> BackendBlockReason.STALE_STOP_EPOCH
                else -> null
            }
        } else null
        return BackendStatus(
            backendId = backendId,
            lifecycle = if (state.execution.state == ExecutionState.EXITED) BackendLifecycle.DETACHED else BackendLifecycle.ATTACHED,
            executionState = state.execution.state,
            inspectionAvailable = state.execution.state == ExecutionState.STOPPED && owns.isNotEmpty() && blockedBy == null,
            blockedBy = blockedBy,
            stopEpoch = state.execution.stopEpoch,
        )
    }

    @Synchronized
    fun resume(process: ProcessRef, backendId: BackendId, operationId: OperationId): ResumeResult {
        val state = states[process.instanceId] ?: error("unknown process instance")
        val owned = state.leases.filter { it.ownerBackend == backendId }
        require(owned.isNotEmpty()) { "backend does not own an active suspension lease" }
        val released = owned.map {
            it.copy(state = SuspensionLeaseState.RELEASED, releaseSequence = it.acquiredSequence?.plus(1), releaseEvidence = operationId.value)
        }
        state.leases.removeAll(owned.toSet())
        state.leases.addAll(released)
        if (state.leases.none { it.state == SuspensionLeaseState.ACTIVE }) {
            state.leases.clear()
            state.execution = state.execution.copy(
                state = ExecutionState.RUNNING,
                scope = StopScope.UNKNOWN,
                stopEpoch = StopEpoch(state.execution.stopEpoch.value + 1),
                stopOrigin = null,
                stopReason = null,
                stoppedThreadIds = emptySet(),
                observedAt = Instant.now(),
            )
        }
        return ResumeResult(state.execution, state.leases.toList())
    }

    @Synchronized
    fun detach(process: ProcessRef, backendId: BackendId): CoordinatorSnapshot {
        val state = states[process.instanceId] ?: error("unknown process instance")
        state.leases.removeAll { it.ownerBackend == backendId }
        if (state.leases.none { it.state == SuspensionLeaseState.ACTIVE } && state.execution.state == ExecutionState.STOPPED) {
            state.leases.clear()
            state.execution = state.execution.copy(
                state = ExecutionState.RUNNING,
                scope = StopScope.UNKNOWN,
                stopEpoch = StopEpoch(state.execution.stopEpoch.value + 1),
                stopOrigin = null,
                stopReason = null,
                stoppedThreadIds = emptySet(),
                observedAt = Instant.now(),
            )
        }
        val statuses = mutableMapOf<BackendId, BackendStatus>()
        state.sequences.keys.forEach { id -> backendStatus(process, id)?.let { statuses[id] = it } }
        return CoordinatorSnapshot(state.execution, state.leases.toList(), statuses)
    }

    @Synchronized
    fun requireFresh(handle: DebugHandle) {
        val state = states[handle.process.instanceId]
            ?: throw StaleHandleException("process instance is no longer tracked")
        if (state.execution.state != ExecutionState.STOPPED) {
            throw StaleHandleException("process is not stopped")
        }
        if (state.execution.stopEpoch != handle.stopEpoch) {
            throw StaleHandleException("stale stop epoch: handle=${handle.stopEpoch.value} current=${state.execution.stopEpoch.value}")
        }
        if (state.leases.none { it.ownerBackend == handle.backendId && it.state == SuspensionLeaseState.ACTIVE }) {
            throw StaleHandleException("backend no longer owns an active lease")
        }
    }
}
