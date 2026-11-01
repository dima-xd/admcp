package dev.admcp.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ModelValidationTest {
    @Test
    fun idsAreValidated() {
        assertFailsWith<IllegalArgumentException> { SessionId(" ") }
        assertFailsWith<IllegalArgumentException> { DeviceId("") }
        assertFailsWith<IllegalArgumentException> { ProcessInstanceId("") }
        assertFailsWith<IllegalArgumentException> { ProcessId(-1) }
        assertEquals("s1", SessionId("s1").value)
    }

    @Test
    fun stopEpochMustBeNonNegative() {
        assertFailsWith<IllegalArgumentException> { StopEpoch(-1) }
        assertEquals(0, StopEpoch(0).value)
    }

    @Test
    fun backendStatusCanBeAttachedButNotInspectable() {
        val status = BackendStatus(
            backendId = BackendId("JDI"),
            lifecycle = BackendLifecycle.ATTACHED,
            executionState = ExecutionState.STOPPED,
            inspectionAvailable = false,
            blockedBy = BackendBlockReason.PROCESS_STOPPED_BY_OTHER_BACKEND,
        )
        assertEquals(false, status.inspectionAvailable)
        assertNotNull(status.blockedBy)
    }

    @Test
    fun executionSnapshotRequiresStopMetadataWhenStopped() {
        val process = process("instance-1")
        assertFailsWith<IllegalArgumentException> {
            ExecutionSnapshot(process, ExecutionState.STOPPED, StopScope.PROCESS, StopEpoch(1), null, null, emptySet(), Instant.now())
        }
    }

    @Test
    fun processInstanceIdDiffersAfterPidReuse() {
        val first = process("instance-1")
        val second = process("instance-2")
        assertEquals(first.pid, second.pid)
        assertEquals(false, first.instanceId == second.instanceId)
    }

    @Test
    fun processRefRequiresInstanceId() {
        assertFailsWith<IllegalArgumentException> { ProcessRef(DeviceId("d"), ProcessId(1), "pkg", ProcessInstanceId(" "), null) }
    }

    @Test
    fun backendEventIncludesSequenceAndOperationId() {
        val event = BackendExecutionEvent(
            backendId = BackendId("JDI"),
            process = process("instance-1"),
            eventKind = BackendExecutionEventKind.STOPPED,
            scope = StopScope.THREAD,
            stoppedThreadIds = setOf(4),
            reason = StopReason.BREAKPOINT,
            backendStopId = "stop-1",
            backendSequence = 12,
            operationId = OperationId("op-1"),
            observedAtHostMonotonicNanos = 100,
        )
        assertEquals(12, event.backendSequence)
        assertEquals("op-1", event.operationId?.value)
    }

    @Test
    fun suspensionLeaseHasOwnerScopeAndLifecycleState() {
        val lease = SuspensionLease(
            leaseId = "lease-1",
            ownerBackend = BackendId("LLDB"),
            process = process("instance-1"),
            scope = StopScope.PROCESS,
            stoppedThreadIds = emptySet(),
            backendStopId = "s-1",
            operationId = null,
            acquiredSequence = 2,
            state = SuspensionLeaseState.ACTIVE,
        )
        assertEquals(StopScope.PROCESS, lease.scope)
        assertEquals(SuspensionLeaseState.ACTIVE, lease.state)
    }

    @Test
    fun clockCalibrationRecordsUncertainty() {
        val calibration = ClockCalibration(
            sourceClock = ClockDomain.DEVICE_MONOTONIC,
            targetClock = ClockDomain.HOST_MONOTONIC,
            offsetNanos = 100,
            uncertaintyNanos = 5,
            sampleCount = 5,
            measuredAt = Instant.now(),
            method = "midpoint",
            status = ClockCalibrationStatus.ESTIMATED,
        )
        assertEquals(5L, calibration.uncertaintyNanos)
    }

    @Test
    fun riskPolicyUsesTypedEnums() {
        val assessment = UiActionAssessment(UiActionRisk.DESTRUCTIVE, UiActionDecision.REQUIRE_CONFIRMATION, listOf("clears data"))
        assertEquals(UiActionDecision.REQUIRE_CONFIRMATION, assessment.decision)
        val request = ApprovalRequest(ApprovalPurpose.UI_ACTION, "ui-1", setOf("tap"), ApprovalRisk.DESTRUCTIVE_ACTION, java.time.Duration.ofMinutes(1))
        assertEquals(ApprovalRisk.DESTRUCTIVE_ACTION, request.risk)
    }

    private fun process(instance: String): ProcessRef =
        ProcessRef(DeviceId("d1"), ProcessId(100), "dev.admcp.fixture", ProcessInstanceId(instance), null)
}
