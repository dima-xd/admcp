package dev.admcp.debug.jdi

import com.sun.jdi.ArrayReference
import com.sun.jdi.Bootstrap
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.EventSet
import com.sun.jdi.connect.AttachingConnector
import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BreakpointRequest
import dev.admcp.core.model.BreakpointResult
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.EvaluationRequest
import dev.admcp.core.model.EvaluationResult
import dev.admcp.core.model.ExecutionState
import dev.admcp.core.model.FrameSnapshot
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.ResumeRequest
import dev.admcp.core.model.SourceLocation
import dev.admcp.core.model.StepRequest
import dev.admcp.core.model.StopEpoch
import dev.admcp.core.model.StopReason
import dev.admcp.core.model.StopScope
import dev.admcp.core.model.ThreadSnapshot
import dev.admcp.core.model.ThreadState
import dev.admcp.core.model.VariableSnapshot
import dev.admcp.debug.api.BackendUnavailableException
import dev.admcp.debug.api.DebugBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

fun interface JdiConnector {
    fun attach(port: Int): VirtualMachine
}

object JdiConnectorProbe {
    fun available(): Boolean = Bootstrap.virtualMachineManager().attachingConnectors()
        .any { it.name() == "com.sun.jdi.SocketAttach" }
}

class SocketAttachConnectorFactory(private val host: String = "127.0.0.1") : JdiConnector {
    override fun attach(port: Int): VirtualMachine {
        val connector = Bootstrap.virtualMachineManager().attachingConnectors()
            .filterIsInstance<AttachingConnector>()
            .firstOrNull { it.name() == "com.sun.jdi.SocketAttach" }
            ?: throw BackendUnavailableException(BackendId("JDI"), "JDI SocketAttach connector is unavailable")
        val arguments = connector.defaultArguments()
        arguments["hostname"]?.setValue(host)
        arguments["port"]?.setValue(port.toString())
        arguments["timeout"]?.setValue("15000")
        return connector.attach(arguments)
    }
}

class JdiHandleGuard(
    private val processInstance: String,
    private val epoch: () -> Long,
) {
    fun check(handle: DebugHandle) {
        require(handle.process.instanceId.value == processInstance) { "handle belongs to a different process instance" }
        require(handle.stopEpoch.value == epoch()) { "stale JDI handle for stop epoch ${handle.stopEpoch.value}" }
    }
}

