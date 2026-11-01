package dev.admcp.debug.lldb

import dev.admcp.core.model.BackendExecutionEvent
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BreakpointRequest
import dev.admcp.core.model.BreakpointResult
import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.EvaluationRequest
import dev.admcp.core.model.EvaluationResult
import dev.admcp.core.model.FrameSnapshot
import dev.admcp.core.model.ModuleSnapshot
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.RegisterValue
import dev.admcp.core.model.ResumeRequest
import dev.admcp.core.model.SourceLocation
import dev.admcp.core.model.StepKind
import dev.admcp.core.model.StepRequest
import dev.admcp.core.model.StopEpoch
import dev.admcp.core.model.StopReason
import dev.admcp.core.model.StopScope
import dev.admcp.core.model.ThreadSnapshot
import dev.admcp.core.model.ThreadState
import dev.admcp.core.model.VariableSnapshot
import dev.admcp.debug.api.DebugBackend
import dev.admcp.debug.dap.DapClient
import dev.admcp.device.adb.AdbClient
import dev.admcp.device.adb.AdbCommandRunner
import dev.admcp.device.adb.ForwardHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Minimal LLDB DAP backend for native Android processes.
 *
 * The adapter owns the remote lldb-server lifecycle and speaks DAP to a
 * separately installed lldb-dap executable. It deliberately keeps expression
 * evaluation disabled; registers and bounded memory reads use dedicated DAP
 * requests or fixed expressions.
 */
