package dev.admcp.core.model

enum class SuspensionLeaseState { ACTIVE, RELEASE_PENDING, RELEASED, INDETERMINATE }

data class SuspensionLease(
    val leaseId: String,
    val ownerBackend: BackendId,
    val process: ProcessRef,
    val scope: StopScope,
    val stoppedThreadIds: Set<Long>,
    val backendStopId: String?,
    val operationId: OperationId?,
    val acquiredSequence: Long?,
    val state: SuspensionLeaseState,
    val releaseSequence: Long? = null,
    val releaseEvidence: String? = null,
) {
    init {
        require(leaseId.isNotBlank()) { "leaseId must not be blank" }
        require(scope != StopScope.UNKNOWN) { "lease scope must be known" }
        if (state == SuspensionLeaseState.RELEASED) {
            require(releaseSequence != null) { "released lease requires a release sequence" }
        }
    }
}

data class BackendExecutionEvent(
    val backendId: BackendId,
    val process: ProcessRef,
    val eventKind: BackendExecutionEventKind,
    val scope: StopScope,
    val stoppedThreadIds: Set<Long>,
    val reason: StopReason?,
    val backendStopId: String?,
    val backendSequence: Long?,
    val operationId: OperationId?,
    val observedAtHostMonotonicNanos: Long,
)
