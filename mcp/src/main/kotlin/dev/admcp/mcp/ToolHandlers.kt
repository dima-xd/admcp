package dev.admcp.mcp

import dev.admcp.androidcli.DefaultAndroidCliClient
import dev.admcp.androidcli.DeviceInventory
import dev.admcp.androidcli.DeviceRoutingPolicy
import dev.admcp.androidcli.SupervisorAndroidCliRunner
import dev.admcp.app.lifecycle.BuildExecutor
import dev.admcp.app.lifecycle.PackageInstaller
import dev.admcp.core.artifacts.ArtifactStore
import dev.admcp.core.model.Availability
import dev.admcp.core.model.BackendExecutionEventKind
import dev.admcp.core.model.BackendId
import dev.admcp.core.model.BuildArtifact
import dev.admcp.core.model.BuildOptions
import dev.admcp.core.model.BuildTarget
import dev.admcp.core.model.CapabilitySubject
import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.EvaluationRequest
import dev.admcp.core.model.ExpressionLanguage
import dev.admcp.core.model.ExpressionOperation
import dev.admcp.core.model.ExpressionPolicy
import dev.admcp.core.model.InspectionContext
import dev.admcp.core.model.InstallResult
import dev.admcp.core.model.ProjectKind
import dev.admcp.core.model.SessionId
import dev.admcp.core.model.StepKind
import dev.admcp.core.model.StopEpoch
import dev.admcp.core.model.ToolKind
import dev.admcp.core.model.ToolchainRequest
import dev.admcp.core.model.UiAction
import dev.admcp.core.model.UiActionKind
import dev.admcp.core.model.UiSelector
import dev.admcp.core.model.WorkspaceOperation
import dev.admcp.core.session.SessionManager
import dev.admcp.core.process.collectStdout
import dev.admcp.core.process.collectStderr
import dev.admcp.core.model.ArtifactKind
import dev.admcp.core.model.ClockDomain
import dev.admcp.core.model.EventId
import dev.admcp.core.model.OperationId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.VariableSnapshot
import dev.admcp.debug.expression.ExpressionPolicyEngine
import dev.admcp.events.EventEnvelope
import dev.admcp.events.EventQuery
import dev.admcp.events.EventSeverity
import dev.admcp.events.EventSource
import dev.admcp.events.EventType
import dev.admcp.events.SqliteEventStore
import dev.admcp.profiler.PerfettoTraceAnalyzer
import dev.admcp.profiler.TraceAnalysis
import dev.admcp.device.adb.AdbClient
import dev.admcp.device.adb.AdbCommandRunner
import dev.admcp.device.adb.AdbDeviceManager
import dev.admcp.device.adb.DefaultAdbClient
import dev.admcp.device.adb.ProcessInstances
import dev.admcp.device.adb.SupervisorAdbRunner
import dev.admcp.debug.api.DebugBackend
import dev.admcp.debug.coordinator.ExecutionCoordinator
import dev.admcp.debug.jdi.JdiBackend
import dev.admcp.debug.jdi.JdiConnectorProbe
import dev.admcp.debug.lldb.LldbBackend
import dev.admcp.debug.flutter.DartDapBackend
import dev.admcp.debug.flutter.FlutterResolver
import dev.admcp.diagnostics.DiagnosticsEngine
import dev.admcp.logcat.LogcatParser
import dev.admcp.logcat.LogcatCaptureConfig
import dev.admcp.logcat.LogcatCaptureSession
import dev.admcp.logcat.ProcessLogcatSource
import dev.admcp.profiler.ProfilePlanner
import dev.admcp.ui.UiActionPlanner
import dev.admcp.ui.UiSelectorResolver
import dev.admcp.ui.compose.HierarchyComposeInspector
import dev.admcp.ui.uiautomator.UiAdb
import dev.admcp.ui.uiautomator.UiautomatorBackend
import dev.admcp.workspace.ProjectDetector
import dev.admcp.workspace.Workspace
import dev.admcp.workspace.WorkspacePathGuard
import dev.admcp.workspace.WorkspaceTrustPolicy
import dev.admcp.workspace.TrustApprovalVerifier
import dev.admcp.workspace.BuildEnvironmentPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.streams.toList

class AdmcpToolHandler(private val config: ServerConfig) : ToolDispatcher {
    private val runner: AdbCommandRunner by lazy {
        SupervisorAdbRunner(resolveAdb() ?: Path.of("adb"))
    }
    private val adb: AdbClient by lazy { DefaultAdbClient(resolveAdb()?.toString() ?: "adb", runner) }
    private val devices = AdbDeviceManager(adb)
    private val artifacts = ArtifactStore(config.artifactRoot ?: Path.of(System.getProperty("user.home"), ".admcp", "artifacts"))
    private val sessions = SessionManager()
    private val coordinator = ExecutionCoordinator()
    private val workspaceDetector = ProjectDetector()
    private val trust by lazy { WorkspaceTrustPolicy(denyAllApprovals) }
    private val uiBackend = UiautomatorBackend(AdbUiAdapter(runner))
    private val uiResolver = UiSelectorResolver()
    private val uiPlanner = UiActionPlanner(uiResolver)
    private val composeInspector = HierarchyComposeInspector()
    private val profilePlanner = ProfilePlanner()
    private val diagnostics = DiagnosticsEngine(analyzerAvailable = true)
    private val traceAnalyzer = PerfettoTraceAnalyzer()
    private val expressionPolicy = ExpressionPolicyEngine()
    private val host = HostCommandExecutor()
    private val eventStore by lazy {
        SqliteEventStore(
            (config.artifactRoot ?: Path.of(System.getProperty("user.home"), ".admcp", "artifacts"))
                .resolve("events.db"),
        )
    }
    private val debugBackends = ConcurrentHashMap<SessionId, ConcurrentHashMap<BackendId, DebugBackend>>()
    private val debugProcesses = ConcurrentHashMap<SessionId, dev.admcp.core.model.ProcessRef>()
    private val uiConfirmations = ConcurrentHashMap<String, PendingUiConfirmation>()
    private val uiConfirmationHost: UiConfirmationHost = DesktopUiConfirmationHost()
    private val logcatSessions = ConcurrentHashMap<String, LogcatSession>()
    private val traceSessions = ConcurrentHashMap<String, Deferred<ToolOutcome>>()
    private val traceSessionOwners = ConcurrentHashMap<String, SessionId>()
    private val workspaces = ConcurrentHashMap<String, Path>()
    private val workspaceIds = ConcurrentHashMap<Path, String>()
    private val timelineLogKeys = ConcurrentHashMap<String, MutableSet<String>>()
    private val evaluationConfirmations = ConcurrentHashMap<String, PendingEvaluation>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var uiGeneration = 0L

    private fun backendFor(sessionId: SessionId, args: JsonObject): DebugBackend? {
        val attached = debugBackends[sessionId] ?: return null
        val requested = args.str("backendId")?.let { BackendId(it.uppercase()) }
        return if (requested != null) attached[requested] else attached.values.singleOrNull()
    }

    private val handlers: Map<String, suspend (JsonObject) -> ToolOutcome> = mapOf(
        "device_list" to ::deviceList,
        "device_inspect" to ::deviceInspect,
        "toolchain_probe" to ::toolchainProbe,
        "workspace_open" to ::workspaceOpen,
        "workspace_describe" to ::workspaceDescribe,
        "workspace_trust" to ::workspaceTrust,
        "workspace_revoke_trust" to ::workspaceRevokeTrust,
        "app_build" to ::appBuild,
        "app_install" to ::appInstall,
        "app_launch" to ::appLaunch,
        "session_start" to ::sessionStart,
        "session_status" to ::sessionStatus,
        "session_stop" to ::sessionStop,
        "ui_inspect" to ::uiInspect,
        "ui_find" to ::uiFind,
        "ui_act" to ::uiAct,
        "ui_screenshot" to ::uiScreenshot,
        "logcat_start" to ::logcatStart,
        "logcat_stop" to ::logcatStop,
        "logcat_query" to ::logcatQuery,
        "logcat_statistics" to ::logcatStatistics,
        "logcat_health" to ::logcatHealth,
        "debug_attach" to ::debugAttach,
        "debug_detach" to ::debugDetach,
        "debug_status" to ::debugStatus,
        "debug_breakpoint_add" to ::debugBreakpointAdd,
        "debug_breakpoints_list" to ::debugBreakpointsList,
        "debug_breakpoint_remove" to ::debugBreakpointRemove,
        "debug_pause" to ::debugPause,
        "debug_continue" to ::debugContinue,
        "debug_step" to ::debugStep,
        "debug_threads" to ::debugThreads,
        "debug_stack" to ::debugStack,
        "debug_variables" to ::debugVariables,
        "debug_evaluate" to ::debugEvaluate,
        "debug_modules" to ::debugModules,
        "debug_registers" to ::debugRegisters,
        "debug_memory_read" to ::debugMemoryRead,
        "profiler_systemtrace_start" to ::profileStart,
        "profiler_systemtrace_stop" to ::profileStop,
        "profiler_heapdump_capture" to ::heapDump,
        "performance_trace_analyze" to ::traceAnalyze,
        "preview_list" to ::previewList,
        "preview_render" to ::previewRender,
        "analyze_artifact" to ::analyzeArtifact,
        "diagnostics_timeline" to ::diagnosticsTimeline,
        "diagnostics_crashes" to ::diagnosticsCrashes,
        "diagnostics_analyze" to ::diagnosticsAnalyze,
        "artifact_get" to ::artifactGet,
        "signals_crashes" to ::signalsCrashes,
        "compose_inspect" to ::composeInspect,
    )

    override suspend fun call(name: String, arguments: JsonObject): ToolOutcome {
        val handler = handlers[name] ?: return error("TOOL_NOT_IMPLEMENTED", "tool $name has no v1 handler")
        return runCatching { handler(arguments) }.getOrElse { failure ->
            error("TOOL_FAILED", failure.message ?: failure::class.simpleName ?: "unknown error")
        }
    }

    internal fun handledToolNames(): Set<String> = handlers.keys

    private suspend fun deviceList(args: JsonObject): ToolOutcome {
        val includeOffline = args.bool("includeOffline") ?: false
        val list = adb.devices().filter { includeOffline || it.state == DeviceConnectionState.ONLINE }
        return ok(buildJsonObject {
            put("devices", buildJsonArray {
                list.forEach { device ->
                    add(buildJsonObject {
                        put("deviceId", device.id.value)
                        put("state", device.state.name)
                        put("model", device.model)
                        put("product", device.product)
                    })
                }
            })
        })
    }

    private suspend fun deviceInspect(args: JsonObject): ToolOutcome {
        val device = DeviceId(args.requireString("deviceId"))
        val snapshot = devices.inspect(device)
        val processes = if (args.bool("includeProcesses") != false) devices.processes(device) else emptyList()
        return ok(buildJsonObject {
            put("deviceId", snapshot.id.value)
            put("state", snapshot.state.name)
            put("model", snapshot.model)
            put("manufacturer", snapshot.manufacturer)
            put("apiLevel", snapshot.apiLevel)
            put("abis", JsonArray(snapshot.abis.map(::JsonPrimitive)))
            put("fingerprint", snapshot.fingerprint)
            snapshot.display?.let { display ->
                put("display", buildJsonObject {
                    put("width", display.width); put("height", display.height); put("density", display.density)
                })
            }
            put("processes", buildJsonArray {
                processes.forEach { process ->
                    add(buildJsonObject {
                        put("pid", process.ref.pid.value)
                        put("name", process.processName)
                        put("instanceId", process.ref.instanceId.value)
                    })
                }
            })
        })
    }