class LldbBackend(
    private val dapPath: Path,
    private val serverPath: Path,
    private val adb: AdbClient,
    private val runner: AdbCommandRunner,
    private val adbExecutable: Path,
    private val symbolFile: Path? = null,
    private val targetAbi: String? = null,
    private val hostSourceRoot: Path? = null,
    private val remoteSourceRoot: String? = null,
) : DebugBackend {
    override val id: BackendId = BackendId("LLDB")
    override val lastStopReason: StopReason? get() = lastStop.get()?.reason

    private val eventFlow = MutableSharedFlow<BackendExecutionEvent>(extraBufferCapacity = 128)
    override val events: Flow<BackendExecutionEvent> = eventFlow
    private val epoch = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = ReentrantLock()
    private val stopLatch = AtomicReference(CountDownLatch(1))
    private val lastStop = AtomicReference<LastStop?>(null)
    private val breakpoints = mutableMapOf<String, BreakpointEntry>()
    private val autoResumes = AtomicLong(0)
    private val pauseRequested = AtomicBoolean(false)
    @Volatile private var configured = false

    private var processRef: ProcessRef? = null
    private var adapter: Process? = null
    private var serverProcess: Process? = null
    private var dap: DapClient? = null
    private var forward: ForwardHandle? = null
    private var tempDir: Path? = null
    private var serverLog: Path? = null
    private var dapLog: Path? = null
    private var moduleCommands: List<String> = emptyList()
    private var mapModules: List<ModuleSnapshot> = emptyList()

    override suspend fun attach(process: ProcessRef) {
        require(Files.isRegularFile(dapPath)) { "lldb-dap is missing: $dapPath" }
        require(Files.isRegularFile(serverPath)) { "lldb-server is missing: $serverPath" }
        processRef = process
        closed.set(false)
        configured = false
        autoResumes.set(0)
        pauseRequested.set(false)
        lastStop.set(null)
        stopLatch.set(CountDownLatch(1))
        try {
            prepareRemoteServer(process)
            prepareModules(process)
            startAdapter()
            initializeDap()
            attachDap(process)
            // Wait for the attach stop; only force a pause when the adapter
            // has not reported one. The command-interpreter pause path can
            // inject SIGBUS, so avoid it when the target is already stopped.
            if (!stopLatch.get().await(2, TimeUnit.SECONDS)) {
                pause(process, null)
            }
            stopLatch.get().await(10, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            runCatching { detach() }
            throw failure
        }
    }

    override suspend fun detach() {
        closed.set(true)
        configured = false
        lifecycleLock.withLock {
            runCatching {
                // Detach the target before closing the DAP/server. lldb-server
                // in gdbserver mode can terminate a target that is left stopped
                // when the DAP client closes. Stop the target first, suppress
                // pending stop signals so detach does not forward Android's
                // internal SIGBUS/SIGSEGV, then detach so the app keeps running.
                runCatching {
                    dap?.request(
                        "evaluate",
                        buildJsonObject {
                            put("expression", "`process interrupt")
                            put("context", "repl")
                        },
                        timeoutSeconds = 5,
                    )
                }
                Thread.sleep(300)
                for (signal in listOf("SIGSEGV", "SIGBUS", "SIGILL", "SIGFPE")) {
                    runCatching {
                        dap?.request(
                            "evaluate",
                            buildJsonObject {
                                put("expression", "`process handle $signal -s false -p false -n false")
                                put("context", "repl")
                            },
                            timeoutSeconds = 5,
                        )
                    }
                }
                val detached = runCatching {
                    dap?.request(
                        "evaluate",
                        buildJsonObject {
                            put("expression", "`process detach")
                            put("context", "repl")
                        },
                        timeoutSeconds = 5,
                    )
                }.isSuccess
                if (!detached) {
                    dap?.request("continue", buildJsonObject { put("singleThread", false) })
                }
                Thread.sleep(300)
                dap?.request("disconnect", buildJsonObject { put("terminateDebuggee", false) })
            }
        }
        runCatching { dap?.close() }
        dap = null
        adapter?.let { process ->
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        adapter = null
        serverProcess?.let { process ->
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        serverProcess = null
        forward?.let { handle -> runCatching { adb.removeForward(handle) } }
        forward = null
        val device = processRef?.deviceId
        val packageName = processRef?.packageName
        val targetPid = processRef?.pid?.value
        if (device != null && !packageName.isNullOrBlank()) {
            runCatching {
                adb.shell(
                    device,
                    listOf("run-as", packageName, "pkill", "-9", "-f", "admcp-lldb-server"),
                    Duration.ofSeconds(5),
                )
            }
        }
        if (device != null && targetPid != null) {
            // Do not let the next backend attach until lldb-server has actually
            // released ptrace. Otherwise JDWP can race the still-traced target.
            for (attempt in 0 until 30) {
                val status = runCatching {
                    adb.shell(
                        device,
                        listOf("cat", "/proc/$targetPid/status"),
                        Duration.ofSeconds(2),
                    ).stdoutText
                }.getOrNull()
                val tracer = status
                    ?.lineSequence()
                    ?.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull()
                if (status == null || tracer == 0) break
                Thread.sleep(100)
            }
        }
        moduleCommands = emptyList()
        mapModules = emptyList()
        tempDir?.let { directory -> runCatching { directory.toFile().deleteRecursively() } }
        tempDir = null
        serverLog?.let { log -> runCatching { Files.deleteIfExists(log) } }
        serverLog = null
        dapLog?.let { log -> runCatching { Files.deleteIfExists(log) } }
        dapLog = null
        processRef = null
        lastStop.set(null)
    }

    override suspend fun threads(process: ProcessRef): List<ThreadSnapshot> = withContext(Dispatchers.IO) {
        val body = send("threads")
        body.arr("threads").map { item ->
            val threadId = item.long("id") ?: return@map null
            val stop = lastStop.get()
            ThreadSnapshot(
                id = threadId,
                name = item.str("name") ?: "thread-$threadId",
                state = if (stop != null && (stop.threadId == threadId || stop.allThreads)) ThreadState.SUSPENDED else ThreadState.UNKNOWN,
                suspended = stop != null && (stop.threadId == threadId || stop.allThreads),
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
        dumpDebug("frames", body)
        body.arr("stackFrames").mapNotNull { item ->
            val frameId = item.long("id") ?: return@mapNotNull null
            val source = item["source"]?.let { it as? JsonObject }
            val line = (item.long("line") ?: source?.long("line"))?.toInt()?.takeIf { it > 0 }
            val column = (item.long("column") ?: source?.long("column"))?.toInt()?.takeIf { it > 0 }
            val path = (source?.str("path") ?: source?.str("name"))?.let(::localPath)
            val location = if (path != null && line != null) {
                SourceLocation(path, line, column)
            } else {
                null
            }
            FrameSnapshot(
                handle = thread.copy(opaqueId = frameId.toString()),
                name = item.str("name") ?: "<unknown>",
                location = location,
                module = item.str("moduleId") ?: source?.str("name"),
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
                    childrenCount = variable.long("variablesReference")
                        ?.takeIf { it > 0 }
                        ?.let { 1 },
                )
            }
        }
        result
    }

    override suspend fun setBreakpoint(process: ProcessRef, request: BreakpointRequest): BreakpointResult =
        withContext(Dispatchers.IO) {
            val function = request.function
            if (!function.isNullOrBlank()) {
                val specs = breakpoints.values.filter { it.function != null } +
                    BreakpointEntry(function = function, condition = request.condition)
                val body = send(
                    "setFunctionBreakpoints",
                    buildJsonObject {
                        put("breakpoints", buildJsonArray {
                            specs.forEach { spec ->
                                add(buildJsonObject {
                                    put("name", spec.function)
                                    spec.condition?.let { put("condition", it) }
                                })
                            }
                        })
                    },
                )
                val returned = body.arr("breakpoints")
                breakpoints.entries.removeIf { it.value.function != null }
                returned.forEachIndexed { index, breakpoint ->
                    val spec = specs.getOrNull(index) ?: return@forEachIndexed
                    val id = breakpoint.long("id")?.toString() ?: "function:${spec.function}:$index"
                    breakpoints[id] = spec
                }
                val breakpoint = returned.getOrNull(specs.lastIndex)
                    ?: return@withContext BreakpointResult(function, false, emptyList(), "adapter returned no breakpoint")
                val id = breakpoint.long("id")?.toString() ?: "function:$function"
                BreakpointResult(id, breakpoint.bool("verified") ?: false, emptyList(), breakpoint.str("message"))
            } else {
                val source = request.source ?: return@withContext BreakpointResult(
                    "invalid-breakpoint", false, emptyList(), "source or function is required",
                )
                val line = request.line ?: return@withContext BreakpointResult(
                    "invalid-breakpoint", false, emptyList(), "line is required for a source breakpoint",
                )
                val path = Path.of(source).toAbsolutePath().normalize()
                val specs = breakpoints.values.filter { it.source == path } +
                    BreakpointEntry(source = path, line = line, condition = request.condition)
                val body = send(
                    "setBreakpoints",
                    buildJsonObject {
                        put("source", buildJsonObject { put("path", path.toString()) })
                        put("breakpoints", buildJsonArray {
                            specs.forEach { spec ->
                                add(buildJsonObject {
                                    put("line", spec.line)
                                    spec.condition?.let { put("condition", it) }
                                })
                            }
                        })
                    },
                )
                val returned = body.arr("breakpoints")
                breakpoints.entries.removeIf { it.value.source == path }
                returned.forEachIndexed { index, breakpoint ->
                    val spec = specs.getOrNull(index) ?: return@forEachIndexed
                    val id = breakpoint.long("id")?.toString() ?: "$path:${spec.line}:$index"
                    breakpoints[id] = spec
                }
                val breakpoint = returned.getOrNull(specs.lastIndex)
                    ?: return@withContext BreakpointResult("$path:$line", false, emptyList(), "adapter returned no breakpoint")
                val id = breakpoint.long("id")?.toString() ?: "$path:$line"
                val resolvedLine = breakpoint.long("line")?.toInt() ?: line
                BreakpointResult(
                    id,
                    breakpoint.bool("verified") ?: false,
                    listOf(SourceLocation(path, resolvedLine, null)),
                    breakpoint.str("message"),
                )
            }
        }

    override suspend fun listBreakpoints(process: ProcessRef): List<BreakpointResult> =
        breakpoints.entries.map { (id, entry) ->
            val location = entry.source?.let { source ->
                SourceLocation(source, entry.line ?: 1, null)
            }
            BreakpointResult(id, true, listOfNotNull(location), null)
        }

    override suspend fun removeBreakpoint(id: String) {
        val entry = breakpoints.remove(id) ?: return
        withContext(Dispatchers.IO) {
            if (entry.function != null) {
                val remaining = breakpoints.values.filter { it.function != null }
                val body = send(
                    "setFunctionBreakpoints",
                    buildJsonObject {
                        put("breakpoints", buildJsonArray {
                            remaining.forEach { spec ->
                                add(buildJsonObject {
                                    put("name", spec.function)
                                    spec.condition?.let { put("condition", it) }
                                })
                            }
                        })
                    },
                )
                breakpoints.entries.removeIf { it.value.function != null }
                body.arr("breakpoints").forEachIndexed { index, breakpoint ->
                    val spec = remaining.getOrNull(index) ?: return@forEachIndexed
                    val newId = breakpoint.long("id")?.toString() ?: "function:${spec.function}:$index"
                    breakpoints[newId] = spec
                }
            } else if (entry.source != null) {
                val remaining = breakpoints.values.filter { it.source == entry.source }
                val body = send(
                    "setBreakpoints",
                    buildJsonObject {
                        put("source", buildJsonObject { put("path", entry.source.toString()) })
                        put("breakpoints", buildJsonArray {
                            remaining.forEach { spec ->
                                add(buildJsonObject {
                                    put("line", spec.line)
                                    spec.condition?.let { put("condition", it) }
                                })
                            }
                        })
                    },
                )
                breakpoints.entries.removeIf { it.value.source == entry.source }
                body.arr("breakpoints").forEachIndexed { index, breakpoint ->
                    val spec = remaining.getOrNull(index) ?: return@forEachIndexed
                    val newId = breakpoint.long("id")?.toString() ?: "${entry.source}:${spec.line}:$index"
                    breakpoints[newId] = spec
                }
            }
        }
    }

    override suspend fun resume(request: ResumeRequest, process: ProcessRef) {
        withContext(Dispatchers.IO) {
            // An attach stop event can arrive after the resume and leave the
            // process stopped at a signal. Retry only for non-user stops; a
            // breakpoint, step or explicit pause must remain stopped.
            repeat(3) { attempt ->
                lastStop.set(null)
                autoResumes.set(0)
                pauseRequested.set(false)
                dap?.request(
                    "continue",
                    buildJsonObject {
                        request.threadId?.let { put("threadId", it) }
                        put("singleThread", false)
                    },
                )
                Thread.sleep(350)
                val stop = lastStop.get() ?: return@withContext
                if (stop.reason == StopReason.BREAKPOINT ||
                    stop.reason == StopReason.STEP ||
                    stop.reason == StopReason.PAUSE_REQUEST
                ) {
                    return@withContext
                }
                if (attempt == 2) return@withContext
            }
        }
    }

    override suspend fun pause(process: ProcessRef, threadId: Long?) {
        withContext(Dispatchers.IO) {
            pauseRequested.set(true)
            runCatching {
                dap?.request(
                    "pause",
                    buildJsonObject { threadId?.let { put("threadId", it) } },
                )
            }
            // lldb-server in gdbserver mode may ignore async DAP pause; the
            // command interpreter path is a fallback only when no stop arrived,
            // because it can inject a SIGBUS that is later delivered on detach.
            for (attempt in 0 until 10) {
                if (lastStop.get() != null) break
                Thread.sleep(100)
            }
            if (lastStop.get() == null) {
                runCatching {
                    dap?.request(
                        "evaluate",
                        buildJsonObject {
                            put("expression", "`process interrupt")
                            put("context", "repl")
                        },
                    )
                }
                Thread.sleep(250)
            }
            val current = lastStop.get()
            if (current != null) {
                lastStop.set(current.copy(reason = StopReason.PAUSE_REQUEST, allThreads = true))
                pauseRequested.set(false)
            } else {
                val selected = threadId ?: runCatching {
                    send("threads").arr("threads").firstOrNull()?.long("id")
                }.getOrNull()
                if (selected != null) {
                    val newEpoch = epoch.incrementAndGet()
                    lastStop.set(LastStop(selected, StopReason.PAUSE_REQUEST, true))
                    processRef?.let { process ->
                        eventFlow.tryEmit(
                            BackendExecutionEvent(
                                id,
                                process,
                                BackendExecutionEventKind.STOPPED,
                                StopScope.THREAD,
                                setOf(selected),
                                StopReason.PAUSE_REQUEST,
                                "lldb-pause-$selected-$newEpoch",
                                newEpoch,
                                null,
                                System.nanoTime(),
                            ),
                        )
                    }
                    stopLatch.get().countDown()
                    pauseRequested.set(false)
                } else {
                    pauseRequested.set(false)
                }
            }
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
        if (!NATIVE_READ_ONLY.matches(expression)) {
            return@withContext EvaluationResult(
                null,
                false,
                "expression is not a read-only native register, local or field read",
                request.frame,
                request.operation,
            )
        }
        val frameId = request.frame.opaqueId.toLongOrNull() ?: topFrameId(request.frame.threadId)
        runCatching {
            val body = send(
                "evaluate",
                buildJsonObject {
                    put("expression", expression)
                    frameId?.let { put("frameId", it) }
                    put("context", "watch")
                },
            )
            val value = body.str("result")
                ?: error(body.str("message") ?: "the debugger returned no value")
            EvaluationResult(
                VariableSnapshot(expression, body.str("type"), value, null, body.long("variablesReference")?.takeIf { it > 0 }?.let { 1 }),
                false,
                null,
                request.frame,
                request.operation,
            )
        }.getOrElse { failure ->
            EvaluationResult(
                null,
                false,
                failure.message ?: failure::class.simpleName ?: "LLDB evaluation failed",
                request.frame,
                request.operation,
            )
        }
    }

    override suspend fun registers(thread: DebugHandle): List<RegisterValue> = withContext(Dispatchers.IO) {
        val frameId = thread.opaqueId.toLongOrNull() ?: topFrameId(thread.threadId)
        if (frameId == null) return@withContext emptyList()
        val names = if (isArm64()) {
            (0..28).map { "x$it" } + listOf("fp", "lr", "sp", "pc", "cpsr")
        } else {
            listOf("rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp", "rip")
        }
        names.mapNotNull { name ->
            val body = runCatching {
                send(
                    "evaluate",
                    buildJsonObject {
                        put("expression", "\$$name")
                        put("frameId", frameId)
                        put("context", "repl")
                    },
                )
            }.getOrNull() ?: return@mapNotNull null
            val value = body.str("result") ?: return@mapNotNull null
            RegisterValue(name, value, if (name == "cpsr") 32 else 64)
        }
    }

    override suspend fun modules(process: ProcessRef, includeBuildIds: Boolean): List<ModuleSnapshot> =
        withContext(Dispatchers.IO) {
            val adapters = runCatching {
                send(
                    "modules",
                    buildJsonObject {
                        put("startModule", 0)
                        put("moduleCount", 1000)
                    },
                ).arr("modules").map { item ->
                    ModuleSnapshot(
                        name = item.str("name") ?: item.str("path") ?: "<module>",
                        path = item.str("path"),
                        buildId = if (includeBuildIds) item.str("buildId") else null,
                        loadAddress = parseAddressText(item.str("addressRange") ?: item.str("address")),
                    )
                }
            }.getOrDefault(emptyList())
            val merged = linkedMapOf<String, ModuleSnapshot>()
            mapModules.forEach { module ->
                merged[module.path ?: module.name] = module.copy(buildId = null)
            }
            adapters.forEach { module ->
                val key = module.path ?: module.name
                val known = merged[key]
                merged[key] = module.copy(
                    loadAddress = module.loadAddress ?: known?.loadAddress,
                    buildId = if (includeBuildIds) module.buildId ?: known?.buildId else null,
                )
            }
            merged.values.toList()
        }

    override suspend fun readMemory(process: ProcessRef, address: String, size: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val body = send(
                "readMemory",
                buildJsonObject {
                    put("memoryReference", normalizeAddress(address))
                    put("offset", 0)
                    put("count", size.coerceIn(1, 65536))
                },
            )
            body.str("data")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        }

    private suspend fun prepareRemoteServer(process: ProcessRef) {
        val device = process.deviceId
        val packageName = process.packageName?.takeIf { it.isNotBlank() }
            ?: error("native debugging requires a package name for run-as")
        val staged = "/data/local/tmp/admcp-lldb-server"
        val remote = "files/admcp-lldb-server"
        val push = runner.run(
            listOf("-s", device.value, "push", serverPath.toString(), staged),
            Duration.ofSeconds(60),
        )
        check(push.exitCode == 0) { "adb push lldb-server failed: ${push.stderrText}" }
        val copy = adb.shell(
            device,
            listOf("run-as", packageName, "cp", staged, remote),
            Duration.ofSeconds(60),
        )
        check(copy.exitCode == 0) {
            "run-as could not install lldb-server; the app must be debuggable: ${copy.stderrText}"
        }
        val chmod = adb.shell(
            device,
            listOf("run-as", packageName, "chmod", "700", remote),
            Duration.ofSeconds(10),
        )
        check(chmod.exitCode == 0) { "chmod lldb-server failed: ${chmod.stderrText}" }
        val port = 30000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(20000)
        forward = adb.forward(device, "tcp:0", "tcp:$port")
        val localPort = forward?.local?.substringAfterLast(':')?.toIntOrNull()
            ?: error("adb forward returned no local port")
        val command = ProcessBuilder(
            adbExecutable.toString(),
            "-s", device.value,
            "shell", "run-as", packageName,
            "./$remote", "gdbserver", "--attach", process.pid.value.toString(), ":$port",
        )
        val log = Files.createTempFile("admcp-lldb-server", ".log")
        serverLog = log
        command.redirectErrorStream(true)
        command.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        serverProcess = command.start()
        forward = forward?.copy(local = "tcp:$localPort")
        Thread.sleep(3000)
        if (serverProcess?.isAlive != true) {
            val text = runCatching { Files.readString(log).takeLast(1000) }.getOrDefault("")
            error("lldb-server exited before the DAP attach${if (text.isBlank()) "" else ": $text"}")
        }
    }

    private suspend fun prepareModules(process: ProcessRef) {
        val device = process.deviceId
        val packageName = process.packageName?.takeIf { it.isNotBlank() } ?: return
        val maps = adb.shell(
            device,
            listOf("run-as", packageName, "cat", "/proc/${process.pid.value}/maps"),
            Duration.ofSeconds(15),
        )
        if (maps.exitCode != 0) return
        val entries = linkedMapOf<String, ULong>()
        val allEntries = linkedMapOf<String, ULong>()
        maps.stdoutText.lineSequence().forEach { line ->
            val match = MAPS_LINE.matchEntire(line.trim()) ?: return@forEach
            val address = match.groupValues[1].toULongOrNull(16) ?: return@forEach
            val path = match.groupValues[2].trim().removeSuffix(" (deleted)")
            if (!path.endsWith(".so")) return@forEach
            allEntries.putIfAbsent(path, address)
            val appOwned = path.startsWith("/data/app/") ||
                path.startsWith("/data/data/$packageName/") ||
                path.startsWith("/data/user/0/$packageName/") ||
                path.startsWith("/data/user_de/0/$packageName/")
            if (!appOwned) return@forEach
            entries.putIfAbsent(path, address)
        }
        mapModules = allEntries.entries.take(1000).map { (path, address) ->
            ModuleSnapshot(path.substringAfterLast('/'), path, null, address)
        }
        if (entries.isEmpty()) return
        val selected = if (symbolFile != null) {
            entries.filterKeys { it.substringAfterLast('/') == symbolFile.fileName.toString() }
        } else {
            entries
        }
        if (selected.isEmpty()) return
        val directory = Files.createTempDirectory("admcp-lldb-")
        tempDir = directory
        val commands = mutableListOf<String>()
        selected.entries.take(64).forEachIndexed { index, (remote, address) ->
            val local = directory.resolve("$index-${remote.substringAfterLast('/')}")
            val pull = runner.run(
                listOf("-s", device.value, "pull", remote, local.toString()),
                Duration.ofSeconds(60),
            )
            if (pull.exitCode != 0) return@forEachIndexed
            val path = local.toString().replace('\\', '/')
            commands += "target modules add \"$path\""
            commands += "target modules load --file \"$path\" --slide 0x${address.toString(16)}"
        }
        moduleCommands = commands
    }

    private fun startAdapter() {
        val pb = ProcessBuilder(dapPath.toString())
        pb.directory(dapPath.parent.toFile())
        val root = dapPath.parent?.parent
        if (root != null) {
            val pythonHome = root.resolve("python310")
            val sitePackages = root.resolve("lib/site-packages")
            if (Files.isDirectory(pythonHome)) {
                pb.environment()["PYTHONHOME"] = pythonHome.toString()
                pb.environment()["PATH"] = "$pythonHome;${pb.environment()["PATH"] ?: ""}"
                pb.environment()["PATH"] = "${dapPath.parent};${pb.environment()["PATH"] ?: ""}"
            }
            if (Files.isDirectory(sitePackages)) pb.environment()["PYTHONPATH"] = sitePackages.toString()
        }
        val log = Files.createTempFile("admcp-lldb-dap", ".log")
        dapLog = log
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
        val process = pb.start()
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
                put("adapterID", "lldb")
                put("pathFormat", "path")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("supportsVariableType", true)
                put("supportsMemoryReferences", true)
                put("supportsRunInTerminalRequest", false)
            },
        )
    }

    private fun attachDap(process: ProcessRef) {
        val localPort = forward?.local?.substringAfterLast(':')?.toIntOrNull()
            ?: error("lldb forward is missing")
        val commands = buildList {
            add("gdb-remote 127.0.0.1:$localPort")
            add("process handle SIGSEGV -s false -p true -n false")
            add("process handle SIGBUS -s false -p true -n false")
            add("process handle SIGILL -s false -p true -n false")
            add("process handle SIGFPE -s false -p true -n false")
            addAll(moduleCommands)
        }
        val arguments = buildJsonObject {
            put("pid", process.pid.value)
            put("attachCommands", buildJsonArray {
                commands.forEach { add(JsonPrimitive(it)) }
            })
            if (hostSourceRoot != null && remoteSourceRoot != null) {
                put("sourceMap", buildJsonObject { put(remoteSourceRoot, hostSourceRoot.toString()) })
            }
        }
        dapLog?.let { log -> runCatching { Files.writeString(log, "attach commands:\n" + commands.joinToString("\n") + "\n") } }
        send("attach", arguments)
        send("configurationDone", buildJsonObject { })
        configured = true
    }

    private fun onDapEvent(message: JsonObject) {
        val event = message.str("event") ?: return
        val body = message["body"] as? JsonObject ?: JsonObject(emptyMap())
        val process = processRef ?: return
        when (event) {
            "stopped" -> {
                val threadId = body.long("threadId")
                val reason = when (body.str("reason")) {
                    "breakpoint" -> StopReason.BREAKPOINT
                    "step" -> StopReason.STEP
                    "exception" -> StopReason.EXCEPTION
                    "entry" -> StopReason.ENTRY
                    "pause" -> StopReason.PAUSE_REQUEST
                    "signal" -> StopReason.SIGNAL
                    else -> StopReason.UNKNOWN
                }
                val pauseNow = pauseRequested.getAndSet(false)
                val effectiveReason = if (pauseNow &&
                    (reason == StopReason.EXCEPTION || reason == StopReason.SIGNAL || reason == StopReason.UNKNOWN)
                ) {
                    StopReason.PAUSE_REQUEST
                } else {
                    reason
                }
                val newEpoch = epoch.incrementAndGet()
                val autoSignal = configured && threadId != null && !pauseNow &&
                    (effectiveReason == StopReason.EXCEPTION || effectiveReason == StopReason.SIGNAL) &&
                    autoResumes.get() < 12
                if (autoSignal) {
                    autoResumes.incrementAndGet()
                    dapLog?.let { log ->
                        runCatching {
                            Files.writeString(
                                log,
                                "auto-continue ${body.str("reason")} ${body.str("description").orEmpty()} thread=$threadId\n",
                                java.nio.file.StandardOpenOption.APPEND,
                            )
                        }
                    }
                    Thread({
                        lifecycleLock.withLock {
                            runCatching {
                                if (closed.get() || pauseRequested.get()) return@runCatching
                                dap?.request(
                                    "continue",
                                    buildJsonObject {
                                        put("threadId", threadId)
                                        put("singleThread", false)
                                    },
                                )
                            }
                        }
                    }, "lldb-auto-continue").apply { isDaemon = true; start() }
                    return
                }
                autoResumes.set(0)
                lastStop.set(
                    LastStop(
                        threadId,
                        effectiveReason,
                        (body.bool("allThreadsStopped") ?: false) || effectiveReason == StopReason.PAUSE_REQUEST,
                    ),
                )
                eventFlow.tryEmit(
                    BackendExecutionEvent(
                        id,
                        process,
                        BackendExecutionEventKind.STOPPED,
                        StopScope.THREAD,
                        setOfNotNull(threadId),
                        effectiveReason,
                        "lldb-$threadId-$newEpoch",
                        newEpoch,
                        null,
                        System.nanoTime(),
                    ),
                )
                stopLatch.get().countDown()
            }
            "continued" -> {
                if (pauseRequested.get()) return
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

    private fun dumpDebug(name: String, body: JsonObject) {
        if (System.getenv("ADMCP_DAP_DEBUG") != "1") return
        runCatching {
            val path = Path.of(System.getProperty("java.io.tmpdir"), "admcp-dap-$name.json")
            Files.writeString(path, body.toString())
        }
    }

    private fun send(command: String, arguments: JsonObject = JsonObject(emptyMap())): JsonObject {
        val client = dap ?: error("lldb-dap is not running")
        return try {
            client.request(command, arguments)
        } catch (failure: Throwable) {
            val serverTail = serverLog
                ?.let { log -> runCatching { Files.readString(log).takeLast(1000) }.getOrNull() }
                .orEmpty()
            val dapTail = dapLog
                ?.let { log -> runCatching { Files.readString(log).takeLast(1000) }.getOrNull() }
                .orEmpty()
            val detail = listOf(serverTail, dapTail).filter { it.isNotBlank() }.joinToString(" ")
            throw IllegalStateException(
                "DAP $command failed: ${failure.message}${if (detail.isBlank()) "" else " ($detail)"}",
                failure,
            )
        }
    }

    private fun topFrameId(threadId: Long?): Long? {
        val id = threadId ?: return null
        return runCatching {
            send(
                "stackTrace",
                buildJsonObject {
                    put("threadId", id)
                    put("startFrame", 0)
                    put("levels", 1)
                },
            ).arr("stackFrames").firstOrNull()?.long("id")
        }.getOrNull()
    }

    private fun isArm64(): Boolean =
        targetAbi == "arm64-v8a" ||
            symbolFile?.toString()?.replace('\\', '/')?.contains("arm64") == true ||
            symbolFile?.toString()?.replace('\\', '/')?.contains("aarch64") == true

    private fun normalizeAddress(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("0x", ignoreCase = true)) return trimmed
        if (trimmed.isNotEmpty() && trimmed.all(Char::isDigit)) {
            return "0x" + trimmed.toULong().toString(16)
        }
        return if (trimmed.matches(Regex("[0-9a-fA-F]+"))) "0x$trimmed" else trimmed
    }

    private fun parseAddressText(value: String?): ULong? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        if (trimmed.all(Char::isDigit)) return trimmed.toULongOrNull()
        return trimmed.removePrefix("0x").removePrefix("0X").toULongOrNull(16)
    }

    private companion object {
        val MAPS_LINE = Regex("^([0-9a-fA-F]+)-[0-9a-fA-F]+\\s+\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+(.+)$")
        val NATIVE_READ_ONLY =
            Regex("""^(?:\$?[A-Za-z_][A-Za-z0-9_]*)(?:(?:\.[A-Za-z_][A-Za-z0-9_]*)|(?:\[\d+]))*$""")
    }

    private fun localPath(sourcePath: String): Path? {
        var value = sourcePath
        if (hostSourceRoot != null && remoteSourceRoot != null && value.startsWith(remoteSourceRoot)) {
            value = hostSourceRoot.resolve(value.removePrefix(remoteSourceRoot).trimStart('/', '\\')).toString()
        }
        return runCatching { Path.of(value) }.getOrNull()
    }

    private data class LastStop(val threadId: Long?, val reason: StopReason, val allThreads: Boolean = false)
    private data class BreakpointEntry(
        val source: Path? = null,
        val line: Int? = null,
        val function: String? = null,
        val condition: String? = null,
    )

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.arr(name: String): List<JsonObject> =
        (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun String.toULongOrNull(radix: Int): ULong? =
        runCatching {
            java.lang.Long.parseUnsignedLong(removePrefix("0x").removePrefix("0X"), radix).toULong()
        }.getOrNull()
}
