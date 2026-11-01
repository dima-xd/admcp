package dev.admcp.debug.api

import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BreakpointRequest
import dev.admcp.core.model.BreakpointResult
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.EvaluationRequest
import dev.admcp.core.model.EvaluationResult
import dev.admcp.core.model.FrameSnapshot
import dev.admcp.core.model.IsolateSnapshot
import dev.admcp.core.model.ModuleSnapshot
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.RegisterValue
import dev.admcp.core.model.ResumeRequest
import dev.admcp.core.model.StepRequest
import dev.admcp.core.model.ThreadSnapshot
import dev.admcp.core.model.VariableSnapshot
import kotlinx.coroutines.flow.Flow

interface DebugBackend {
    val id: BackendId
    val events: Flow<BackendExecutionEvent>
    val lastStopReason: dev.admcp.core.model.StopReason? get() = null
    suspend fun attach(process: ProcessRef)
    suspend fun detach()
    suspend fun threads(process: ProcessRef): List<ThreadSnapshot>
    suspend fun frames(thread: DebugHandle): List<FrameSnapshot>
    suspend fun variables(frame: DebugHandle): List<VariableSnapshot>
    suspend fun setBreakpoint(process: ProcessRef, request: BreakpointRequest): BreakpointResult
    suspend fun listBreakpoints(process: ProcessRef): List<BreakpointResult> = emptyList()
    suspend fun removeBreakpoint(id: String)
    suspend fun resume(request: ResumeRequest, process: ProcessRef)
    suspend fun pause(process: ProcessRef, threadId: Long?)
    suspend fun step(request: StepRequest, process: ProcessRef)
    suspend fun evaluate(request: EvaluationRequest): EvaluationResult
    suspend fun registers(thread: DebugHandle): List<RegisterValue> = emptyList()
    suspend fun isolates(process: ProcessRef): List<IsolateSnapshot> = emptyList()
    suspend fun modules(process: ProcessRef, includeBuildIds: Boolean = false): List<ModuleSnapshot> = emptyList()
    suspend fun readMemory(process: ProcessRef, address: String, size: Int): ByteArray? = null
}

class BackendUnavailableException(val backend: BackendId, message: String) : RuntimeException(message)
