package dev.admcp.core.model

import java.nio.file.Path
import java.time.Instant

enum class ExecutionState { RUNNING, STOPPED, UNKNOWN, EXITED }
enum class StopScope { PROCESS, THREAD, UNKNOWN }
enum class BackendLifecycle { NOT_APPLICABLE, PROBING, STARTING, ATTACHED, DETACHING, DETACHED, FAILED }
enum class BackendBlockReason {
    PROCESS_STOPPED_BY_OTHER_BACKEND, THREAD_NOT_SUSPENDED, STALE_STOP_EPOCH,
    TARGET_EXITED, BACKEND_UNAVAILABLE, UNKNOWN,
}
enum class ThreadState { RUNNING, SUSPENDED, WAITING, SLEEPING, BLOCKED, TERMINATED, UNKNOWN }
enum class StepKind { INTO, OVER, OUT }
enum class StepGranularity { STATEMENT, LINE, INSTRUCTION }
enum class StopReason { BREAKPOINT, EXCEPTION, STEP, PAUSE_REQUEST, SIGNAL, ENTRY, UNKNOWN }
enum class BackendExecutionEventKind { STOPPED, RESUMED, THREAD_CREATED, THREAD_EXITED, PROCESS_EXITED, STATE_CHANGED }

data class ProcessRef(
    val deviceId: DeviceId,
    val pid: ProcessId,
    val packageName: String?,
    val instanceId: ProcessInstanceId,
    val startToken: String?,
) {
    init { require(instanceId.value.isNotBlank()) { "process instance is required" } }
}

data class ExecutionSnapshot(
    val process: ProcessRef,
    val state: ExecutionState,
    val scope: StopScope,
    val stopEpoch: StopEpoch,
    val stopOrigin: BackendId?,
    val stopReason: StopReason?,
    val stoppedThreadIds: Set<Long>,
    val observedAt: Instant,
) {
    init {
        if (state == ExecutionState.STOPPED) {
            require(stopOrigin != null) { "stopped execution requires a stop origin" }
            require(stopReason != null) { "stopped execution requires a stop reason" }
        }
    }
}

data class BackendStatus(
    val backendId: BackendId,
    val lifecycle: BackendLifecycle,
    val executionState: ExecutionState,
    val inspectionAvailable: Boolean,
    val blockedBy: BackendBlockReason? = null,
    val stopEpoch: StopEpoch? = null,
    val capabilities: List<Capability> = emptyList(),
)

data class DebugHandle(
    val sessionId: SessionId,
    val backendId: BackendId,
    val process: ProcessRef,
    val stopEpoch: StopEpoch,
    val threadId: Long?,
    val opaqueId: String,
)

data class SourceLocation(val path: Path, val line: Int, val column: Int?) {
    init { require(line >= 1) { "source line must be positive" } }
}

data class ThreadSnapshot(val id: Long, val name: String, val state: ThreadState, val suspended: Boolean)
data class FrameSnapshot(val handle: DebugHandle, val name: String, val location: SourceLocation?, val module: String?)
data class VariableSnapshot(val name: String, val type: String?, val value: String?, val handle: DebugHandle?, val childrenCount: Int?)
data class ModuleSnapshot(val name: String, val path: String?, val buildId: String?, val loadAddress: ULong?)
data class RegisterValue(val name: String, val value: String, val bitWidth: Int)
data class RuntimeLocation(
    val module: String?,
    val address: ULong?,
    val className: String?,
    val methodName: String?,
    val sourceUri: String?,
    val line: Int?,
)
data class SourceResolution(val location: SourceLocation?, val status: Availability, val reason: String?)
data class LoadedModule(val path: String, val buildId: String?, val abi: String, val loadAddress: ULong?)
data class SymbolMatch(
    val matched: Boolean,
    val symbolPath: Path?,
    val expectedBuildId: String?,
    val actualBuildId: String?,
    val reason: String?,
)
data class ModuleDescriptorView(val name: String, val path: String?, val buildId: String?)

data class BreakpointRequest(
    val source: String?,
    val line: Int?,
    val function: String?,
    val condition: String?,
    val exceptionFilter: String?,
)
data class BreakpointResult(
    val id: String,
    val verified: Boolean,
    val resolvedLocations: List<SourceLocation>,
    val message: String?,
)
data class ResumeRequest(val threadId: Long?, val allThreads: Boolean)
data class StepRequest(val threadId: Long, val kind: StepKind, val granularity: StepGranularity?)
data class IsolateSnapshot(val id: String, val name: String, val runnable: Boolean)