    private suspend fun toolchainProbe(args: JsonObject): ToolOutcome {
        val kinds = (args["requiredKinds"] ?: args["kinds"])?.jsonArray?.map { ToolKind.valueOf(it.jsonPrimitive.content.uppercase()) }
            ?: ToolKind.entries
        val probeRoot = workspaceRoot(args, required = false) ?: config.workspaceRoot
        val result = runBlocking {
            dev.admcp.toolchain.DefaultToolchainManager(
                dev.admcp.toolchain.ToolResolver(
                    dev.admcp.toolchain.ResolverEnvironment(
                        probeRoot,
                        System.getenv(),
                        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map(Path::of).orEmpty(),
                        System.getProperty("os.name"),
                        System.getProperty("os.arch"),
                    ),
                    object : dev.admcp.toolchain.ExecutableInspector {
                        override fun version(path: Path): String? = null
                        override fun sha256(path: Path): String? = null
                    },
                ),
                object : dev.admcp.toolchain.ExecutableInspector {
                    override fun version(path: Path): String? = null
                    override fun sha256(path: Path): String? = null
                },
            ).resolve(ToolchainRequest(probeRoot, kinds.toSet(), args.str("targetAbi")))
        }
        return ok(buildJsonObject {
            put("tools", buildJsonArray {
                result.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("kind", tool.kind.name)
                        put("executable", tool.executable.toString())
                        put("provenance", tool.provenance)
                        put("version", tool.version)
                    })
                }
            })
            put("errors", buildJsonArray { result.errors.forEach { error -> add(error.toJson()) } })
        })
    }

    private suspend fun workspaceOpen(args: JsonObject): ToolOutcome {
        val root = Path.of(args.requireString("path")).toRealPath()
        val project = workspaceDetector.detect(root)
        val workspaceId = rememberWorkspace(root)
        return ok(buildJsonObject {
            put("workspaceId", workspaceId)
            put("root", root.toString())
            put("kind", project.kind.name)
            put("buildSupport", project.buildSupport.name)
            put("packageNames", JsonArray(project.packageNames.map(::JsonPrimitive)))
            put("modules", buildJsonArray {
                project.modules.forEach { module ->
                    add(buildJsonObject { put("name", module.name); put("path", module.path.toString()); put("kind", module.kind.name) })
                }
            })
        })
    }

    private suspend fun workspaceDescribe(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val project = workspaceDetector.detect(root)
        return ok(buildJsonObject {
            put("workspaceId", rememberWorkspace(root))
            put("root", root.toString())
            put("kind", project.kind.name)
            put("buildSupport", project.buildSupport.name)
            put("packageNames", JsonArray(project.packageNames.map(::JsonPrimitive)))
            put("modules", buildJsonArray {
                project.modules.forEach { module ->
                    add(buildJsonObject { put("name", module.name); put("path", module.path.toString()); put("kind", module.kind.name) })
                }
            })
        })
    }

    private suspend fun workspaceTrust(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val approvedRoot = config.trustedWorkspaceRoots
            .mapNotNull { candidate -> runCatching { candidate.toRealPath() }.getOrNull() }
            .firstOrNull { root.startsWith(it) }
            ?: return error("APPROVAL_REQUIRED", "workspace trust requires a host-issued approval receipt; start the server with --trust-workspace for an operator-approved root")
        val workspace = Workspace(root, workspaceDetector.detect(root).kind, emptyList(), dev.admcp.core.model.TrustStatus.UNTRUSTED)
        val now = java.time.Instant.now()
        val approval = dev.admcp.core.model.ExplicitUserApproval(
            receiptId = "startup-operator-approval",
            purpose = dev.admcp.core.model.ApprovalPurpose.WORKSPACE_TRUST,
            subjectId = root.toString(),
            scopeHash = approvedRoot.toString(),
            issuedAt = now.minusSeconds(1),
            expiresAt = now.plusSeconds(3600),
        )
        val requested = args["allowedOperations"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?.map { dev.admcp.core.model.WorkspaceOperation.valueOf(it.uppercase()) }
            ?: listOf(
                dev.admcp.core.model.WorkspaceOperation.BUILD,
                dev.admcp.core.model.WorkspaceOperation.PREVIEW,
                dev.admcp.core.model.WorkspaceOperation.ANALYZE,
            )
        val operations = requested.toSet() + dev.admcp.core.model.WorkspaceOperation.INSPECT
        val grant = trust.approve(workspace, dev.admcp.core.model.TrustScope(operations, setOf(root)), approval)
        return ok(buildJsonObject {
            put("trusted", grant.status == dev.admcp.core.model.TrustStatus.TRUSTED)
            put("root", root.toString())
            put("workspaceId", rememberWorkspace(root))
            put("operations", JsonArray(operations.map { JsonPrimitive(it.name) }))
        })
    }

    private suspend fun workspaceRevokeTrust(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val workspace = Workspace(root, ProjectKind.UNKNOWN, emptyList(), dev.admcp.core.model.TrustStatus.UNTRUSTED)
        trust.revoke(workspace)
        return ok(buildJsonObject { put("revoked", true); put("root", root.toString()) })
    }

    private suspend fun appBuild(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val detected = workspaceDetector.detect(root)
        val workspace = Workspace(root, detected.kind, emptyList(), dev.admcp.core.model.TrustStatus.UNTRUSTED)
        val decision = trust.requireTrusted(workspace, WorkspaceOperation.BUILD)
        if (!decision.allowed) return error("WORKSPACE_TRUST_REQUIRED", decision.reason ?: "workspace is not trusted")
        val variant = args.str("variant") ?: "debug"
        val service = dev.admcp.app.lifecycle.AppBuildService(SupervisorBuildExecutor(runner, root))
        val result = service.build(
            workspace,
            BuildTarget(args.str("targetId") ?: args.str("module") ?: ":app", variant, dev.admcp.core.model.ArtifactKind.APK),
            BuildOptions(
                clean = args.bool("clean") ?: false,
                offline = args.bool("offline") ?: false,
                stacktrace = args.bool("stacktrace") ?: false,
            ),
            trusted = true,
        )
        val artifact = result.artifact
        val badging = artifact?.let { built ->
            resolveAapt2(config)?.let { aapt2 -> readBadging(aapt2, built.path, host) }
        }
        var outputArtifact: dev.admcp.core.model.ArtifactId? = null
        if (artifact != null) {
            outputArtifact = runCatching {
                val bytes = Files.readAllBytes(artifact.path)
                val record = artifacts.put(ArtifactKind.APK, bytes)
                dev.admcp.core.model.ArtifactId(record.id)
            }.getOrNull()
        }
        val packageName = badging?.packageName ?: artifact?.packageName ?: detected.packageNames.firstOrNull()
        return ok(buildJsonObject {
            put("success", result.success)
            put("exitCode", result.exitCode)
            put("variant", variant)
            artifact?.let { built ->
                put("artifactPath", built.path.toString())
                put("packageName", packageName)
                put("debuggable", badging?.debuggable ?: built.debuggable)
                put("buildIdentity", built.buildIdentity)
            }
            outputArtifact?.let { put("artifactId", it.value) }
            badging?.let { info ->
                put("manifest", buildJsonObject {
                    put("packageName", info.packageName)
                    put("versionCode", info.versionCode)
                    put("versionName", info.versionName)
                    put("minSdk", info.minSdk)
                    put("targetSdk", info.targetSdk)
                    put("launchableActivity", info.launchableActivity)
                    put("nativeCode", JsonArray(info.nativeCode.map(::JsonPrimitive)))
                })
            }
            put("stdout", result.stdout.takeLast(8_000))
            put("stderr", result.stderr.takeLast(8_000))
        })
    }

    private suspend fun appInstall(args: JsonObject): ToolOutcome {
        val device = DeviceId(args.requireString("deviceId"))
        val apk = args.str("artifactPath")?.let { Path.of(it).toRealPath() } ?: run {
            val artifactId = args.str("artifactId") ?: throw IllegalArgumentException("artifactPath or artifactId is required")
            val read = artifacts.read(artifactId)
            val temp = Files.createTempFile("admcp-install-", ".apk")
            Files.write(temp, read.bytes)
            temp
        }
        val artifact = BuildArtifact(apk, args.str("packageName"), args.str("variant") ?: "debug", true, args.str("buildId") ?: apk.fileName.toString())
        val installer = object : PackageInstaller {
            override suspend fun install(deviceId: DeviceId, artifact: BuildArtifact): InstallResult {
                val result = runner.run(listOf("-s", deviceId.value, "install", "-r", artifact.path.toString()), Duration.ofMinutes(3))
                return InstallResult(result.exitCode == 0, artifact.packageName ?: "", deviceId, result.stdoutText.ifBlank { result.stderrText })
            }
        }
        val result = dev.admcp.app.lifecycle.AppInstallService(installer).install(device, artifact, adb.devices().map { it.id })
        return ok(buildJsonObject { put("success", result.success); put("packageName", result.packageName); put("message", result.message) })
    }

    private suspend fun appLaunch(args: JsonObject): ToolOutcome {
        val device = DeviceId(args.requireString("deviceId"))
        val packageName = args.requireString("packageName")
        val process = launchProcess(device, packageName, args.str("activity"), args.bool("waitForDebugger") ?: false)
        args.str("sessionId")?.let { sessionId ->
            val id = SessionId(sessionId)
            sessions.attachProcess(id, process)
            recordEvent(id, EventType.PROCESS, EventSource.ADB, buildJsonObject {
                put("pid", process.pid.value)
                put("packageName", process.packageName)
                put("processInstanceId", process.instanceId.value)
            })
        }
        return ok(buildJsonObject {
            put("pid", process.pid.value)
            put("packageName", process.packageName)
            put("processInstanceId", process.instanceId.value)
        })
    }

    private suspend fun launchProcess(
        device: DeviceId,
        packageName: String,
        activity: String?,
        waitForDebugger: Boolean,
    ): ProcessRef {
        val process = dev.admcp.app.lifecycle.AppLaunchService(object : dev.admcp.app.lifecycle.LaunchAdb {
            override suspend fun shell(deviceId: DeviceId, args: List<String>): CommandResult =
                adb.shell(deviceId, args, Duration.ofSeconds(30))
        }).launch(device, packageName, activity, waitForDebugger)
        return process.ref
    }

    private suspend fun sessionStart(args: JsonObject): ToolOutcome {
        val device = DeviceId(args.requireString("deviceId"))
        val packageName = args.str("packageName")
        val workspacePath = workspaceRoot(args, required = false)
        val workspace = workspacePath?.let { dev.admcp.core.model.WorkspaceRef(it, workspaceDetector.detect(it).kind) }
        val session = sessions.create(device, packageName, workspace) {}
        recordEvent(session.id, EventType.LIFECYCLE, EventSource.MCP, buildJsonObject {
            put("event", "SESSION_STARTED")
            put("packageName", packageName)
            put("workspace", workspacePath?.toString())
        })
        val warnings = mutableListOf<String>()
        val backendIds = args["backendIds"]?.jsonArray?.map { it.jsonPrimitive.content.uppercase() }.orEmpty()
        val startApp = args.bool("startApp") ?: false
        var process: ProcessRef? = session.process
        var launchJson: JsonObject? = null
        if (startApp) {
            if (packageName.isNullOrBlank()) {
                return error("PACKAGE_REQUIRED", "startApp requires packageName")
            }
            val waitForDebugger = backendIds.contains("JDI")
            val launched = runCatching { launchProcess(device, packageName, null, waitForDebugger) }.getOrElse { failure ->
                return error("APP_LAUNCH_FAILED", failure.message ?: "app launch failed")
            }
            sessions.attachProcess(session.id, launched)
            process = launched
            launchJson = buildJsonObject {
                put("pid", launched.pid.value)
                put("packageName", launched.packageName)
                put("processInstanceId", launched.instanceId.value)
                put("waitForDebugger", waitForDebugger)
            }
            recordEvent(session.id, EventType.PROCESS, EventSource.ADB, buildJsonObject {
                put("event", "APP_LAUNCHED")
                put("pid", launched.pid.value)
                put("packageName", launched.packageName)
                put("processInstanceId", launched.instanceId.value)
            })
        } else if (backendIds.isNotEmpty() && packageName != null && process == null) {
            val pidResult = adb.shell(device, listOf("pidof", packageName), Duration.ofSeconds(10))
            val pid = pidResult.stdoutText.trim().substringBefore(' ').toIntOrNull()
            if (pid != null) {
                process = ProcessRef(device, dev.admcp.core.model.ProcessId(pid), packageName, ProcessInstances.next(), null)
                sessions.attachProcess(session.id, process)
            } else {
                warnings += "no running process found for $packageName; backend attach may require startApp=true"
            }
        }
        val backendResults = buildJsonArray {
            backendIds.forEachIndexed { index, backendId ->
                val processInstance = process?.instanceId?.value
                if (process == null && backendId != "DART_DAP") {
                    add(buildJsonObject {
                        put("backend", backendId)
                        put("attached", false)
                        put("error", "process not available")
                    })
                    return@forEachIndexed
                }
                val attach = debugAttach(buildJsonObject {
                    put("sessionId", session.id.value)
                    put("backendId", backendId)
                    processInstance?.let { put("processInstanceId", it) }
                    if (backendId == "DART_DAP") put("pid", process?.pid?.value ?: 0)
                    put("switchBackend", index > 0)
                })
                if (attach.isError) {
                    val message = attach.structured["error"]?.jsonObject?.str("message") ?: "attach failed"
                    warnings += "$backendId attach failed: $message"
                    add(buildJsonObject { put("backend", backendId); put("attached", false); put("error", message) })
                } else {
                    add(attach.structured["data"]?.jsonObject ?: buildJsonObject { put("backend", backendId) })
                }
            }
        }
        val logcatJson = if (args.bool("captureLogcat") == true) {
            val capture = logcatStart(buildJsonObject {
                put("sessionId", session.id.value)
                put("deviceId", device.value)
            })
            if (capture.isError) {
                warnings += "logcat capture failed"
                null
            } else {
                capture.structured["data"]?.jsonObject
            }
        } else {
            null
        }
        return ok(buildJsonObject {
            put("sessionId", session.id.value)
            put("state", sessions.require(session.id).state.name)
            put("deviceId", device.value)
            process?.let { processRef ->
                put("processInstanceId", processRef.instanceId.value)
                put("pid", processRef.pid.value)
            }
            launchJson?.let { put("launch", it) }
            put("backends", backendResults)
            logcatJson?.let { put("logcat", it) }
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
        })
    }

    private suspend fun sessionStatus(args: JsonObject): ToolOutcome {
        val session = sessions.require(SessionId(args.requireString("sessionId")))
        return ok(buildJsonObject {
            put("sessionId", session.id.value)
            put("state", session.state.name)
            put("deviceId", session.deviceId.value)
            put("packageName", session.packageName)
            put("processInstanceId", session.process?.instanceId?.value)
            put("pid", session.process?.pid?.value)
        })
    }

    private suspend fun sessionStop(args: JsonObject): ToolOutcome {
        val id = SessionId(args.requireString("sessionId"))
        debugBackends.remove(id)?.values?.forEach { backend -> runCatching { backend.detach() } }
        debugProcesses.remove(id)
        logcatSessions.remove(id.value)?.capture?.stop()
        recordEvent(id, EventType.LIFECYCLE, EventSource.MCP, buildJsonObject { put("event", "SESSION_STOPPED") })
        sessions.close(id)
        return ok(buildJsonObject { put("closed", true); put("sessionId", id.value) })
    }

    private suspend fun uiInspect(args: JsonObject): ToolOutcome {
        val device = sessionDevice(args)
        val snapshot = uiBackend.snapshot(device, ++uiGeneration)
        return ok(buildJsonObject {
            put("generation", snapshot.generation)
            put("elements", buildJsonArray {
                snapshot.elements.forEach { add(uiElementJson(it)) }
            })
        })
    }

    private suspend fun uiFind(args: JsonObject): ToolOutcome {
        val snapshot = uiBackend.snapshot(sessionDevice(args), ++uiGeneration)
        args.int("expectedGeneration")?.let { expected ->
            if (expected.toLong() != snapshot.generation) return error("STALE_SELECTOR", "hierarchy generation is ${snapshot.generation}, expected $expected")
        }
        val selector = selector(args)
        val matches = uiResolver.resolveAll(snapshot, selector)
        return ok(buildJsonObject {
            put("generation", snapshot.generation)
            put("matches", buildJsonArray { matches.forEach { add(uiElementJson(it)) } })
        })
    }

    private suspend fun uiAct(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val device = sessionDevice(args)
        val snapshot = uiBackend.snapshot(device, ++uiGeneration)
        args.int("expectedGeneration")?.let { expected ->
            if (expected.toLong() != snapshot.generation) return error("STALE_SELECTOR", "hierarchy generation is ${snapshot.generation}, expected $expected")
        }
        val action = UiAction(
            kind = UiActionKind.valueOf(args.requireString("action").uppercase()),
            selector = selector(args),
            text = args.str("text"),
            direction = args.str("direction")?.let { dev.admcp.core.model.ScrollDirection.valueOf(it.uppercase()) },
            key = args.str("key"),
            expectedState = args.str("expectedState"),
        )
        val plan = uiPlanner.plan(action, snapshot)
        if (plan.assessment.decision == dev.admcp.core.model.UiActionDecision.DENY) {
            return error("ACTION_DENIED", "action is denied: ${plan.assessment.reasons.joinToString("; ")}")
        }
        if (plan.assessment.decision == dev.admcp.core.model.UiActionDecision.REQUIRE_CONFIRMATION) {
            val now = java.time.Instant.now()
            uiConfirmations.entries.removeIf { it.value.expiresAt.isBefore(now) }
            val provided = args.str("confirmationToken")
            if (provided == null) {
                val hostDecision = uiConfirmationHost.confirm(
                    sessionId,
                    action,
                    plan.assessment.risk.name,
                    plan.assessment.reasons,
                )
                if (hostDecision != true) {
                    val token = UUID.randomUUID().toString()
                    uiConfirmations[token] = PendingUiConfirmation(
                        sessionId,
                        action,
                        now.plusSeconds(300),
                    )
                    return ok(buildJsonObject {
                        put("performed", false)
                        put("confirmationRequired", true)
                        put("confirmationToken", token)
                        put("risk", plan.assessment.risk.name)
                        put("expiresInSeconds", 300)
                        put(
                            "hostConfirmation",
                            when (hostDecision) {
                                false -> "DECLINED"
                                null -> "UNAVAILABLE"
                                true -> "APPROVED"
                            },
                        )
                        put("reasons", buildJsonArray { plan.assessment.reasons.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            } else {
                val pending = uiConfirmations.remove(provided)
                if (pending == null || pending.expiresAt.isBefore(now) || pending.sessionId != sessionId || pending.action != action) {
                    return error("CONFIRMATION_INVALID", "confirmationToken is missing, expired or does not match this action")
                }
            }
        }
        val confirmedPlan = if (plan.assessment.decision == dev.admcp.core.model.UiActionDecision.REQUIRE_CONFIRMATION) {
            plan.copy(confirmed = true)
        } else {
            plan
        }
        val result = uiBackend.perform(device, confirmedPlan, snapshot.generation)
        return ok(buildJsonObject {
            put("performed", result.performed)
            put("verified", result.verified)
            put("reason", result.reason)
            put("generation", result.snapshot?.generation)
        })
    }

    private suspend fun uiScreenshot(args: JsonObject): ToolOutcome {
        val device = sessionDevice(args)
        val result = runner.run(listOf("-s", device.value, "exec-out", "screencap", "-p"), Duration.ofSeconds(30))
        if (result.exitCode != 0) return error("SCREENSHOT_FAILED", result.stderrText)
        val record = artifacts.put(ArtifactKind.PNG, result.stdout)
        return ok(buildJsonObject {
            put("artifactId", record.id)
            put("sha256", record.sha256)
            put("sizeBytes", record.sizeBytes)
        })
    }

    private suspend fun logcatStart(args: JsonObject): ToolOutcome {
        val id = args.str("sessionId") ?: "logcat-" + UUID.randomUUID()
        val device = sessionDevice(args)
        val buffers = args["buffers"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: setOf("main", "system", "crash")
        val executable = resolveAdb() ?: return error("ADB_UNAVAILABLE", "adb executable could not be resolved")
        val root = (config.artifactRoot ?: Path.of(System.getProperty("user.home"), ".admcp", "artifacts"))
            .resolve("logcat").resolve(id)
        val capture = LogcatCaptureSession(
            LogcatCaptureConfig(buffers = buffers),
            ProcessLogcatSource(executable, device, buffers),
            root,
        )
        capture.start(scope)
        logcatSessions[id] = LogcatSession(id, device, capture)
        args.str("sessionId")?.let { sessionId ->
            recordEvent(SessionId(sessionId), EventType.LOG, EventSource.LOGCAT, buildJsonObject {
                put("event", "LOGCAT_STARTED")
                put("captureId", id)
                put("buffers", JsonArray(buffers.map(::JsonPrimitive)))
            })
        }
        return ok(buildJsonObject {
            put("captureId", id)
            put("started", true)
            put("buffers", JsonArray(buffers.map(::JsonPrimitive)))
        })
    }

    private suspend fun logcatStop(args: JsonObject): ToolOutcome {
        val id = args.str("collectorId") ?: args.str("captureId") ?: args.requireString("sessionId")
        val session = logcatSessions.remove(id)
        session?.capture?.stop()
        args.str("sessionId")?.let { sessionId ->
            recordEvent(SessionId(sessionId), EventType.LOG, EventSource.LOGCAT, buildJsonObject {
                put("event", "LOGCAT_STOPPED")
                put("captureId", id)
            })
        }
        return ok(buildJsonObject {
            put("stopped", session != null)
            put("captureId", id)
            session?.capture?.state?.value?.let { health -> put("health", healthJson(health)) }
            put("segments", session?.capture?.segments()?.size ?: 0)
        })
    }

    private suspend fun logcatQuery(args: JsonObject): ToolOutcome {
        val lines = args.int("limit") ?: 200
        val captureId = args.str("collectorId") ?: args.str("captureId") ?: args.str("sessionId")
        val records: List<dev.admcp.logcat.LogRecord>
        val capture = captureId?.let { logcatSessions[it] }
        if (capture != null) {
            records = capture.capture.query(lines)
        } else {
            val device = sessionDevice(args)
            val result = runner.run(
                buildList {
                    add("-s"); add(device.value); add("logcat"); add("-d"); add("-t")
                    add(lines.coerceIn(1, 5000).toString()); add("-v"); add("threadtime")
                },
                Duration.ofSeconds(30),
            )
            if (result.exitCode != 0) return error("LOGCAT_FAILED", result.stderrText)
            records = LogcatParser().parse(result.stdoutText).take(lines)
        }
        val raw = records.joinToString("\n") { it.raw }
        val artifact = artifacts.putText(ArtifactKind.TEXT_LOG, raw)
        val sessionId = args.str("sessionId")
            ?: captureId?.takeIf { candidate -> sessions.list().any { it.id.value == candidate } }
        sessionId?.let { recordLogRecords(SessionId(it), records, "LOGCAT") }
        return ok(buildJsonObject {
            put("artifactId", artifact.id)
            put("records", buildJsonArray { records.forEach { add(logRecordJson(it)) } })
        })
    }

    private suspend fun logcatStatistics(args: JsonObject): ToolOutcome {
        val capture = (args.str("collectorId") ?: args.str("sessionId"))?.let { logcatSessions[it] }
        if (capture != null) return ok(buildJsonObject {
            put("health", healthJson(capture.capture.state.value))
            put("segments", capture.capture.segments().size)
        })
        val device = sessionDevice(args)
        val result = runner.run(listOf("-s", device.value, "logcat", "-g"), Duration.ofSeconds(15))
        return ok(buildJsonObject { put("raw", result.stdoutText) })
    }

    private suspend fun logcatHealth(args: JsonObject): ToolOutcome {
        val capture = (args.str("collectorId") ?: args.str("sessionId"))?.let { logcatSessions[it] }
        if (capture != null) return ok(buildJsonObject {
            put("health", healthJson(capture.capture.state.value))
            put("gaps", capture.capture.gaps().size)
        })
        val device = sessionDevice(args)
        val result = runner.run(listOf("-s", device.value, "logcat", "-S"), Duration.ofSeconds(15))
        return ok(buildJsonObject { put("raw", result.stdoutText); put("status", if (result.exitCode == 0) "AVAILABLE" else "UNVERIFIED") })
    }

    private suspend fun debugAttach(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val session = sessions.require(sessionId)
        val backendId = args.str("backendId") ?: "JDI"
        val device = session.deviceId
        args.str("processInstanceId")?.let { expected ->
            val actual = session.process?.instanceId?.value
            if (actual != null && actual != expected) {
                return error("STALE_PROCESS", "processInstanceId does not match the session process")
            }
        }
        val pid = args.int("pid") ?: session.process?.pid?.value
            ?: if (backendId.equals("DART_DAP", ignoreCase = true)) 0
            else return error("PID_REQUIRED", "pid or a launched process is required")
        val process = session.process ?: dev.admcp.core.model.ProcessRef(
            device,
            dev.admcp.core.model.ProcessId(pid),
            session.packageName,
            ProcessInstances.next(),
            null,
        )
        val requestedBackend = BackendId(backendId.uppercase())
        val switchBackend = args.bool("switchBackend") ?: false
        val attached = debugBackends[sessionId]
        val conflicting = attached?.keys?.filter { it != requestedBackend }.orEmpty()
        if (conflicting.isNotEmpty() && !switchBackend) {
            return error(
                "DUAL_BACKEND_CONFLICT",
                "backend ${conflicting.joinToString { it.value }} is already attached to this process; " +
                    "detach it before attaching ${requestedBackend.value}, or pass switchBackend=true " +
                    "to detach it automatically and switch sequentially. Simultaneous JDI+LLDB " +
                    "inspection is not supported by ART on this device.",
            )
        }
        val detachedBackends = mutableListOf<String>()
        if (conflicting.isNotEmpty()) {
            val current = attached ?: return error("NOT_ATTACHED", "no debug backend map for session")
            for (conflict in conflicting) {
                val previous = current[conflict] ?: continue
                try {
                    previous.detach()
                } catch (failure: Exception) {
                    return error(
                        "BACKEND_SWITCH_FAILED",
                        "could not detach ${conflict.value} before attaching ${requestedBackend.value}: " +
                            (failure.message ?: failure::class.simpleName ?: "unknown error"),
                    )
                }
                current.remove(conflict)
                runCatching { coordinator.detach(process, conflict) }
                detachedBackends += conflict.value
            }
            // Give JDWP/gdbserver a moment to release the process before the
            // next backend attaches.
            delay(800)
        }
        debugBackends[sessionId]?.get(requestedBackend)?.let { existing ->
            runCatching { existing.detach() }
            debugBackends[sessionId]?.remove(requestedBackend)
        }
        val backend: DebugBackend = when {
            backendId.equals("JDI", ignoreCase = true) -> {
                if (!JdiConnectorProbe.available()) return error("JDI_UNAVAILABLE", "JDI SocketAttach is unavailable")
                val forward = adb.forward(device, "tcp:0", "jdwp:$pid")
                val port = forward.local.substringAfterLast(':').toIntOrNull()
                    ?: return error("FORWARD_FAILED", "invalid local forward: ${forward.local}")
                var attachedBackend: JdiBackend? = null
                var lastFailure: Exception? = null
                for (attempt in 0 until 3) {
                    val candidate = JdiBackend(port)
                    try {
                        candidate.attach(process)
                        attachedBackend = candidate
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                        runCatching { candidate.detach() }
                    }
                    if (attempt < 2) delay(500)
                }
                if (attachedBackend == null) {
                    runCatching { adb.removeForward(forward) }
                    return error(
                        "JDI_ATTACH_FAILED",
                        "JDI attach failed after 3 attempts: " +
                            (lastFailure?.message ?: lastFailure?.let { it::class.simpleName } ?: "unknown error"),
                    )
                }
                attachedBackend
            }
            backendId.equals("LLDB", ignoreCase = true) -> {
                val abi = deviceAbi(device)
                val dapPath = resolveToolPath(ToolKind.LLDB_DAP, abi)
                    ?: return error("LLDB_DAP_UNAVAILABLE", "lldb-dap is not installed or not on PATH")
                val serverPath = resolveToolPath(ToolKind.LLDB_SERVER, abi)
                    ?: return error("LLDB_SERVER_UNAVAILABLE", "ABI-matched lldb-server is missing for $abi")
                val adbPath = resolveAdb() ?: return error("ADB_UNAVAILABLE", "adb is not installed")
                var attachedBackend: LldbBackend? = null
                var lastFailure: Exception? = null
                for (attempt in 0 until 3) {
                    val candidate = LldbBackend(
                        dapPath,
                        serverPath,
                        adb,
                        runner,
                        adbExecutable = adbPath,
                        targetAbi = abi,
                    )
                    try {
                        candidate.attach(process)
                        attachedBackend = candidate
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                        runCatching { candidate.detach() }
                    }
                    if (attempt < 2) delay(500)
                }
                attachedBackend ?: return error(
                    "LLDB_ATTACH_FAILED",
                    "LLDB attach failed after 3 attempts: " +
                        (lastFailure?.message ?: lastFailure?.let { it::class.simpleName } ?: "unknown error"),
                )
            }
            backendId.equals("DART_DAP", ignoreCase = true) -> {
                val projectRoot = session.workspace?.root
                    ?: args.str("projectPath")?.let { Path.of(it).toAbsolutePath().normalize() }
                    ?: return error("PROJECT_REQUIRED", "DART_DAP requires a session workspace or projectPath")
                val flutter = FlutterResolver().resolve(
                    config.flutterRoot,
                    System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map(Path::of).orEmpty(),
                ) ?: return error("FLUTTER_UNAVAILABLE", "Flutter SDK could not be resolved")
                val program = args.str("program")?.let { Path.of(it).toAbsolutePath().normalize() }
                    ?: projectRoot.resolve("lib/main.dart")
                DartDapBackend(flutter.flutter, projectRoot, device.value, program).also { it.attach(process) }
            }
            else -> return error("BACKEND_UNAVAILABLE", "$backendId debugging is not implemented in this build")
        }
        debugBackends.getOrPut(sessionId) { ConcurrentHashMap() }[backend.id] = backend
        debugProcesses[sessionId] = process
        if (session.process == null) sessions.attachProcess(sessionId, process)
        recordEvent(sessionId, EventType.BACKEND, backendEventSource(backend.id), buildJsonObject {
            put("event", "BACKEND_ATTACHED")
            put("backend", backend.id.value)
            put("processInstanceId", process.instanceId.value)
        })
        scope.launch {
            backend.events.collect { event ->
                coordinator.onEvent(event)
                recordEvent(sessionId, EventType.EXECUTION, backendEventSource(backend.id), buildJsonObject {
                    put("event", event.eventKind.name)
                    event.reason?.let { put("reason", it.name) }
                    event.backendSequence?.let { put("backendSequence", it) }
                    put("threadIds", JsonArray(event.stoppedThreadIds.map { JsonPrimitive(it.toString()) }))
                }, severity = if (event.eventKind == BackendExecutionEventKind.PROCESS_EXITED) EventSeverity.WARN else EventSeverity.INFO,
                    sourceSequence = event.backendSequence)
            }
        }
        return ok(buildJsonObject {
            put("attached", true)
            put("backend", backend.id.value)
            put("processInstanceId", process.instanceId.value)
            put("sequentialSwitch", detachedBackends.isNotEmpty())
            put("detachedBackends", JsonArray(detachedBackends.map(::JsonPrimitive)))
        })
    }

    private suspend fun debugDetach(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backendId = BackendId(args.requireString("backendId").uppercase())
        val attached = debugBackends[sessionId]
        val backend = attached?.remove(backendId)
        val process = debugProcesses[sessionId]
        backend?.detach()
        if (backend != null && process != null) runCatching { coordinator.detach(process, backend.id) }
        if (attached != null && attached.isEmpty()) {
            debugBackends.remove(sessionId)
            debugProcesses.remove(sessionId)
        }
        if (backend != null) {
            recordEvent(sessionId, EventType.BACKEND, backendEventSource(backendId), buildJsonObject {
                put("event", "BACKEND_DETACHED")
                put("backend", backendId.value)
            })
        }
        return ok(buildJsonObject { put("detached", backend != null); put("backend", backendId.value) })
    }

    private suspend fun debugStatus(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val attached = debugBackends[sessionId].orEmpty()
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no debug backend for session")
        val snapshot = coordinator.snapshot(process)
        return ok(buildJsonObject {
            put("attached", attached.isNotEmpty())
            put("backends", buildJsonArray {
                attached.values.forEach { backend ->
                    add(buildJsonObject {
                        put("backend", backend.id.value)
                        backend.lastStopReason?.let { put("reason", it.name) }
                    })
                }
            })
            attached.values.singleOrNull()?.lastStopReason?.let { put("reason", it.name) }
            snapshot.firstOrNull()?.let { execution ->
                put("execution", buildJsonObject {
                    put("state", execution.state.name); put("scope", execution.scope.name); put("stopEpoch", execution.stopEpoch.value)
                })
            }
            put("leases", buildJsonArray {
                coordinator.leases(process).forEach { lease ->
                    add(buildJsonObject { put("owner", lease.ownerBackend.value); put("state", lease.state.name); put("scope", lease.scope.name) })
                }
            })
        })
    }

    private suspend fun debugBreakpointAdd(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val request = args["breakpoint"]?.jsonObject
        val result = backend.setBreakpoint(process, dev.admcp.core.model.BreakpointRequest(
            request?.str("source") ?: args.str("source"),
            request?.int("line") ?: args.int("line"),
            request?.str("function") ?: args.str("function"),
            request?.str("condition") ?: args.str("condition"),
            request?.str("exceptionFilter") ?: args.str("exceptionFilter"),
        ))
        recordEvent(sessionId, EventType.BREAKPOINT, backendEventSource(backend.id), buildJsonObject {
            put("event", "BREAKPOINT_ADDED")
            put("breakpointId", result.id)
            put("verified", result.verified)
            request?.let {
                it.str("source")?.let { source -> put("source", source) }
                it.int("line")?.let { line -> put("line", line) }
            }
            result.message?.let { put("message", it) }
        }, severity = if (result.verified) EventSeverity.INFO else EventSeverity.WARN)
        return ok(buildJsonObject { put("id", result.id); put("verified", result.verified); put("message", result.message) })
    }

    private suspend fun debugBreakpointsList(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val entries = backend.listBreakpoints(process)
        return ok(buildJsonObject {
            put("breakpoints", buildJsonArray {
                entries.forEach { breakpoint ->
                    add(buildJsonObject {
                        put("id", breakpoint.id)
                        put("verified", breakpoint.verified)
                        put("message", breakpoint.message)
                        put("locations", buildJsonArray {
                            breakpoint.resolvedLocations.forEach { location ->
                                add(buildJsonObject {
                                    put("path", location.path.toString())
                                    put("line", location.line)
                                    put("column", location.column)
                                })
                            }
                        })
                    })
                }
            })
        })
    }

    private suspend fun debugBreakpointRemove(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val breakpointId = args.requireString("breakpointId")
        backend.removeBreakpoint(breakpointId)
        return ok(buildJsonObject { put("removed", true); put("breakpointId", breakpointId) })
    }

    private suspend fun debugPause(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val threadId = args.int("threadId")?.toLong()
        backend.pause(process, threadId)
        recordEvent(sessionId, EventType.EXECUTION, backendEventSource(backend.id), buildJsonObject {
            put("event", "PAUSED")
            threadId?.let { put("threadId", it) }
        })
        return ok(buildJsonObject { put("paused", true); put("threadId", threadId) })
    }

    private suspend fun debugContinue(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        backend.resume(dev.admcp.core.model.ResumeRequest(args.int("threadId")?.toLong(), args.bool("allThreads") ?: false), process)
        recordEvent(sessionId, EventType.EXECUTION, backendEventSource(backend.id), buildJsonObject { put("event", "RESUMED") })
        return ok(buildJsonObject { put("resumed", true) })
    }

    private suspend fun debugStep(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val threadId = args.int("threadId")?.toLong() ?: return error("THREAD_REQUIRED", "threadId is required")
        backend.step(dev.admcp.core.model.StepRequest(threadId, StepKind.valueOf(args.str("kind")?.uppercase() ?: "OVER"), null), process)
        return ok(buildJsonObject { put("stepped", true) })
    }

    private suspend fun debugThreads(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        return ok(buildJsonObject { put("threads", buildJsonArray {
            backend.threads(process).forEach { add(buildJsonObject { put("id", it.id); put("name", it.name); put("state", it.state.name); put("suspended", it.suspended) }) }
        }) })
    }

    private suspend fun debugStack(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val threadId = args.int("threadId")?.toLong() ?: return error("THREAD_REQUIRED", "threadId is required")
        val epoch = coordinator.snapshot(process).firstOrNull()?.stopEpoch ?: StopEpoch(0)
        args["stopEpoch"]?.jsonPrimitive?.content?.toLongOrNull()?.let { requested ->
            if (requested != epoch.value) return error("STALE_STOP_EPOCH", "stopEpoch $requested does not match current epoch ${epoch.value}")
        }
        val handle = dev.admcp.core.model.DebugHandle(sessionId, backend.id, process, epoch, threadId, "thread:$threadId")
        val frames = backend.frames(handle)
        return ok(buildJsonObject { put("frames", buildJsonArray {
            frames.forEach { frame ->
                add(buildJsonObject {
                    put("name", frame.name)
                    put("source", frame.location?.path?.toString())
                    put("line", frame.location?.line)
                    put("frameHandle", "${sessionId.value}|${threadId}|${epoch.value}|${frame.handle.opaqueId}")
                })
            }
        }) })
    }

    private suspend fun debugVariables(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val rawHandle = args.str("frameHandle") ?: return error("FRAME_HANDLE_REQUIRED", "frameHandle from debug_stack is required")
        val parts = rawHandle.split("|", limit = 4)
        if (parts.size != 4) return error("INVALID_FRAME_HANDLE", "frameHandle has an unsupported format")
        val threadId = parts[1].toLongOrNull() ?: return error("INVALID_FRAME_HANDLE", "frameHandle has no thread id")
        val epoch = parts[2].toLongOrNull()?.let(::StopEpoch) ?: return error("INVALID_FRAME_HANDLE", "frameHandle has no stop epoch")
        val currentEpoch = coordinator.snapshot(process).firstOrNull()?.stopEpoch ?: StopEpoch(0)
        if (epoch.value != currentEpoch.value) return error("STALE_STOP_EPOCH", "frameHandle is stale")
        val handle = dev.admcp.core.model.DebugHandle(sessionId, backend.id, process, epoch, threadId, parts[3])
        val variables = backend.variables(handle)
        return ok(buildJsonObject { put("variables", buildJsonArray {
            variables.forEach { variable -> add(buildJsonObject { put("name", variable.name); put("type", variable.type); put("value", variable.value) }) }
        }) })
    }

    private suspend fun debugEvaluate(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val rawHandle = args.str("frameHandle") ?: return error("FRAME_HANDLE_REQUIRED", "frameHandle from debug_stack is required")
        val parts = rawHandle.split("|", limit = 4)
        if (parts.size != 4) return error("INVALID_FRAME_HANDLE", "frameHandle has an unsupported format")
        val threadId = parts[1].toLongOrNull() ?: return error("INVALID_FRAME_HANDLE", "frameHandle has no thread id")
        val epoch = parts[2].toLongOrNull()?.let(::StopEpoch) ?: return error("INVALID_FRAME_HANDLE", "frameHandle has no stop epoch")
        val currentEpoch = coordinator.snapshot(process).firstOrNull()?.stopEpoch ?: StopEpoch(0)
        if (epoch.value != currentEpoch.value) return error("STALE_STOP_EPOCH", "frameHandle is stale")
        val expression = args.requireString("expression").trim()
        if (expression.isEmpty()) return error("EXPRESSION_REQUIRED", "expression is required")
        if (expression.length > 512) return error("EXPRESSION_TOO_LONG", "expression exceeds 512 characters")
        val requestedLanguage = args.str("language")?.uppercase() ?: "JAVA"
        val language = when {
            backend.id.value == "LLDB" -> ExpressionLanguage.NATIVE_CPP
            backend.id.value == "DART_DAP" && requestedLanguage == "DART" -> ExpressionLanguage.DART
            backend.id.value == "JDI" && requestedLanguage in setOf("JAVA", "KOTLIN") ->
                if (requestedLanguage == "KOTLIN") ExpressionLanguage.KOTLIN else ExpressionLanguage.JAVA
            else -> return error("LANGUAGE_MISMATCH", "$requestedLanguage is not valid for ${backend.id.value}")
        }
        val operation = when {
            language == ExpressionLanguage.NATIVE_CPP -> ExpressionOperation.NATIVE_SCOPE_READ
            expression.contains('[') -> ExpressionOperation.READ_ARRAY_ELEMENT
            expression.contains('.') -> ExpressionOperation.READ_FIELD
            else -> ExpressionOperation.READ_LOCAL
        }
        val policyProfile = args.str("policyProfile")?.uppercase() ?: "READ_ONLY"
        if (policyProfile == "DART_RUNTIME_EVALUATION_WITH_APPROVAL") {
            if (language != ExpressionLanguage.DART) {
                return error("POLICY_NOT_APPLICABLE", "Dart runtime evaluation requires the DART_DAP backend")
            }
            val provided = args.str("confirmationToken")
            if (provided == null) {
                val token = UUID.randomUUID().toString()
                val expiresAt = java.time.Instant.now().plusSeconds(300)
                evaluationConfirmations[token] = PendingEvaluation(sessionId, expression, rawHandle, expiresAt)
                return ok(buildJsonObject {
                    put("performed", false)
                    put("confirmationRequired", true)
                    put("confirmationToken", token)
                    put("expiresInSeconds", 300)
                    put("sideEffectsPossible", true)
                    put("reason", "Dart runtime evaluation requires explicit per-expression approval")
                })
            }
            val pending = evaluationConfirmations.remove(provided)
            if (pending == null || pending.expiresAt.isBefore(java.time.Instant.now()) ||
                pending.sessionId != sessionId || pending.expression != expression || pending.frameHandle != rawHandle
            ) {
                return error("CONFIRMATION_INVALID", "confirmationToken is missing, expired or does not match this expression")
            }
        }
        val policy = ExpressionPolicy(
            allowReadOnly = true,
            allowMethodInvocation = false,
            maxEvaluationTime = Duration.ofSeconds(2),
            maxObjectDepth = 4,
            maxArrayElements = 256,
        )
        val decision = expressionPolicy.decide(
            language,
            operation,
            policy,
            InspectionContext(process, epoch, 0, expression.count { it == '.' }),
            Duration.ofSeconds(2),
        )
        if (!decision.allowed) return error("EVALUATION_DENIED", decision.reason ?: "expression is not permitted")
        val handle = dev.admcp.core.model.DebugHandle(sessionId, backend.id, process, epoch, threadId, parts[3])
        val result = backend.evaluate(
            EvaluationRequest(
                language = language,
                expression = expression,
                frame = handle,
                policy = policy,
                timeout = Duration.ofSeconds(2),
                operation = operation,
            ),
        )
        result.error?.let { message -> return error("EVALUATION_FAILED", message) }
        val value = result.value
        recordEvent(sessionId, EventType.EXECUTION, backendEventSource(backend.id), buildJsonObject {
            put("event", "EVALUATED")
            put("operation", result.operation.name)
            put("language", language.name)
            value?.type?.let { put("resultType", it) }
        })
        return ok(buildJsonObject {
            put("expression", expression)
            put("value", value?.value)
            put("type", value?.type)
            put("sideEffectsPossible", result.sideEffectsPossible)
            put("operation", result.operation.name)
            put("language", language.name)
        })
    }

    private suspend fun debugModules(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        if (backend.id.value != "LLDB") return error("UNSUPPORTED_OPERATION", "native module discovery requires an LLDB backend")
        val modules = backend.modules(process, args.bool("includeBuildIds") ?: false)
        return ok(buildJsonObject {
            put("modules", buildJsonArray {
                modules.forEach { module ->
                    add(buildJsonObject {
                        put("name", module.name)
                        put("path", module.path)
                        put("buildId", module.buildId)
                        put("loadAddress", module.loadAddress?.toString(16))
                    })
                }
            })
        })
    }

    private suspend fun debugRegisters(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val threadId = args.int("threadId")?.toLong() ?: return error("THREAD_REQUIRED", "threadId is required")
        val currentEpoch = coordinator.snapshot(process).firstOrNull()?.stopEpoch ?: StopEpoch(0)
        val requestedEpoch = args.int("stopEpoch")?.toLong()?.let(::StopEpoch) ?: currentEpoch
        if (requestedEpoch.value != currentEpoch.value) return error("STALE_STOP_EPOCH", "stopEpoch is stale")
        val handle = dev.admcp.core.model.DebugHandle(sessionId, backend.id, process, currentEpoch, threadId, "")
        val registers = backend.registers(handle)
        if (registers.isEmpty()) return error("REGISTERS_UNAVAILABLE", "backend returned no registers")
        return ok(buildJsonObject {
            put("registers", buildJsonArray {
                registers.forEach { register ->
                    add(buildJsonObject {
                        put("name", register.name)
                        put("value", register.value)
                        put("bitWidth", register.bitWidth)
                    })
                }
            })
        })
    }

    private suspend fun debugMemoryRead(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        val backend = backendFor(sessionId, args) ?: return error("NOT_ATTACHED", "no debug backend")
        val process = debugProcesses[sessionId] ?: return error("NOT_ATTACHED", "no process")
        staleProcess(args, process)?.let { return error("STALE_PROCESS", it) }
        val address = args.requireString("address")
        val size = args.int("size") ?: return error("SIZE_REQUIRED", "size is required")
        if (size !in 1..65536) return error("INVALID_SIZE", "size must be between 1 and 65536")
        val currentEpoch = coordinator.snapshot(process).firstOrNull()?.stopEpoch ?: StopEpoch(0)
        val requestedEpoch = args.int("stopEpoch")?.toLong()?.let(::StopEpoch) ?: currentEpoch
        if (requestedEpoch.value != currentEpoch.value) return error("STALE_STOP_EPOCH", "stopEpoch is stale")
        val bytes = backend.readMemory(process, address, size)
            ?: return error("MEMORY_UNAVAILABLE", "backend returned no memory")
        val format = args.str("format")?.uppercase() ?: "HEX"
        val data = if (format == "BASE64") {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            bytes.joinToString("") { "%02X".format(it) }
        }
        return ok(buildJsonObject {
            put("address", address)
            put("size", bytes.size)
            put("format", format)
            put("data", data)
        })
    }

    private suspend fun profileStart(args: JsonObject): ToolOutcome {
        val session = sessions.require(SessionId(args.requireString("sessionId")))
        val durationMs = args.int("durationMs") ?: return error("DURATION_REQUIRED", "durationMs is required")
        if (durationMs !in 1..300_000) return error("INVALID_DURATION", "durationMs must be between 1 and 300000")
        val probe = runner.run(
            listOf("-s", session.deviceId.value, "shell", "perfetto", "--version"),
            Duration.ofSeconds(15),
        )
        if (probe.exitCode != 0) {
            return error("CAPABILITY_UNAVAILABLE", "Perfetto is unavailable on the device: ${probe.stderrText.ifBlank { probe.stdoutText }}")
        }
        val profilerSessionId = "trace-" + UUID.randomUUID()
        val capture = scope.async {
            capturePerfetto(session.deviceId, profilerSessionId, durationMs, args.str("profileConfig"))
        }
        traceSessions[profilerSessionId] = capture
        traceSessionOwners[profilerSessionId] = session.id
        recordEvent(session.id, EventType.ARTIFACT, EventSource.MCP, buildJsonObject {
            put("event", "TRACE_STARTED")
            put("profilerSessionId", profilerSessionId)
            put("durationMs", durationMs)
        })
        return ok(buildJsonObject {
            put("profilerSessionId", profilerSessionId)
            put("started", true)
            put("durationMs", durationMs)
        })
    }

    private suspend fun capturePerfetto(
        device: DeviceId,
        profilerSessionId: String,
        durationMs: Int,
        profileConfig: String?,
    ): ToolOutcome {
        val remote = "/data/misc/perfetto-traces/admcp-$profilerSessionId.perfetto-trace"
        val categories = profileConfig
            ?.split(',', ' ', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("sched", "freq", "idle", "am", "wm", "gfx", "view", "binder_driver", "dalvik", "input", "res", "memory")
        val capture = runner.run(
            listOf("-s", device.value, "shell", "perfetto", "-o", remote, "-t", "${durationMs}ms") + categories,
            Duration.ofMillis(durationMs.toLong() + 30_000),
        )
        if (capture.exitCode != 0) {
            return error("TRACE_CAPTURE_FAILED", capture.stderrText.ifBlank { capture.stdoutText })
        }
        val pull = runner.run(
            listOf("-s", device.value, "exec-out", "cat", remote),
            Duration.ofMinutes(2),
        )
        runCatching {
            runner.run(listOf("-s", device.value, "shell", "rm", "-f", remote), Duration.ofSeconds(15))
        }
        if (pull.exitCode != 0 || pull.stdout.isEmpty()) {
            return error("TRACE_PULL_FAILED", pull.stderrText.ifBlank { "captured trace was empty" })
        }
        val artifact = artifacts.put(ArtifactKind.PERFETTO_TRACE, pull.stdout)
        return ok(buildJsonObject {
            put("artifactId", artifact.id)
            put("sha256", artifact.sha256)
            put("sizeBytes", artifact.sizeBytes)
            put("durationMs", durationMs)
            put("categories", JsonArray(categories.map(::JsonPrimitive)))
        })
    }

    private suspend fun profileStop(args: JsonObject): ToolOutcome {
        val profilerSessionId = args.str("profilerSessionId") ?: return error("TRACE_REQUIRED", "profilerSessionId is required")
        val capture = traceSessions.remove(profilerSessionId)
            ?: return error("NOT_STARTED", "no system trace is active for $profilerSessionId")
        val owner = traceSessionOwners.remove(profilerSessionId)
        val outcome = capture.await()
        owner?.let {
            recordEvent(it, EventType.ARTIFACT, EventSource.MCP, buildJsonObject {
                put("event", "TRACE_STOPPED")
                put("profilerSessionId", profilerSessionId)
            })
        }
        return outcome
    }

    private suspend fun heapDump(args: JsonObject): ToolOutcome {
        val session = sessions.require(SessionId(args.requireString("sessionId")))
        val packageName = args.str("packageName") ?: session.packageName ?: session.process?.packageName
            ?: return error("PACKAGE_REQUIRED", "session has no package name; start the app or pass packageName")
        args.str("processInstanceId")?.let { expected ->
            val actual = session.process?.instanceId?.value
            if (actual != null && actual != expected) return error("STALE_PROCESS", "processInstanceId does not match the session process")
        }
        val device = session.deviceId
        val path = "/data/local/tmp/admcp-${UUID.randomUUID()}.hprof"
        val result = runner.run(listOf("-s", device.value, "shell", "am", "dumpheap", packageName, path), Duration.ofMinutes(2))
        if (result.exitCode != 0) return error("HEAPDUMP_FAILED", result.stderrText)
        val pulled = runner.run(listOf("-s", device.value, "exec-out", "cat", path), Duration.ofMinutes(5))
        runCatching { runner.run(listOf("-s", device.value, "shell", "rm", "-f", path), Duration.ofSeconds(15)) }
        if (pulled.exitCode != 0 || pulled.stdout.isEmpty()) {
            return error("HEAPDUMP_PULL_FAILED", pulled.stderrText.ifBlank { "device heap dump was empty" })
        }
        val artifact = artifacts.put(ArtifactKind.JVM_HEAP_DUMP, pulled.stdout)
        recordEvent(session.id, EventType.ARTIFACT, EventSource.MCP, buildJsonObject {
            put("event", "HEAPDUMP_CAPTURED")
            put("artifactId", artifact.id)
            put("sizeBytes", artifact.sizeBytes)
            put("packageName", packageName)
        })
        val header = pulled.stdout.copyOfRange(0, minOf(32, pulled.stdout.size))
            .toString(Charsets.US_ASCII)
            .trim('\u0000', '\n', '\r')
        return ok(buildJsonObject {
            put("artifactId", artifact.id)
            put("sha256", artifact.sha256)
            put("sizeBytes", artifact.sizeBytes)
            put("format", "HPROF")
            put("header", header.take(32))
            put("packageName", packageName)
            put("devicePath", path)
            put("deviceFileRemoved", true)
        })
    }

    private suspend fun traceAnalyze(args: JsonObject): ToolOutcome {
        val artifact = runCatching { artifacts.readAll(args.requireString("artifactId")) }
            .getOrElse { failure -> return error("ARTIFACT_READ_FAILED", failure.message ?: "could not read trace artifact") }
        val range = args["timeRange"]?.jsonObject
        val from = range?.str("from")?.let { parseNanos(it) }
            ?: if (range?.str("from") != null) {
                return error("INVALID_TIME_RANGE", "timeRange.from is not a valid nanosecond value")
            } else {
                null
            }
        val to = range?.str("to")?.let { parseNanos(it) }
            ?: if (range?.str("to") != null) {
                return error("INVALID_TIME_RANGE", "timeRange.to is not a valid nanosecond value")
            } else {
                null
            }
        if (from != null && to != null && from > to) return error("INVALID_TIME_RANGE", "timeRange.from is after timeRange.to")
        val analysis = traceAnalyzer.analyze(artifact.bytes, from, to)
        return ok(traceAnalysisJson(analysis, args, artifact.record.id, artifact.record.kind.name))
    }

    private fun traceAnalysisJson(
        analysis: TraceAnalysis,
        args: JsonObject,
        artifactId: String,
        artifactKind: String,
    ): JsonObject = buildJsonObject {
        put("artifactId", artifactId)
        put("artifactKind", artifactKind)
        put("availability", if (analysis.valid) "AVAILABLE" else "FAILED")
        put("valid", analysis.valid)
        put("packetCount", analysis.packetCount)
        put("ftraceEventCount", analysis.ftraceEventCount)
        put("schedSwitchCount", analysis.schedSwitchCount)
        put("firstTimestampNanos", analysis.firstTimestampNanos)
        put("lastTimestampNanos", analysis.lastTimestampNanos)
        put("durationNanos", analysis.durationNanos)
        put("queries", JsonArray((args["queries"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()).map(::JsonPrimitive)))
        put("processes", buildJsonArray {
            analysis.processes.forEach { process ->
                add(buildJsonObject { put("pid", process.pid); put("name", process.name) })
            }
        })
        put("threads", buildJsonArray {
            analysis.threads.forEach { thread ->
                add(buildJsonObject { put("tid", thread.tid); put("tgid", thread.tgid); put("name", thread.name) })
            }
        })
        put("topSlices", buildJsonArray {
            analysis.slices.forEach { slice ->
                add(buildJsonObject {
                    put("name", slice.name)
                    put("trackUuid", slice.trackUuid)
                    put("count", slice.count)
                    put("totalDurationNanos", slice.totalDurationNanos)
                    put("maxDurationNanos", slice.maxDurationNanos)
                })
            }
        })
        put("counters", buildJsonArray {
            analysis.counters.forEach { counter ->
                add(buildJsonObject {
                    put("name", counter.name)
                    put("trackUuid", counter.trackUuid)
                    put("samples", counter.samples)
                    put("min", counter.minValue)
                    put("max", counter.maxValue)
                    put("last", counter.lastValue)
                })
            }
        })
        put("errors", JsonArray(analysis.errors.map(::JsonPrimitive)))
        put("limitations", JsonArray(analysis.limitations.map(::JsonPrimitive)))
    }

    private fun previewReference(root: Path, args: JsonObject): String? {
        args.str("modulePath")?.let { return it }
        val module = workspaceDetector.detect(root).modules.firstOrNull() ?: return null
        return ":" + module.name.trimStart(':')
    }

    private fun parseNanos(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return it }
        val match = Regex("""^(\d+(?:\.\d+)?)(ns|us|ms|s)$""", RegexOption.IGNORE_CASE).matchEntire(trimmed) ?: return null
        val number = match.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "ns" -> 1.0
            "us" -> 1_000.0
            "ms" -> 1_000_000.0
            "s" -> 1_000_000_000.0
            else -> return null
        }
        return (number * multiplier).toLong()
    }

    private suspend fun previewList(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val workspace = Workspace(root, workspaceDetector.detect(root).kind, emptyList(), dev.admcp.core.model.TrustStatus.UNTRUSTED)
        val decision = trust.requireTrusted(workspace, WorkspaceOperation.PREVIEW)
        if (!decision.allowed) return error("WORKSPACE_TRUST_REQUIRED", decision.reason ?: "workspace is not trusted")
        val cli = resolveAndroidCli(config) ?: return error("ANDROID_CLI_UNAVAILABLE", "android CLI is not installed or not on PATH")
        val command = buildList {
            add("preview")
            add("list")
            add("--format=json")
            previewReference(root, args)?.let { add(it) }
        }
        val result = host.run(cli, command, root, Duration.ofMinutes(10), System.getenv())
        val raw = parseJsonLoose(result.stdoutText)
        if (raw == null) {
            return error("PREVIEW_LIST_FAILED", result.stderrText.ifBlank { result.stdoutText.takeLast(2000) })
        }
        val rootObject = raw as? JsonObject ?: return error("PREVIEW_LIST_FAILED", "android preview did not return a JSON object")
        if (rootObject["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == false) {
            return error("PREVIEW_LIST_FAILED", rootObject.str("globalError") ?: "android preview list failed")
        }
        val filter = args.str("filter")
        val previews = rootObject["previews"]?.jsonArray?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { preview ->
                filter == null || preview.toString().contains(filter, ignoreCase = true)
            }
        return ok(buildJsonObject {
            put("availability", "AVAILABLE")
            put("command", command.joinToString(" "))
            put("count", previews.size)
            put("previews", JsonArray(previews))
            put("warnings", JsonArray(emptyList()))
        })
    }

    private suspend fun previewRender(args: JsonObject): ToolOutcome {
        val root = workspaceRoot(args) ?: return error("WORKSPACE_REQUIRED", "workspaceId or path is required")
        val workspace = Workspace(root, workspaceDetector.detect(root).kind, emptyList(), dev.admcp.core.model.TrustStatus.UNTRUSTED)
        val decision = trust.requireTrusted(workspace, WorkspaceOperation.PREVIEW)
        if (!decision.allowed) return error("WORKSPACE_TRUST_REQUIRED", decision.reason ?: "workspace is not trusted")
        val cli = resolveAndroidCli(config) ?: return error("ANDROID_CLI_UNAVAILABLE", "android CLI is not installed or not on PATH")
        val previewId = args.requireString("previewId")
        val outputRoot = (config.artifactRoot ?: Path.of(System.getProperty("user.home"), ".admcp", "artifacts"))
            .resolve("previews")
            .resolve(UUID.randomUUID().toString())
        Files.createDirectories(outputRoot)
        val command = buildList {
            add("preview")
            add("render")
            add("--format=json")
            add("--component-tree")
            add("--fqn=$previewId")
            add("--output=${outputRoot.toAbsolutePath()}")
            previewReference(root, args)?.let { add(it) }
        }
        val result = host.run(cli, command, root, Duration.ofMinutes(20), System.getenv())
        val raw = parseJsonLoose(result.stdoutText)
        if (result.exitCode != 0) {
            return error("PREVIEW_RENDER_FAILED", result.stderrText.ifBlank { result.stdoutText.takeLast(2000) })
        }
        val cliSuccess = (raw as? JsonObject)?.get("success")?.jsonPrimitive?.content?.toBooleanStrictOrNull() != false
        val files = runCatching {
            Files.walk(outputRoot).use { stream ->
                stream.filter { Files.isRegularFile(it) }.toList()
            }
        }.getOrDefault(emptyList())
        val images = buildJsonArray {
            files.filter { it.fileName.toString().endsWith(".png", ignoreCase = true) }.forEach { file ->
                val record = artifacts.put(ArtifactKind.PNG, Files.readAllBytes(file))
                add(buildJsonObject {
                    put("artifactId", record.id)
                    put("path", file.toString())
                    put("sha256", record.sha256)
                    put("sizeBytes", record.sizeBytes)
                })
            }
        }
        val trees = buildJsonArray {
            files.filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }.forEach { file ->
                val record = artifacts.putText(ArtifactKind.JSON, Files.readString(file))
                add(buildJsonObject {
                    put("artifactId", record.id)
                    put("path", file.toString())
                    put("sha256", record.sha256)
                    put("sizeBytes", record.sizeBytes)
                })
            }
        }
        if (images.isEmpty()) {
            val detail = (raw as? JsonObject)?.let { it.str("globalError") ?: it.toString() }
                ?: result.stderrText.ifBlank { result.stdoutText.takeLast(2000) }
            return error("PREVIEW_RENDER_EMPTY", "android preview render produced no PNG output: ${detail.take(2000)}")
        }
        return ok(buildJsonObject {
            put("availability", "AVAILABLE")
            put("previewId", previewId)
            put("outputDirectory", outputRoot.toString())
            put("images", images)
            put("componentTrees", trees)
            put("cliSuccess", cliSuccess)
            raw?.let { put("raw", it) }
            val warnings = mutableListOf<String>()
            if (!cliSuccess) warnings += "android preview reported an error but produced render files; inspect raw.results"
            if (args.str("deviceProfile") != null) warnings += "the installed android preview command does not accept deviceProfile; using the project default"
            if (args.str("theme") != null) warnings += "the installed android preview command does not accept theme; using the project default"
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
        })
    }

    private suspend fun analyzeArtifact(args: JsonObject): ToolOutcome {
        args.str("sessionId")?.let { sessions.require(SessionId(it)) }
        val artifactPath = args.str("path")?.let { Path.of(it).toRealPath() }
        val artifactRead = args.str("artifactId")?.let { runCatching { artifacts.readAll(it) }.getOrNull() }
        if (artifactPath == null && artifactRead == null) {
            return error("ARTIFACT_REQUIRED", "artifactId or path is required")
        }
        if (artifactPath != null && !Files.isRegularFile(artifactPath)) {
            return error("ARTIFACT_NOT_FOUND", "path is not a regular file: $artifactPath")
        }
        var tempFile: Path? = null
        val path = artifactPath ?: run {
            val suffix = when (artifactRead!!.record.kind) {
                ArtifactKind.APK -> ".apk"
                ArtifactKind.AAB -> ".aab"
                ArtifactKind.AAR -> ".aar"
                ArtifactKind.JAR -> ".jar"
                else -> ".bin"
            }
            Files.createTempFile("admcp-analyze-", suffix).also { file ->
                Files.write(file, artifactRead.bytes)
                tempFile = file
            }
        }
        try {
            val bytes = artifactRead?.bytes ?: Files.readAllBytes(path)
            val sha = artifactRead?.record?.sha256 ?: MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val options = args["analysisOptions"]?.jsonObject
            val includeManifest = options?.bool("includeManifest") ?: true
            val includeNativeLibraries = options?.bool("includeNativeLibraries") ?: true
            val includeDependencies = options?.bool("includeDependencies") ?: true
            val aapt2 = resolveAapt2(config)
            val badging = aapt2?.let { readBadging(it, path, host) }
            val zip = summarizeZip(path)
            val cli = resolveAndroidCli(config)
            val cliResult = cli?.let {
                host.run(it, listOf("analyze", "--format=json", path.toString()), null, Duration.ofMinutes(10), System.getenv())
            }
            val cliRaw = cliResult?.let { parseJsonLoose(it.stdoutText) }
            val warnings = mutableListOf<String>()
            if (cliResult != null && cliResult.exitCode != 0) {
                warnings += "android analyze failed: ${cliResult.stderrText.ifBlank { cliResult.stdoutText }.take(1000)}"
            }
            if (aapt2 == null) warnings += "aapt2 was not found; manifest fields are limited"
            if (zip == null) warnings += "the artifact is not a readable ZIP container"
            val availability = when {
                badging != null || zip != null || cliRaw != null -> "AVAILABLE"
                else -> "LIMITED"
            }
            val normalized = buildJsonObject {
                put("artifactPath", path.toString())
                put("sha256", sha)
                put("sizeBytes", bytes.size.toLong())
                put("extension", path.fileName.toString().substringAfterLast('.', "").lowercase())
                put("packageName", badging?.packageName)
                put("versionCode", badging?.versionCode)
                put("versionName", badging?.versionName)
                put("minSdk", badging?.minSdk)
                put("targetSdk", badging?.targetSdk)
                put("debuggable", badging?.debuggable)
                put("launchableActivity", badging?.launchableActivity)
                put("appLabel", badging?.appLabel)
                zip?.let { summary ->
                    put("container", buildJsonObject {
                        put("entries", summary.entries)
                        put("dexFiles", summary.dexFiles)
                        put("hasManifest", summary.hasManifest)
                        put("hasResources", summary.hasResources)
                        if (includeNativeLibraries) {
                            put("nativeLibraries", JsonArray(summary.nativeLibraries.map(::JsonPrimitive)))
                        }
                    })
                }
                if (includeManifest && badging != null) {
                    put("manifest", buildJsonObject {
                        put("packageName", badging.packageName)
                        put("versionCode", badging.versionCode)
                        put("versionName", badging.versionName)
                        put("minSdk", badging.minSdk)
                        put("targetSdk", badging.targetSdk)
                        put("debuggable", badging.debuggable)
                        put("launchableActivity", badging.launchableActivity)
                        put("nativeCode", JsonArray(badging.nativeCode.map(::JsonPrimitive)))
                    })
                }
                if (includeDependencies && cliRaw != null) put("androidCli", cliRaw)
            }
            val analysisArtifact = artifacts.putText(ArtifactKind.JSON, normalized.toString())
            return ok(buildJsonObject {
                put("availability", availability)
                put("artifactId", analysisArtifact.id)
                artifactRead?.let { put("sourceArtifactId", it.record.id) }
                put("sha256", sha)
                put("sizeBytes", bytes.size.toLong())
                put("normalized", normalized)
                put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
                put("command", cliResult?.let { "android analyze --format=json ${path.fileName}" })
            })
        } finally {
            tempFile?.let { file -> runCatching { Files.deleteIfExists(file) } }
        }
    }

    private suspend fun diagnosticsTimeline(args: JsonObject): ToolOutcome {
        val sessionId = SessionId(args.requireString("sessionId"))
        sessions.require(sessionId)
        val from = args.str("from")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?: if (args.str("from") != null) return error("INVALID_TIME", "from must be an ISO-8601 instant") else null
        val to = args.str("to")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?: if (args.str("to") != null) return error("INVALID_TIME", "to must be an ISO-8601 instant") else null
        val types = timelineTypes(args)
        val limit = (args.int("limit") ?: 200).coerceIn(1, 1000)
        val page = eventStore.query(
            EventQuery(
                sessionId = sessionId,
                fromHostTime = from,
                toHostTime = to,
                types = types,
                limit = limit,
                cursor = args.str("cursor"),
            ),
        )
        return ok(buildJsonObject {
            put("sessionId", sessionId.value)
            put("events", buildJsonArray { page.events.forEach { add(eventJson(it)) } })
            put("nextCursor", page.nextCursor)
            put("count", page.events.size)
            put("clock", buildJsonObject {
                put("ordering", "host_timestamp_then_source_sequence")
                put("uncertaintyNanos", null as Long?)
                put("status", "UNAVAILABLE")
                put("note", "per-event clockCalibration.uncertaintyNanos is included when calibration exists")
            })
        })
    }

    private suspend fun diagnosticsCrashes(args: JsonObject): ToolOutcome {
        val device = sessionDevice(args)
        val limit = 200
        val result = runner.run(
            listOf("-s", device.value, "shell", "logcat", "-b", "crash", "-d", "-t", limit.toString(), "-v", "threadtime"),
            Duration.ofSeconds(30),
        )
        if (result.exitCode != 0) return error("CRASH_BUFFER_FAILED", result.stderrText)
        val records = LogcatParser().parse(result.stdoutText).take(limit)
        val artifact = artifacts.putText(ArtifactKind.TEXT_LOG, records.joinToString("\n") { it.raw })
        args.str("sessionId")?.let { recordLogRecords(SessionId(it), records, "CRASH") }
        return ok(buildJsonObject {
            put("artifactId", artifact.id)
            put("records", buildJsonArray { records.forEach { add(logRecordJson(it)) } })
            put("count", records.size)
        })
    }

    private suspend fun diagnosticsAnalyze(args: JsonObject): ToolOutcome {
        val rawOverride = args.str("rawJson")
        if (rawOverride != null) {
            val report = diagnostics.analyze(rawOverride, args.str("source") ?: "unknown")
            return ok(diagnosticsReportJson(report, rawJson = true))
        }
        val sessionId = SessionId(args.requireString("sessionId"))
        val session = sessions.require(sessionId)
        val from = args.str("from")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?: if (args.str("from") != null) return error("INVALID_TIME", "from must be an ISO-8601 instant") else null
        val to = args.str("to")?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            ?: if (args.str("to") != null) return error("INVALID_TIME", "to must be an ISO-8601 instant") else null
        val types = timelineTypes(args)
        val firstPage = eventStore.query(EventQuery(sessionId, from, to, types, 1000))
        if (firstPage.events.none { it.severity == EventSeverity.ERROR || it.severity == EventSeverity.FATAL }) {
            val crash = runner.run(
                listOf("-s", session.deviceId.value, "shell", "logcat", "-b", "crash", "-d", "-t", "200", "-v", "threadtime"),
                Duration.ofSeconds(30),
            )
            if (crash.exitCode == 0) {
                recordLogRecords(sessionId, LogcatParser().parse(crash.stdoutText).take(200), "CRASH")
            }
        }
        val page = eventStore.query(EventQuery(sessionId, from, to, types, 1000))
        val requested = args["evidenceIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val selected = if (requested.isEmpty()) page.events else page.events.filter { it.id.value in requested }
        val issues = selected.mapNotNull { event ->
            val severity = event.severity?.name ?: return@mapNotNull null
            if (severity !in setOf("WARN", "ERROR", "FATAL")) return@mapNotNull null
            buildJsonObject {
                put("id", event.id.value)
                put("severity", severity)
                put("message", event.data.str("message") ?: event.data.str("tag") ?: event.type.name)
            }
        }
        val raw = buildJsonObject {
            put("issues", JsonArray(issues))
            put("sessionId", sessionId.value)
            put("eventCount", selected.size)
            put("scope", args.str("scope") ?: "SESSION")
            from?.let { put("from", it.toString()) }
            to?.let { put("to", it.toString()) }
        }
        val report = diagnostics.analyze(raw.toString(), "session-timeline")
        return ok(diagnosticsReportJson(report, rawJson = false, eventIds = selected.map { it.id.value }))
    }

    private suspend fun artifactGet(args: JsonObject): ToolOutcome {
        args.str("sessionId")?.let { sessions.require(SessionId(it)) }
        val read = artifacts.read(args.requireString("artifactId"))
        val isText = read.record.kind in setOf(ArtifactKind.TEXT_LOG, ArtifactKind.JSON)
        val representation = args.str("representation") ?: "REDACTED_VIEW"
        if (representation == "METADATA") {
            return ok(buildJsonObject {
                put("artifactId", read.record.id)
                put("sha256", read.record.sha256)
                put("sizeBytes", read.record.sizeBytes)
                put("kind", read.record.kind.name)
                put("redaction", read.record.redaction.name)
            })
        }
        val content = if (isText && representation == "REDACTED_VIEW") {
            dev.admcp.core.artifacts.Redactor().redact(read.bytes.toString(Charsets.UTF_8)).value
        } else if (isText) read.bytes.toString(Charsets.UTF_8)
        else java.util.Base64.getEncoder().encodeToString(read.bytes)
        return ok(buildJsonObject {
            put("artifactId", read.record.id)
            put("sha256", read.record.sha256)
            put("sizeBytes", read.record.sizeBytes)
            put("truncated", read.truncated)
            put("encoding", if (isText) "utf8" else "base64")
            put("representation", representation)
            put("content", content)
        })
    }

    private suspend fun signalsCrashes(args: JsonObject): ToolOutcome {
        val device = sessionDevice(args)
        val limit = args.int("limit")?.coerceIn(1, 500) ?: 200
        val result = runner.run(
            listOf("-s", device.value, "shell", "logcat", "-b", "crash", "-d", "-t", limit.toString(), "-v", "threadtime"),
            Duration.ofSeconds(30),
        )
        if (result.exitCode != 0) return error("CRASH_BUFFER_FAILED", result.stderrText)
        val records = LogcatParser().parse(result.stdoutText).take(limit)
        args.str("sessionId")?.let { recordLogRecords(SessionId(it), records, "CRASH") }
        return ok(buildJsonObject {
            put("source", "LOCAL_DEVICE")
            put("fieldTelemetry", false)
            put("availability", if (records.isEmpty()) "NO_LOCAL_SIGNALS" else "LOCAL_ONLY")
            put("reason", "Crashlytics credentials are not configured; returning the device crash buffer only")
            put("count", records.size)
            put("signals", buildJsonArray {
                records.forEach { record ->
                    add(buildJsonObject {
                        put("timestamp", record.timestamp)
                        put("pid", record.pid)
                        put("level", record.level)
                        put("tag", record.tag)
                        put("message", record.message)
                    })
                }
            })
        })
    }

    private suspend fun composeInspect(args: JsonObject): ToolOutcome {
        val inspectionKind = (args.str("inspectionKind") ?: "TREE").uppercase()
        if (inspectionKind != "TREE") {
            return ok(buildJsonObject {
                put("supported", false)
                put("inspectionKind", inspectionKind)
                put("reason", "$inspectionKind requires Compose runtime instrumentation; this build exposes reported accessibility semantics only")
                put("semantics", JsonArray(emptyList()))
                put("limitations", JsonArray(listOf(
                    JsonPrimitive("internal Compose composition tree and slot table are not exposed"),
                    JsonPrimitive("recomposition counts and traces require runtime instrumentation"),
                )))
            })
        }
        val snapshot = uiBackend.snapshot(sessionDevice(args), ++uiGeneration)
        val inspection = composeInspector.detect(snapshot)
        return ok(buildJsonObject {
            put("supported", inspection.supported)
            put("inspectionKind", "TREE")
            put("source", "UI_AUTOMATOR_ACCESSIBILITY")
            put("nodeCount", snapshot.elements.size)
            put("reason", inspection.reason)
            put("semantics", JsonArray(inspection.semantics.map(::JsonPrimitive)))
            put("limitations", JsonArray(listOf(
                JsonPrimitive("reports Compose semantics exposed through the accessibility hierarchy"),
                JsonPrimitive("internal composition tree, slot table, and recomposition data are not available"),
            )))
        })
    }

    private fun logRecordJson(record: dev.admcp.logcat.LogRecord): JsonObject = buildJsonObject {
        put("timestamp", record.timestamp); put("pid", record.pid); put("tid", record.tid)
        put("level", record.level); put("tag", record.tag); put("message", record.message)
    }

    private fun healthJson(health: dev.admcp.logcat.CaptureHealth): JsonObject = buildJsonObject {
        put("state", health.state)
        put("bytesWritten", health.bytesWritten)
        put("segments", health.segments)
        put("droppedBytes", health.droppedBytes)
        put("quotaReached", health.quotaReached)
        put("error", health.error)
    }

    private fun uiElementJson(element: dev.admcp.core.model.UiElement): JsonObject = buildJsonObject {
        put("id", element.id); put("text", element.text); put("contentDescription", element.contentDescription)
        put("className", element.className); put("clickable", element.clickable); put("enabled", element.enabled)
        put("focused", element.focused); put("scrollable", element.scrollable)
        element.bounds?.let { bounds ->
            put("bounds", buildJsonObject { put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom) })
        }
    }

    private fun eventJson(event: EventEnvelope): JsonObject = buildJsonObject {
        put("id", event.id.value)
        put("sessionId", event.sessionId.value)
        put("type", event.type.name)
        put("source", event.source.name)
        put("hostTimestamp", event.hostTimestamp.toString())
        event.deviceTimestamp?.let { put("deviceTimestamp", it.toString()) }
        event.monotonicNanos?.let { put("monotonicNanos", it) }
        put("clockDomain", event.monotonicClockDomain.name)
        event.process?.let { process ->
            put("process", buildJsonObject {
                put("pid", process.pid.value)
                put("packageName", process.packageName)
                put("processInstanceId", process.instanceId.value)
            })
        }
        event.tid?.let { put("tid", it) }
        event.severity?.let { put("severity", it.name) }
        event.sourceSequence?.let { put("sourceSequence", it) }
        event.operationId?.let { put("operationId", it.value) }
        put("clockUncertaintyNanos", event.clockCalibration?.uncertaintyNanos)
        put("artifactRefs", JsonArray(event.artifactRefs.map { JsonPrimitive(it.value) }))
        put("data", event.data)
    }

    private fun timelineTypes(args: JsonObject): Set<EventType> {
        val requested = args["types"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (requested.isEmpty()) return emptySet()
        return requested.mapNotNull { value ->
            when (value.uppercase()) {
                "CRASH" -> EventType.LOG
                "DEBUG" -> EventType.EXECUTION
                "BACKEND" -> EventType.BACKEND
                else -> runCatching { EventType.valueOf(value.uppercase()) }.getOrNull()
            }
        }.toSet()
    }

    private fun diagnosticsReportJson(
        report: dev.admcp.diagnostics.DiagnosticReport,
        rawJson: Boolean,
        eventIds: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("availability", report.availability.name)
        put("rawJsonAccepted", rawJson)
        put("findings", buildJsonArray {
            report.findings.forEach { finding ->
                add(buildJsonObject {
                    put("id", finding.id)
                    put("severity", finding.severity)
                    put("message", finding.message)
                    put("source", finding.source)
                    put("confidence", finding.confidence.name)
                })
            }
        })
        put("evidence", JsonArray((report.evidence + eventIds).distinct().map(::JsonPrimitive)))
        put("summary", dev.admcp.diagnostics.AiDiagnosisFormatter.format(report).summary)
    }

    private suspend fun recordEvent(
        sessionId: SessionId,
        type: EventType,
        source: EventSource,
        data: JsonObject,
        severity: EventSeverity = EventSeverity.INFO,
        sourceSequence: Long? = null,
    ) {
        runCatching {
            eventStore.append(
                EventEnvelope(
                    id = EventId("event-" + UUID.randomUUID()),
                    sessionId = sessionId,
                    type = type,
                    source = source,
                    hostTimestamp = java.time.Instant.now(),
                    deviceTimestamp = null,
                    monotonicNanos = System.nanoTime(),
                    monotonicClockDomain = ClockDomain.HOST_MONOTONIC,
                    process = debugProcesses[sessionId],
                    tid = data.int("tid")?.toLong(),
                    severity = severity,
                    sourceSequence = sourceSequence,
                    operationId = null,
                    clockCalibration = null,
                    artifactRefs = emptyList(),
                    data = data,
                ),
            )
        }
    }

    private suspend fun recordLogRecords(sessionId: SessionId, records: List<dev.admcp.logcat.LogRecord>, kind: String) {
        val keys = timelineLogKeys.getOrPut(sessionId.value) { ConcurrentHashMap.newKeySet() }
        val process = debugProcesses[sessionId] ?: runCatching { sessions.require(sessionId).process }.getOrNull()
        records.asSequence()
            .filter { record -> kind == "CRASH" || process == null || record.pid == process.pid.value }
            .forEach { record ->
            val key = "$kind|${record.raw}"
            if (keys.size >= 10_000) keys.clear()
            if (!keys.add(key)) return@forEach
            recordEvent(
                sessionId,
                EventType.LOG,
                EventSource.LOGCAT,
                buildJsonObject {
                    put("kind", kind)
                    put("timestamp", record.timestamp)
                    put("pid", record.pid)
                    put("tid", record.tid)
                    put("level", record.level)
                    put("tag", record.tag)
                    put("message", record.message)
                    put("raw", record.raw)
                },
                severity = severityOf(record.level),
            )
        }
    }

    private fun severityOf(level: String): EventSeverity = when (level.uppercase()) {
        "F", "FATAL" -> EventSeverity.FATAL
        "E", "ERROR" -> EventSeverity.ERROR
        "W", "WARN", "WARNING" -> EventSeverity.WARN
        "I", "INFO" -> EventSeverity.INFO
        "D", "DEBUG" -> EventSeverity.DEBUG
        else -> EventSeverity.TRACE
    }

    private fun backendEventSource(backend: BackendId): EventSource = when (backend.value) {
        "JDI" -> EventSource.JDI
        "LLDB" -> EventSource.LLDB
        "DART_DAP" -> EventSource.DAP
        else -> EventSource.MCP
    }

    private fun workspaceRoot(args: JsonObject, required: Boolean = true): Path? {
        val id = args.str("workspaceId")
        if (id != null) {
            return workspaces[id] ?: if (required) throw IllegalArgumentException("unknown workspaceId: $id") else null
        }
        val path = args.str("path")
        if (path != null) return Path.of(path).toRealPath()
        config.workspaceRoot?.let { return it.toRealPath() }
        if (required) throw IllegalArgumentException("workspaceId or path is required")
        return null
    }

    private fun rememberWorkspace(root: Path): String =
        workspaceIds.computeIfAbsent(root) { key ->
            val id = "workspace-" + UUID.randomUUID()
            workspaces[id] = key
            id
        }

    private fun sessionDevice(args: JsonObject): DeviceId {
        args.str("sessionId")?.let { return sessions.require(SessionId(it)).deviceId }
        return DeviceId(args.requireString("deviceId"))
    }

    private fun sessionProcess(args: JsonObject): dev.admcp.core.model.ProcessRef? {
        args.str("sessionId")?.let { id -> sessions.require(SessionId(id)).process?.let { return it } }
        return null
    }

    private fun staleProcess(args: JsonObject, process: dev.admcp.core.model.ProcessRef): String? {
        val expected = args.str("processInstanceId") ?: return null
        return if (expected != process.instanceId.value) {
            "processInstanceId $expected does not match the attached process ${process.instanceId.value}"
        } else {
            null
        }
    }

    private fun selector(args: JsonObject): UiSelector {
        val nested = args["selector"]?.jsonObject
        if (nested != null) return UiSelector(
            resourceId = nested.str("resourceId"),
            text = nested.str("text"),
            contentDescription = nested.str("contentDescription"),
            className = nested.str("className"),
            clickable = nested.bool("clickable"),
            enabled = nested.bool("enabled"),
            exact = nested.bool("exact") ?: true,
        )
        return UiSelector(
            resourceId = args.str("resourceId"),
            text = args.str("text"),
            contentDescription = args.str("contentDescription"),
            className = args.str("className"),
            clickable = args.bool("clickable"),
            enabled = args.bool("enabled"),
            exact = args.bool("exact") ?: true,
        )
    }

    private fun resolveToolPath(kind: ToolKind, targetAbi: String?): Path? = runBlocking {
        val root = config.workspaceRoot
        val inspector = object : dev.admcp.toolchain.ExecutableInspector {
            override fun version(path: Path): String? = null
            override fun sha256(path: Path): String? = null
        }
        val resolver = dev.admcp.toolchain.ToolResolver(
            dev.admcp.toolchain.ResolverEnvironment(
                root,
                System.getenv(),
                System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map(Path::of).orEmpty(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
            ),
            inspector,
        )
        dev.admcp.toolchain.DefaultToolchainManager(resolver, inspector)
            .resolve(ToolchainRequest(root, setOf(kind), targetAbi))
            .tools
            .firstOrNull()
            ?.executable
    }

    private suspend fun deviceAbi(device: DeviceId): String {
        val result = adb.shell(device, listOf("getprop", "ro.product.cpu.abi"), Duration.ofSeconds(5))
        return result.stdoutText.trim().ifBlank { "arm64-v8a" }
    }

    private fun resolveAdb(): Path? {
        config.platformTools?.resolve(if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb")?.let {
            if (Files.isRegularFile(it)) return it
        }
        System.getenv("ANDROID_HOME")?.let { home ->
            val candidate = Path.of(home, "platform-tools", if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb")
            if (Files.isRegularFile(candidate)) return candidate
        }
        System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map(Path::of)?.forEach { dir ->
            val candidate = dir.resolve(if (System.getProperty("os.name").lowercase().contains("win")) "adb.exe" else "adb")
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    private fun ok(body: JsonObject): ToolOutcome = ToolOutcome(buildJsonObject { put("ok", true); put("data", body) })

    private fun error(code: String, message: String): ToolOutcome = ToolOutcome(
        buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject { put("code", code); put("message", message); put("retryable", false) })
        },
        isError = true,
    )

    private val denyAllApprovals = object : TrustApprovalVerifier {
        override fun verify(
            approval: dev.admcp.core.model.ExplicitUserApproval,
            subjectId: String,
            scope: Set<Path>,
        ): Boolean {
            if (approval.purpose != dev.admcp.core.model.ApprovalPurpose.WORKSPACE_TRUST) return false
            if (approval.receiptId != "startup-operator-approval") return false
            if (!approval.expiresAt.isAfter(java.time.Instant.now())) return false
            val approved = config.trustedWorkspaceRoots.mapNotNull { runCatching { it.toRealPath() }.getOrNull() }
            val subject = runCatching { Path.of(subjectId).toRealPath() }.getOrNull() ?: return false
            if (approved.none { subject.startsWith(it) }) return false
            return scope.all { it.toAbsolutePath().normalize().startsWith(subject) }
        }
    }

    private data class LogcatSession(val id: String, val deviceId: DeviceId, val capture: LogcatCaptureSession)
    private interface UiConfirmationHost {
        /** true approves, false declines, null means no host UI is available. */
        fun confirm(sessionId: SessionId, action: UiAction, risk: String, reasons: List<String>): Boolean?
    }

    private class DesktopUiConfirmationHost : UiConfirmationHost {
        override fun confirm(sessionId: SessionId, action: UiAction, risk: String, reasons: List<String>): Boolean? {
            if (java.awt.GraphicsEnvironment.isHeadless()) return null
            val message = buildString {
                append("A destructive UI action requires host approval.\n\n")
                append("Session: ").append(sessionId.value).append('\n')
                append("Action: ").append(action.kind.name)
                action.selector?.let { append("\nSelector: ").append(it) }
                action.text?.let { append("\nText: ").append(it.take(200)) }
                append("\nRisk: ").append(risk)
                if (reasons.isNotEmpty()) {
                    append("\nReasons:")
                    reasons.forEach { reason -> append("\n- ").append(reason) }
                }
                append("\n\nApprove this action?")
            }
            var approved = false
            return try {
                javax.swing.SwingUtilities.invokeAndWait {
                    val pane = javax.swing.JOptionPane(
                        message,
                        javax.swing.JOptionPane.WARNING_MESSAGE,
                        javax.swing.JOptionPane.YES_NO_OPTION,
                    )
                    val dialog = pane.createDialog(null, "admcp - destructive action")
                    val timeout = javax.swing.Timer(120_000) { dialog.dispose() }
                    timeout.isRepeats = false
                    timeout.start()
                    dialog.isVisible = true
                    timeout.stop()
                    approved = pane.value == javax.swing.JOptionPane.YES_OPTION
                    dialog.dispose()
                }
                approved
            } catch (_: Throwable) {
                null
            }
        }
    }

    private data class PendingUiConfirmation(
        val sessionId: SessionId,
        val action: UiAction,
        val expiresAt: java.time.Instant,
    )

    private data class PendingEvaluation(
        val sessionId: SessionId,
        val expression: String,
        val frameHandle: String,
        val expiresAt: java.time.Instant,
    )

    private class AdbUiAdapter(private val runner: AdbCommandRunner) : UiAdb {
        override suspend fun shell(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult =
            runner.run(listOf("-s", deviceId.value, "shell") + args, timeout)

        override suspend fun execOut(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult =
            runner.run(listOf("-s", deviceId.value, "exec-out") + args, timeout)
    }

    private class SupervisorBuildExecutor(private val runner: AdbCommandRunner, private val root: Path) : BuildExecutor {
        override suspend fun run(executable: Path, args: List<String>, workingDirectory: Path, environment: Map<String, String>): CommandResult {
            val process = dev.admcp.core.process.DefaultProcessSupervisor().start(
                dev.admcp.core.process.ProcessSpec(
                    executable,
                    args,
                    workingDirectory,
                    environment,
                    Duration.ofMinutes(20),
                    dev.admcp.core.process.StdinMode.CLOSED,
                ),
            )
            val out = kotlinx.coroutines.coroutineScope {
                val stdout = async { process.collectStdout() }
                val stderr = async { process.collectStderr() }
                val exit = process.awaitExit()
                CommandResult(exit.exitCode ?: -1, stdout.await(), stderr.await())
            }
            return out
        }
    }
}

private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content
private fun JsonObject.requireString(name: String): String =
    str(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing required string argument: $name")
private fun JsonObject.bool(name: String): Boolean? = this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.content?.toIntOrNull()