class JdiBackend(
    private val port: Int,
    private val connector: JdiConnector = SocketAttachConnectorFactory(),
) : DebugBackend {
    override val id: BackendId = BackendId("JDI")
    private val eventFlow = MutableSharedFlow<BackendExecutionEvent>(extraBufferCapacity = 64)
    override val events: Flow<BackendExecutionEvent> = eventFlow
    private val epoch = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private var vm: VirtualMachine? = null
    private var process: ProcessRef? = null
    private var eventThread: Thread? = null
    @Volatile
    private var pendingEventSet: EventSet? = null
    private val breakpoints = ConcurrentHashMap<String, com.sun.jdi.request.BreakpointRequest>()
    private val pendingBreakpoints = ConcurrentHashMap<String, PendingBreakpoint>()
    private val breakpointCounter = AtomicInteger(0)

    private data class PendingBreakpoint(
        val id: String,
        val className: String,
        val line: Int,
        val source: String?,
        val condition: String?,
        val request: com.sun.jdi.request.ClassPrepareRequest,
    )

    override suspend fun attach(process: ProcessRef) {
        if (this.process != null) throw IllegalStateException("JDI backend is already attached")
        this.process = process
        val machine = connector.attach(port)
        vm = machine
        machine.eventRequestManager()
        eventThread = Thread({ processEvents(machine, process) }, "jdi-events-${process.instanceId.value}").apply {
            isDaemon = true
            start()
        }
    }

    private fun processEvents(machine: VirtualMachine, process: ProcessRef) {
        val queue = machine.eventQueue()
        while (!closed.get()) {
            val events = runCatching { queue.remove(1000) }.getOrNull() ?: continue
            events.forEach { event ->
                when (event) {
                    is com.sun.jdi.event.BreakpointEvent -> {
                        val threadId = event.thread().uniqueID()
                        epoch.incrementAndGet()
                        pendingEventSet = events
                        eventFlow.tryEmit(executionEvent(process, BackendExecutionEventKind.STOPPED, threadId, StopReason.BREAKPOINT))
                    }
                    is com.sun.jdi.event.ExceptionEvent -> {
                        epoch.incrementAndGet()
                        pendingEventSet = events
                        eventFlow.tryEmit(executionEvent(process, BackendExecutionEventKind.STOPPED, event.thread().uniqueID(), StopReason.EXCEPTION))
                    }
                    is com.sun.jdi.event.StepEvent -> {
                        runCatching { event.request().disable() }
                        epoch.incrementAndGet()
                        pendingEventSet = events
                        eventFlow.tryEmit(executionEvent(process, BackendExecutionEventKind.STOPPED, event.thread().uniqueID(), StopReason.STEP))
                    }
                    is com.sun.jdi.event.ClassPrepareEvent -> {
                        resolvePendingBreakpoints(machine, event.referenceType())
                    }
                    is com.sun.jdi.event.ThreadDeathEvent -> {
                        eventFlow.tryEmit(executionEvent(process, BackendExecutionEventKind.THREAD_EXITED, event.thread().uniqueID(), null))
                    }
                    is com.sun.jdi.event.VMDisconnectEvent, is com.sun.jdi.event.VMDeathEvent -> {
                        pendingEventSet = null
                        eventFlow.tryEmit(
                            BackendExecutionEvent(
                                id, process, BackendExecutionEventKind.PROCESS_EXITED, StopScope.PROCESS,
                                emptySet(), null, null, null, null, System.nanoTime(),
                            ),
                        )
                        closed.set(true)
                    }
                }
            }
        }
    }

    private fun executionEvent(process: ProcessRef, kind: BackendExecutionEventKind, threadId: Long, reason: StopReason?) =
        BackendExecutionEvent(
            id, process, kind, StopScope.THREAD, setOf(threadId), reason, "jdi-$threadId-${epoch.get()}",
            epoch.get(), null, System.nanoTime(),
        )

    override suspend fun detach() {
        closed.set(true)
        pendingEventSet = null
        runCatching { vm?.dispose() }
        vm = null
        process = null
        eventThread?.interrupt()
    }

    override suspend fun threads(process: ProcessRef): List<ThreadSnapshot> {
        val machine = requireMachine()
        return machine.allThreads().map {
            ThreadSnapshot(
                it.uniqueID(),
                it.name(),
                when (it.status()) {
                    ThreadReference.THREAD_STATUS_RUNNING -> ThreadState.RUNNING
                    ThreadReference.THREAD_STATUS_SLEEPING -> ThreadState.SLEEPING
                    ThreadReference.THREAD_STATUS_WAIT -> ThreadState.WAITING
                    ThreadReference.THREAD_STATUS_MONITOR -> ThreadState.BLOCKED
                    ThreadReference.THREAD_STATUS_ZOMBIE -> ThreadState.TERMINATED
                    else -> ThreadState.UNKNOWN
                },
                it.isSuspended,
            )
        }
    }

    override suspend fun frames(thread: DebugHandle): List<FrameSnapshot> {
        JdiHandleGuard(requireProcess().instanceId.value) { epoch.get() }.check(thread)
        val reference = requireNotNull(vm?.allThreads()?.firstOrNull { it.uniqueID() == thread.threadId }) { "thread not found" }
        return reference.frames().mapIndexed { index, stackFrame ->
            FrameSnapshot(
                thread.copy(stopEpoch = StopEpoch(epoch.get()), opaqueId = "frame:$index:${stackFrame.location()}"),
                stackFrame.location().method().name(),
                SourceLocation(java.nio.file.Path.of(stackFrame.location().sourcePath() ?: stackFrame.location().declaringType().name()), stackFrame.location().lineNumber().coerceAtLeast(1), null),
                stackFrame.location().declaringType().name(),
            )
        }
    }

    override suspend fun variables(frame: DebugHandle): List<VariableSnapshot> {
        JdiHandleGuard(requireProcess().instanceId.value) { epoch.get() }.check(frame)
        val reference = requireNotNull(vm?.allThreads()?.firstOrNull { it.uniqueID() == frame.threadId }) { "thread not found" }
        val index = frame.opaqueId.substringAfter("frame:").substringBefore(':').toIntOrNull() ?: 0
        val stackFrame = reference.frames().getOrNull(index) ?: return emptyList()
        return runCatching {
            stackFrame.visibleVariables().map { variable ->
                val value = runCatching { stackFrame.getValue(variable) }.getOrNull()
                VariableSnapshot(variable.name(), variable.typeName(), value?.toString(), null, null)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun listBreakpoints(process: ProcessRef): List<BreakpointResult> {
        val verified = breakpoints.map { (id, request) ->
            val location = request.location()
            BreakpointResult(
                id,
                true,
                listOf(
                    SourceLocation(
                        java.nio.file.Path.of(location.sourcePath() ?: location.declaringType().name()),
                        location.lineNumber().coerceAtLeast(1),
                        null,
                    ),
                ),
                null,
            )
        }
        val pending = pendingBreakpoints.values.map { pending ->
            BreakpointResult(
                pending.id,
                false,
                listOf(SourceLocation(java.nio.file.Path.of(pending.source ?: pending.className), pending.line, null)),
                "class is not loaded yet; the breakpoint will bind when it loads",
            )
        }
        return verified + pending
    }

    override suspend fun setBreakpoint(process: ProcessRef, request: BreakpointRequest): BreakpointResult {
        val machine = requireMachine()
        val className = request.source?.let { source ->
            source.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        } ?: request.function ?: throw IllegalArgumentException("a class name is required for a JDI breakpoint")
        val line = request.line ?: throw IllegalArgumentException("a line is required for a JDI breakpoint")
        val classes = machine.classesByName(className)
            .ifEmpty { machine.allClasses().filter { it.name().endsWith(".$className") } }
        for (type in classes) {
            val location = type.locationsOfLine(line).firstOrNull() ?: continue
            val id = nextBreakpointId()
            registerBreakpoint(machine, id, location, request.condition)
            return BreakpointResult(id, true, listOf(SourceLocation(java.nio.file.Path.of(request.source ?: className), line, null)), null)
        }
        val id = nextBreakpointId()
        val prepare = machine.eventRequestManager().createClassPrepareRequest().apply {
            addClassFilter(if (className.contains('.')) className else "*$className")
            setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_NONE)
            enable()
        }
        pendingBreakpoints[id] = PendingBreakpoint(id, className, line, request.source, request.condition, prepare)
        return BreakpointResult(
            id,
            false,
            listOf(SourceLocation(java.nio.file.Path.of(request.source ?: className), line, null)),
            "class is not loaded yet; the breakpoint will bind when it loads",
        )
    }

    override suspend fun removeBreakpoint(id: String) {
        pendingBreakpoints.remove(id)?.let { pending ->
            runCatching { pending.request.disable() }
            runCatching { vm?.eventRequestManager()?.deleteEventRequest(pending.request) }
        }
        breakpoints.remove(id)?.let { request ->
            request.disable()
            vm?.eventRequestManager()?.deleteEventRequest(request)
        }
    }

    private fun nextBreakpointId(): String = "jdi-breakpoint-${breakpointCounter.incrementAndGet()}"

    private fun registerBreakpoint(
        machine: VirtualMachine,
        id: String,
        location: com.sun.jdi.Location,
        condition: String?,
    ) {
        val breakpoint = machine.eventRequestManager().createBreakpointRequest(location).apply {
            setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_ALL)
            if (!condition.isNullOrBlank()) addCountFilter(1)
        }
        breakpoint.enable()
        breakpoints[id] = breakpoint
    }

    private fun resolvePendingBreakpoints(machine: VirtualMachine, type: com.sun.jdi.ReferenceType) {
        val matches = pendingBreakpoints.values.filter { pending ->
            type.name() == pending.className || type.name().endsWith(".${pending.className}")
        }
        for (pending in matches) {
            if (pendingBreakpoints.remove(pending.id) == null) continue
            runCatching { pending.request.disable() }
            runCatching { machine.eventRequestManager().deleteEventRequest(pending.request) }
            val location = type.locationsOfLine(pending.line).firstOrNull() ?: continue
            registerBreakpoint(machine, pending.id, location, pending.condition)
        }
    }

    override suspend fun resume(request: ResumeRequest, process: ProcessRef) {
        val machine = requireMachine()
        pendingEventSet = null
        if (request.allThreads) machine.resume() else {
            val thread = machine.allThreads().firstOrNull { it.uniqueID() == request.threadId } ?: return
            thread.resume()
        }
    }

    override suspend fun pause(process: ProcessRef, threadId: Long?) {
        val machine = requireMachine()
        if (threadId == null) {
            machine.suspend()
        } else {
            machine.allThreads().firstOrNull { it.uniqueID() == threadId }?.suspend()
        }
    }

    override suspend fun step(request: StepRequest, process: ProcessRef) {
        val machine = requireMachine()
        val thread = machine.allThreads().firstOrNull { it.uniqueID() == request.threadId } ?: return
        val manager = machine.eventRequestManager()
        manager.createStepRequest(
            thread,
            com.sun.jdi.request.StepRequest.STEP_LINE,
            when (request.kind) {
                dev.admcp.core.model.StepKind.INTO -> com.sun.jdi.request.StepRequest.STEP_INTO
                dev.admcp.core.model.StepKind.OVER -> com.sun.jdi.request.StepRequest.STEP_OVER
                dev.admcp.core.model.StepKind.OUT -> com.sun.jdi.request.StepRequest.STEP_OUT
            },
        ).apply {
            setSuspendPolicy(com.sun.jdi.request.EventRequest.SUSPEND_ALL)
            enable()
        }
        val eventSet = pendingEventSet
        pendingEventSet = null
        if (eventSet != null) {
            runCatching { eventSet.resume() }.onFailure { thread.resume() }
        } else {
            thread.resume()
        }
    }

    override suspend fun evaluate(request: EvaluationRequest): EvaluationResult {
        JdiHandleGuard(requireProcess().instanceId.value) { epoch.get() }.check(request.frame)
        val expression = request.expression.trim()
        if (!READ_ONLY_EXPRESSION.matches(expression)) {
            return EvaluationResult(
                null,
                false,
                "expression is not a read-only local, field or array read",
                request.frame,
                request.operation,
            )
        }
        return runCatching {
            val reference = requireNotNull(vm?.allThreads()?.firstOrNull { it.uniqueID() == request.frame.threadId }) {
                "thread not found"
            }
            val index = request.frame.opaqueId.substringAfter("frame:").substringBefore(':').toIntOrNull() ?: 0
            val stackFrame = reference.frames().getOrNull(index) ?: error("frame not found")
            EvaluationResult(
                resolveReadOnly(stackFrame, expression),
                false,
                null,
                request.frame,
                request.operation,
            )
        }.getOrElse { failure ->
            EvaluationResult(
                null,
                false,
                failure.message ?: failure::class.simpleName ?: "JDI evaluation failed",
                request.frame,
                request.operation,
            )
        }
    }

    private fun resolveReadOnly(frame: StackFrame, expression: String): VariableSnapshot {
        val match = READ_ONLY_EXPRESSION.matchEntire(expression) ?: error("unsupported expression")
        val head = match.groupValues[1]
        var value: Value? = if (head == "this") {
            frame.thisObject()
        } else {
            val variable = frame.visibleVariables().firstOrNull { it.name() == head }
                ?: error("local $head is not visible")
            frame.getValue(variable)
        }
        val suffix = match.groupValues[2]
        FIELD_OR_INDEX.findAll(suffix).forEach { token ->
            val field = token.groups[1]?.value
            val index = token.groups[2]?.value?.toIntOrNull()
            value = when {
                field != null -> {
                    val reference = value as? ObjectReference ?: error("$field requires an object")
                    val fieldRef = reference.referenceType().fieldByName(field)
                        ?: reference.referenceType().allFields().firstOrNull { it.name() == field }
                        ?: error("field $field was not found")
                    reference.getValue(fieldRef)
                }
                index != null -> {
                    val array = value as? ArrayReference ?: error("index access requires an array")
                    array.getValue(index)
                }
                else -> error("unsupported expression segment")
            }
        }
        val resolved = value
        return VariableSnapshot(
            name = expression,
            type = resolved?.type()?.name(),
            value = resolved?.toString(),
            handle = null,
            childrenCount = null,
        )
    }

    private fun requireMachine(): VirtualMachine =
        vm ?: throw BackendUnavailableException(id, "JDI backend is not attached")

    private fun requireProcess(): ProcessRef =
        process ?: throw BackendUnavailableException(id, "JDI backend is not attached")

    private companion object {
        private val READ_ONLY_EXPRESSION =
            Regex("""^(this|[A-Za-z_$][A-Za-z0-9_$]*)((?:\.[A-Za-z_$][A-Za-z0-9_$]*|\[\d+])*)$""")
        private val FIELD_OR_INDEX = Regex("""\.([A-Za-z_$][A-Za-z0-9_$]*)|\[(\d+)]""")
    }
}
