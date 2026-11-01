package dev.admcp.debug.flutter

import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BreakpointRequest
import dev.admcp.core.model.BreakpointResult
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.EvaluationRequest
import dev.admcp.core.model.EvaluationResult
import dev.admcp.core.model.FrameSnapshot
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.ResumeRequest
import dev.admcp.core.model.SourceLocation
import dev.admcp.core.model.StepKind
import dev.admcp.core.model.StepRequest
import dev.admcp.core.model.StopReason
import dev.admcp.core.model.StopScope
import dev.admcp.core.model.ThreadSnapshot
import dev.admcp.core.model.ThreadState
import dev.admcp.core.model.VariableSnapshot
import dev.admcp.debug.api.DebugBackend
import dev.admcp.debug.dap.DapClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Flutter/Dart debugger backed by `flutter debug-adapter`.
 *
 * The adapter owns `flutter run --machine`; this backend starts the adapter,
 * sends the DAP launch request and exposes breakpoints, stack frames, scopes and
 * stepping through the shared DebugBackend contract.
 */
class DartDapBackend(
    private val flutter: Path,
    private val projectRoot: Path,
    private val deviceId: String,
    private val program: Path,
) : DebugBackend {
    override val id: BackendId = BackendId("DART_DAP")
    override val lastStopReason: StopReason? get() = lastStop.get()?.reason

    private val eventFlow = MutableSharedFlow<BackendExecutionEvent>(extraBufferCapacity = 128)
    override val events: Flow<BackendExecutionEvent> = eventFlow
    private val epoch = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val stopLatch = AtomicReference(CountDownLatch(1))
    private val initializedLatch = CountDownLatch(1)
    private val startedLatch = CountDownLatch(1)
    private val lastStop = AtomicReference<LastStop?>(null)
    private val breakpoints = java.util.concurrent.ConcurrentHashMap<String, BreakpointEntry>()
    private val breakpointEvents = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()

    private var processRef: ProcessRef? = null
    private var adapter: Process? = null
    private var dap: DapClient? = null
    private var adapterLog: Path? = null

    override suspend fun attach(process: ProcessRef) {
        require(Files.isRegularFile(flutter)) { "flutter executable is missing: $flutter" }
        require(Files.isRegularFile(program)) { "Flutter program is missing: $program" }
        processRef = process
        closed.set(false)
        try {
            startAdapter()
            initializeDap()
            val client = dap ?: error("Flutter debug adapter is not running")
            val launch = client.requestAsync(
                "launch",
                buildJsonObject {
                    put("program", program.toString())
                    put("cwd", projectRoot.toString())
                    put("toolArgs", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("-d"))
                        add(kotlinx.serialization.json.JsonPrimitive(deviceId))
                    })
                },
            )
            if (!initializedLatch.await(120, TimeUnit.SECONDS)) {
                error("Flutter debug adapter did not emit the DAP initialized event")
            }
            send("configurationDone", buildJsonObject { })
            client.responseBody("launch", launch.get(300, TimeUnit.SECONDS))
            startedLatch.await(300, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            runCatching { detach() }
            throw failure
        }
    }

    override suspend fun detach() {
        closed.set(true)
        runCatching {
            dap?.request("disconnect", buildJsonObject { put("terminateDebuggee", false) })
        }
        runCatching { dap?.close() }
        dap = null
        adapter?.let { process ->
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        adapter = null
        if (System.getenv("ADMCP_DAP_DEBUG") != "1") {
            adapterLog?.let { log -> runCatching { Files.deleteIfExists(log) } }
        }
        adapterLog = null
        breakpoints.clear()
        processRef = null
        lastStop.set(null)
    }

    override suspend fun threads(process: ProcessRef): List<ThreadSnapshot> = withContext(Dispatchers.IO) {
        val body = send("threads")
        val stop = lastStop.get()
        body.arr("threads").map { item ->
            val threadId = item.long("id") ?: return@map null
            ThreadSnapshot(
                id = threadId,
                name = item.str("name") ?: "isolate-$threadId",
                state = if (stop?.threadId == threadId) ThreadState.SUSPENDED else ThreadState.UNKNOWN,
                suspended = stop?.threadId == threadId,
            )
        }.filterNotNull()
    }

    override suspend fun frames(thread: DebugHandle): List<FrameSnapshot> = withContext(Dispatchers.IO) {
        val threadId = thread.threadId ?: return@withContext emptyList()
        val body = send(
            "stackTrace",
            buildJsonObject {
                put("threadId", threadId)
                put("startFrame", 0)
                put("levels", 200)
            },
        )
        body.arr("stackFrames").mapNotNull { item ->
            val frameId = item.long("id") ?: return@mapNotNull null
            val source = item["source"] as? JsonObject
            val line = (item.long("line") ?: source?.long("line"))?.toInt()?.takeIf { it > 0 }
            val column = (item.long("column") ?: source?.long("column"))?.toInt()?.takeIf { it > 0 }
            val path = source?.str("path")?.let(::sourcePath)
            FrameSnapshot(
                handle = thread.copy(opaqueId = frameId.toString()),
                name = item.str("name") ?: "<unknown>",
                location = if (path != null && line != null) SourceLocation(path, line, column) else null,
                module = source?.str("name"),
            )
        }
    }

    override suspend fun variables(frame: DebugHandle): List<VariableSnapshot> = withContext(Dispatchers.IO) {
        val frameId = frame.opaqueId.toLongOrNull() ?: return@withContext emptyList()
        val scopes = send("scopes", buildJsonObject { put("frameId", frameId) })
        val result = mutableListOf<VariableSnapshot>()
        scopes.arr("scopes").forEach { scope ->
            val reference = scope.long("variablesReference") ?: return@forEach
            if (reference <= 0) return@forEach
            val body = send(
                "variables",
                buildJsonObject {
                    put("variablesReference", reference)
                    put("count", 200)
                },
            )
            body.arr("variables").forEach { variable ->
                if (result.size >= 500) return@forEach
                result += VariableSnapshot(
                    name = variable.str("name") ?: "<unnamed>",
                    type = variable.str("type"),
                    value = variable.str("value"),
                    handle = null,
                    childrenCount = variable.long("variablesReference")?.takeIf { it > 0 }?.let { 1 },
                )
            }
        }
        result
    }

    override suspend fun setBreakpoint(process: ProcessRef, request: BreakpointRequest): BreakpointResult =
        withContext(Dispatchers.IO) {
            if (!request.function.isNullOrBlank()) {
                return@withContext BreakpointResult(
                    "dart-function-unsupported",
                    false,
                    emptyList(),
                    "function breakpoints are not supported for Dart; use a source line breakpoint",
                )
            }
            val source = request.source ?: return@withContext BreakpointResult(
                "invalid-breakpoint", false, emptyList(), "source is required",
            )
            val line = request.line ?: return@withContext BreakpointResult(
                "invalid-breakpoint", false, emptyList(), "line is required",
            )
            val path = Path.of(source).toAbsolutePath().normalize()
            val specs = breakpoints.values.filter { it.source == path } +
                BreakpointEntry(path, line)
            val returned = syncBreakpoints(path, specs)
            val breakpoint = returned.getOrNull(specs.lastIndex)
                ?: return@withContext BreakpointResult("$path:$line", false, emptyList(), "adapter returned no breakpoint")
            val id = breakpoint.long("id")?.toString() ?: "$path:$line"
            val verified = breakpoint.bool("verified") ?: false
            val candidate = specs.last().copy(
                verified = verified,
                message = if (verified) null else breakpoint.str("message"),
            )
            // A breakpoint event can resolve the location while the
            // setBreakpoints response is still unverified. Do not overwrite
            // that resolved state with the older response.
            val previous = breakpoints[id]
            breakpoints[id] = if (previous?.verified == true) previous else candidate
            breakpointEvents[id]?.let { applyBreakpointEvent(id, it) }
            var stored = breakpoints[id] ?: candidate
            // The Dart adapter often resolves a source breakpoint just after
            // the setBreakpoints response. Give the event a short window so
            // the immediate tool result and the stored list agree.
            val deadline = System.currentTimeMillis() + 1_000
            while (!stored.verified && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                stored = breakpoints[id] ?: candidate
            }
            BreakpointResult(
                id,
                stored.verified,
                listOf(SourceLocation(path, stored.line ?: line, null)),
                stored.message,
            )
        }

    override suspend fun listBreakpoints(process: ProcessRef): List<BreakpointResult> =
        breakpoints.entries.map { (id, entry) ->
            val location = entry.source?.let { source ->
                SourceLocation(source, entry.line ?: 1, null)
            }
            BreakpointResult(id, entry.verified, listOfNotNull(location), entry.message)
        }

    override suspend fun removeBreakpoint(id: String) {
        breakpointEvents.remove(id)
        val entry = breakpoints.remove(id) ?: return
        val source = entry.source ?: return
        withContext(Dispatchers.IO) {
            val remaining = breakpoints.values.filter { it.source == source }
            syncBreakpoints(source, remaining)
        }
    }

    override suspend fun resume(request: ResumeRequest, process: ProcessRef) {
        withContext(Dispatchers.IO) {
            lastStop.set(null)
            dap?.request(
                "continue",
                buildJsonObject {
                    request.threadId?.let { put("threadId", it) }
                    put("singleThread", !request.allThreads)
                },
            )
        }
    }

    override suspend fun pause(process: ProcessRef, threadId: Long?) {
        withContext(Dispatchers.IO) {
            dap?.request("pause", buildJsonObject { threadId?.let { put("threadId", it) } })
        }
    }

    override suspend fun step(request: StepRequest, process: ProcessRef) {
        withContext(Dispatchers.IO) {
            val command = when (request.kind) {
                StepKind.INTO -> "stepIn"
                StepKind.OVER -> "next"
                StepKind.OUT -> "stepOut"
            }
            dap?.request(command, buildJsonObject { put("threadId", request.threadId) })
        }
    }

    override suspend fun evaluate(request: EvaluationRequest): EvaluationResult = withContext(Dispatchers.IO) {
        val expression = request.expression.trim()
        if (!DART_READ_ONLY.matches(expression)) {
            return@withContext EvaluationResult(
                null,
                false,
                "expression is not a read-only local, field or index read",
                request.frame,
                request.operation,
            )
        }
        val frameId = request.frame.opaqueId.toLongOrNull()
        if (frameId == null) {
            return@withContext EvaluationResult(null, false, "Dart frame handle has no frame id", request.frame, request.operation)
        }
        runCatching {
            val body = send(
                "evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("frameId", frameId)
                    put("context", "watch")
                },
            )
            val value = body.str("result") ?: error("the debugger returned no value")
            EvaluationResult(
                VariableSnapshot(expression, body.str("type"), value, null, null),
                false,
                null,
                request.frame,
                request.operation,
            )
        }.getOrElse { failure ->
            EvaluationResult(
                null,
                false,
                failure.message ?: failure::class.simpleName ?: "Dart evaluation failed",
                request.frame,
                request.operation,
            )
        }
    }

    private suspend fun syncBreakpoints(path: Path, specs: List<BreakpointEntry>): List<JsonObject> {
        breakpoints.entries.removeIf { it.value.source == path }
        val body = send(
            "setBreakpoints",
            buildJsonObject {
                put("source", buildJsonObject { put("path", path.toString()) })
                put("breakpoints", buildJsonArray {
                    specs.forEach { spec -> add(buildJsonObject { put("line", spec.line) }) }
                })
            },
        )
        val returned = body.arr("breakpoints")
        returned.forEachIndexed { index, breakpoint ->
            val spec = specs.getOrNull(index) ?: return@forEachIndexed
            val id = breakpoint.long("id")?.toString() ?: "$path:${spec.line}:$index"
            val verified = breakpoint.bool("verified") ?: false
            breakpoints[id] = spec.copy(
                verified = verified,
                message = if (verified) null else breakpoint.str("message"),
            )
            breakpointEvents[id]?.let { applyBreakpointEvent(id, it) }
        }
        return returned
    }

    private fun startAdapter() {
        val command = listOf(flutter.toString(), "debug-adapter", "--no-dds", "-d", deviceId)
        val builder = ProcessBuilder(command)
        builder.directory(projectRoot.toFile())
        val flutterRoot = flutter.parent?.parent
        if (flutterRoot != null) builder.environment()["FLUTTER_ROOT"] = flutterRoot.toString()
        val log = Files.createTempFile("admcp-flutter-adapter", ".log")
        adapterLog = log
        builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
        val process = builder.start()
        adapter = process
        dap = DapClient(process.inputStream, process.outputStream).also { client ->
            client.onEvent(::onDapEvent)
        }
    }

    private fun initializeDap() {
        send(
            "initialize",
            buildJsonObject {
                put("clientID", "admcp")
                put("adapterID", "dart")
                put("pathFormat", "path")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("supportsVariableType", true)
                put("supportsRunInTerminalRequest", false)
            },
        )
    }

    private fun applyBreakpointEvent(id: String, breakpoint: JsonObject) {
        val entry = breakpoints[id] ?: return
        val source = breakpoint["source"] as? JsonObject
        val line = breakpoint.long("line")?.toInt()
            ?: source?.long("line")?.toInt()
            ?: entry.line
        val verified = breakpoint.bool("verified") ?: entry.verified
        breakpoints[id] = entry.copy(
            line = line,
            verified = verified,
            message = if (verified) null else breakpoint.str("message") ?: entry.message,
        )
    }

    private fun onDapEvent(message: JsonObject) {
        val event = message.str("event") ?: return
        val body = message["body"] as? JsonObject ?: JsonObject(emptyMap())
        val process = processRef ?: return
        when (event) {
            "initialized" -> initializedLatch.countDown()
            "flutter.appStarted", "process" -> startedLatch.countDown()
            "stopped" -> {
                val threadId = body.long("threadId")
                val reason = when (body.str("reason")) {
                    "breakpoint" -> StopReason.BREAKPOINT
                    "step" -> StopReason.STEP
                    "exception" -> StopReason.EXCEPTION
                    "entry" -> StopReason.ENTRY
                    "pause" -> StopReason.PAUSE_REQUEST
                    else -> StopReason.UNKNOWN
                }
                val newEpoch = epoch.incrementAndGet()
                lastStop.set(LastStop(threadId, reason))
                eventFlow.tryEmit(
                    BackendExecutionEvent(
                        id,
                        process,
                        BackendExecutionEventKind.STOPPED,
                        StopScope.THREAD,
                        setOfNotNull(threadId),
                        reason,
                        "dart-$threadId-$newEpoch",
                        newEpoch,
                        null,
                        System.nanoTime(),
                    ),
                )
                stopLatch.get().countDown()
            }
            "breakpoint" -> {
                val breakpoint = body["breakpoint"] as? JsonObject ?: return
                val id = breakpoint.long("id")?.toString() ?: return
                breakpointEvents[id] = breakpoint
                applyBreakpointEvent(id, breakpoint)
            }
            "continued" -> {
                lastStop.set(null)
                eventFlow.tryEmit(
                    BackendExecutionEvent(
                        id, process, BackendExecutionEventKind.RESUMED, StopScope.THREAD,
                        emptySet(), null, null, epoch.get(), null, System.nanoTime(),
                    ),
                )
            }
            "exited", "terminated" -> {
                eventFlow.tryEmit(
                    BackendExecutionEvent(
                        id, process, BackendExecutionEventKind.PROCESS_EXITED, StopScope.PROCESS,
                        emptySet(), null, null, epoch.get(), null, System.nanoTime(),
                    ),
                )
            }
        }
    }

    private fun send(
        command: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        timeoutSeconds: Long = 30,
    ): JsonObject {
        val client = dap ?: error("Flutter debug adapter is not running")
        return try {
            client.request(command, arguments, timeoutSeconds)
        } catch (failure: Throwable) {
            val detail = adapterLog
                ?.let { log -> runCatching { Files.readString(log).takeLast(1200) }.getOrNull() }
                .orEmpty()
            throw IllegalStateException(
                "DAP $command failed: ${failure::class.simpleName}: ${failure.message}${if (detail.isBlank()) "" else " ($detail)"}",
                failure,
            )
        }
    }

    private fun sourcePath(value: String): Path? {
        var path = value
        if (path.startsWith("file:", ignoreCase = true)) {
            path = runCatching { java.net.URI(path).path }.getOrNull() ?: return null
        }
        if (path.matches(Regex("^/[A-Za-z]:/.*"))) path = path.substring(1)
        return runCatching { Path.of(path) }.getOrNull()
    }

    private companion object {
        val DART_READ_ONLY =
            Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:(?:\.[A-Za-z_][A-Za-z0-9_]*)|(?:\[\d+]))*$""")
    }

    private data class LastStop(val threadId: Long?, val reason: StopReason)
    private data class BreakpointEntry(
        val source: Path? = null,
        val line: Int? = null,
        val verified: Boolean = false,
        val message: String? = null,
    )

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.arr(name: String): List<JsonObject> =
        (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
