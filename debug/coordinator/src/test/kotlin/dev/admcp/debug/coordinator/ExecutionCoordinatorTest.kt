package dev.admcp.debug.coordinator

import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ExecutionState
import dev.admcp.core.model.OperationId
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.StopEpoch
import dev.admcp.core.model.StopReason
import dev.admcp.core.model.StopScope
import dev.admcp.core.model.SuspensionLeaseState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionCoordinatorTest {
    private val device = DeviceId("d1")
    private val process = ProcessRef(device, ProcessId(10), "pkg", ProcessInstanceId("instance-1"), "s1")
    private val jdi = BackendId("JDI")
    private val lldb = BackendId("LLDB")
    private val coordinator = ExecutionCoordinator()

    @Test
    fun startsStopWithSingleBackendLease() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        val snapshot = coordinator.snapshot(process).single()
        assertEquals(ExecutionState.STOPPED, snapshot.state)
        assertEquals(StopEpoch(1), snapshot.stopEpoch)
        assertEquals(1, coordinator.leases(process).size)
        val status = coordinator.backendStatus(process, jdi)!!
        assertTrue(status.inspectionAvailable)
    }

    @Test
    fun secondBackendStopCreatesIndependentLease() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        coordinator.onEvent(stop(lldb, StopScope.PROCESS, emptySet(), 1))
        assertEquals(2, coordinator.leases(process).size)
        assertEquals(StopEpoch(1), coordinator.snapshot(process).single().stopEpoch)
    }

    @Test
    fun processWideNativeStopBlocksJavaInspection() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        coordinator.onEvent(stop(lldb, StopScope.PROCESS, emptySet(), 1))
        val jdiStatus = coordinator.backendStatus(process, jdi)!!
        assertFalse(jdiStatus.inspectionAvailable)
        assertEquals("PROCESS_STOPPED_BY_OTHER_BACKEND", jdiStatus.blockedBy?.name)
    }

    @Test
    fun resumeReleasesOnlyOwnerLease() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        coordinator.onEvent(stop(lldb, StopScope.PROCESS, emptySet(), 1))
        val result = coordinator.resume(process, jdi, OperationId("op-resume-jdi"))
        assertEquals(SuspensionLeaseState.RELEASED, result.leases.single { it.ownerBackend == jdi }.state)
        assertEquals(SuspensionLeaseState.ACTIVE, result.leases.single { it.ownerBackend == lldb }.state)
        assertEquals(ExecutionState.STOPPED, result.execution.state)
    }

    @Test
    fun resumesOnlyAfterAllLeasesReleased() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        coordinator.onEvent(stop(lldb, StopScope.PROCESS, emptySet(), 1))
        coordinator.resume(process, jdi, OperationId("op1"))
        val final = coordinator.resume(process, lldb, OperationId("op2"))
        assertEquals(ExecutionState.RUNNING, final.execution.state)
        assertEquals(StopEpoch(2), final.execution.stopEpoch)
        assertTrue(final.leases.isEmpty())
    }

    @Test
    fun staleHandleIsRejectedAfterResume() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        val handle = DebugHandle(dev.admcp.core.model.SessionId("s"), jdi, process, StopEpoch(1), 7, "frame-1")
        coordinator.requireFresh(handle)
        coordinator.resume(process, jdi, OperationId("op1"))
        assertFailsWith<StaleHandleException> { coordinator.requireFresh(handle) }
    }

    @Test
    fun detachCleansUpBackendLeases() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        coordinator.onEvent(stop(lldb, StopScope.PROCESS, emptySet(), 1))
        val snapshot = coordinator.detach(process, jdi)
        assertTrue(snapshot.leases.none { it.ownerBackend == jdi })
        assertEquals(ExecutionState.STOPPED, snapshot.execution.state)
    }

    @Test
    fun processExitInvalidatesHandlesAndAdvancesEpoch() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.PROCESS, emptySet(), 1))
        val handle = DebugHandle(dev.admcp.core.model.SessionId("s"), jdi, process, StopEpoch(1), null, "frame")
        coordinator.onEvent(exit(lldb, 2))
        assertEquals(ExecutionState.EXITED, coordinator.snapshot(process).single().state)
        assertFailsWith<StaleHandleException> { coordinator.requireFresh(handle) }
    }

    @Test
    fun outOfOrderBackendEventsAreIgnored() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 5))
        val ignored = coordinator.onEvent(stop(jdi, StopScope.PROCESS, emptySet(), 4))
        assertTrue(ignored.ignored)
        assertEquals(1, coordinator.leases(process).size)
    }

    @Test
    fun threadScopedStopKeepsOtherThreadsUsableOnlyWhenProven() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1))
        val status = coordinator.backendStatus(process, jdi)!!
        assertTrue(status.inspectionAvailable)
        assertEquals(StopScope.THREAD, coordinator.snapshot(process).single().scope)
    }

    @Test
    fun operationIdIsPreservedForCorrelation() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1, OperationId("op-stop")))
        assertEquals("op-stop", coordinator.leases(process).single().operationId?.value)
    }

    @Test
    fun duplicateStopDoesNotCreateDuplicateLease() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1, backendStopId = "stop-1"))
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 2, backendStopId = "stop-1"))
        assertEquals(1, coordinator.leases(process).size)
    }

    @Test
    fun sameEventSetBreakpointsShareOneEpoch() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1, backendStopId = "stop-1"))
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(8), 2, backendStopId = "stop-2"))
        assertEquals(StopEpoch(1), coordinator.snapshot(process).single().stopEpoch)
        assertEquals(2, coordinator.leases(process).count { it.state == SuspensionLeaseState.ACTIVE })
    }

    @Test
    fun newStopFromSameBackendAdvancesEpochAndReleasesOldLease() = runBlocking {
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 1, backendStopId = "stop-1"))
        val oldHandle = DebugHandle(dev.admcp.core.model.SessionId("s"), jdi, process, StopEpoch(1), null, "frame")
        coordinator.onEvent(stop(jdi, StopScope.THREAD, setOf(7), 2, reason = StopReason.STEP, backendStopId = "stop-2"))
        val snapshot = coordinator.snapshot(process).single()
        assertEquals(StopEpoch(2), snapshot.stopEpoch)
        assertEquals(1, coordinator.leases(process).count { it.state == SuspensionLeaseState.ACTIVE })
        assertEquals(1, coordinator.leases(process).count { it.state == SuspensionLeaseState.RELEASED })
        assertFailsWith<StaleHandleException> { coordinator.requireFresh(oldHandle) }
    }

    private fun stop(
        backend: BackendId,
        scope: StopScope,
        threads: Set<Long>,
        sequence: Long,
        operationId: OperationId? = null,
        backendStopId: String = "stop-$sequence",
        reason: StopReason = StopReason.BREAKPOINT,
    ) = BackendExecutionEvent(
        backend,
        process,
        BackendExecutionEventKind.STOPPED,
        scope,
        threads,
        reason,
        backendStopId,
        sequence,
        operationId,
        1_000 + sequence,
    )

    private fun exit(backend: BackendId, sequence: Long) = BackendExecutionEvent(
        backend,
        process,
        BackendExecutionEventKind.PROCESS_EXITED,
        StopScope.PROCESS,
        emptySet(),
        null,
        null,
        sequence,
        null,
        2_000 + sequence,
    )
}
