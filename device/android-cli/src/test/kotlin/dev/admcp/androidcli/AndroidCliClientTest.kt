package dev.admcp.androidcli

import dev.admcp.core.model.Availability
import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidCliClientTest {
    @Test
    fun parsesVersion() = runBlocking {
        val client = client(FakeRunner(mapOf("--version" to ok("1.0.16261425\n"))))
        assertEquals("1.0.16261425", client.version().value)
    }

    @Test
    fun probesCommandsIndividually() = runBlocking {
        val runner = FakeRunner(helpWith("describe", "install", "run", "layout", "screen"))
        val capabilities = client(runner).probe()
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.DESCRIBE))
        assertEquals(Availability.UNAVAILABLE, capabilities.status(AndroidCliCommandFamily.INPUT))
        assertTrue(runner.commands.contains("input --help"))
    }

    @Test
    fun probesBuildDescribeInstallRun() = runBlocking {
        val capabilities = client(FakeRunner(helpWith("describe", "install", "run"))).probe()
        assertEquals(Availability.UNAVAILABLE, capabilities.status(AndroidCliCommandFamily.BUILD))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.DESCRIBE))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.INSTALL))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.RUN))
    }

    @Test
    fun probesAndroidCli11CommandFamilies() = runBlocking {
        val capabilities = client(FakeRunner(helpWith("docs", "emulator", "info", "init", "sdk", "skills", "studio"))).probe()
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.DOCS))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.EMULATOR))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.INFO))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.INIT))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.SDK))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.SKILLS))
        assertEquals(Availability.AVAILABLE, capabilities.status(AndroidCliCommandFamily.STUDIO))
    }

    @Test
    fun probesDeviceRoutingOnSubcommandsForScreenAndProfiler() = runBlocking {
        val runner = FakeRunner(
            mapOf(
                "--version" to ok("1.1.16717390\n"),
                "--help" to ok("Usage: android\n  screen\n  profiler\n"),
                "screen --help" to ok("Usage: android screen [COMMAND]\n  capture\n"),
                "screen capture --help" to ok("Usage: android screen capture [--device=PARAM]\n"),
                "profiler --help" to ok("Usage: android profiler [COMMAND]\n  systemtrace\n"),
                "profiler systemtrace --help" to ok("Usage: android profiler systemtrace [COMMAND]\n  start\n"),
                "profiler systemtrace start --help" to ok("Usage: android profiler systemtrace start [--device=PARAM]\n"),
            ),
        )
        val capabilities = client(runner).probe()
        assertEquals(DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED, capabilities.routing(AndroidCliCommandFamily.SCREEN))
        assertEquals(DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED, capabilities.routing(AndroidCliCommandFamily.PROFILER))
        assertTrue(capabilities.evidence.any { it.contains("screen.capture=device-routing") })
        assertTrue(capabilities.evidence.any { it.contains("profiler.systemtrace.start=device-routing") })
    }

    @Test
    fun doesNotAssumeUnsupportedFlag() = runBlocking {
        val runner = FakeRunner(
            mapOf(
                "--version" to ok("1.0\n"),
                "--help" to ok("Usage: android\n  describe\n  install\n  run\n  layout\n"),
                "describe --help" to ok("Usage: android describe [--project_dir=PARAM]"),
                "install --help" to ok("Usage: android install [--apks=PARAM] [--device=PARAM]"),
                "run --help" to ok("Usage: android run [--device=PARAM]"),
                "layout --help" to ok("Usage: android layout [--device=PARAM] [--full]"),
            ),
        )
        val client = client(runner)
        client.probe()
        assertFailsWith<CapabilityUnavailableException> {
            client.execute(AndroidCliCommand(AndroidCliCommandFamily.DESCRIBE, listOf("--project_dir=."), DeviceId("d1"), DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED, emptySet(), Duration.ofSeconds(5)), DeviceId("d1"))
        }
    }

    @Test
    fun usesExplicitDeviceWhenSupportedForEachCommandFamily() = runBlocking {
        val runner = FakeRunner(helpWith("install", "run", "layout"))
        val client = client(runner)
        client.probe()
        client.execute(command(AndroidCliCommandFamily.INSTALL, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), DeviceId("d1"))
        client.execute(command(AndroidCliCommandFamily.RUN, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), DeviceId("d1"))
        client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), DeviceId("d1"))
        assertTrue(runner.commands.filter { it.startsWith("install ") && !it.contains("--help") }.all { it.contains("--device=d1") })
        assertTrue(runner.commands.filter { it.startsWith("run ") && !it.contains("--help") }.all { it.contains("--device=d1") })
        assertTrue(runner.commands.filter { it.startsWith("layout ") && !it.contains("--help") }.all { it.contains("--device=d1") })
    }

    @Test
    fun ignoresOfflineAndUnauthorizedDevicesWhenResolvingExplicitTarget() = runBlocking {
        val inventory = FakeInventory(listOf(DeviceId("online") to DeviceConnectionState.ONLINE, DeviceId("bad") to DeviceConnectionState.UNAUTHORIZED))
        val client = client(FakeRunner(helpWith("layout")), inventory)
        client.probe()
        assertFailsWith<IllegalArgumentException> {
            client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), DeviceId("bad"))
        }
        client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), DeviceId("online"))
    }

    @Test
    fun failsClosedForAmbiguousMultiDeviceCommand() = runBlocking {
        val inventory = FakeInventory(listOf(DeviceId("d1") to DeviceConnectionState.ONLINE, DeviceId("d2") to DeviceConnectionState.ONLINE))
        val client = client(FakeRunner(helpWith("layout")), inventory)
        client.probe()
        assertFailsWith<IllegalArgumentException> {
            client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.SINGLE_DEVICE_ONLY), null)
        }
    }

    @Test
    fun singleDeviceFallbackIsExplicitlyReported() = runBlocking {
        val inventory = FakeInventory(listOf(DeviceId("d1") to DeviceConnectionState.ONLINE))
        val client = client(FakeRunner(helpWith("layout")), inventory)
        val result = client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.SINGLE_DEVICE_ONLY), null)
        assertEquals(0, result.exitCode)
        assertEquals(DeviceRoutingPolicy.SINGLE_DEVICE_ONLY, result.routingUsed)
    }

    @Test
    fun neverUsesImplicitFirstDevice() = runBlocking {
        val inventory = FakeInventory(listOf(DeviceId("d1") to DeviceConnectionState.ONLINE, DeviceId("d2") to DeviceConnectionState.ONLINE))
        val client = client(FakeRunner(helpWith("layout")), inventory)
        assertFailsWith<IllegalArgumentException> {
            client.execute(command(AndroidCliCommandFamily.LAYOUT, DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED), null)
        }
    }

    @Test
    fun separatesStdoutAndStderr() = runBlocking {
        val runner = FakeRunner(mapOf("--version" to CommandResult(0, "1.0\n".toByteArray(), "warn\n".toByteArray())))
        val version = client(runner).version()
        assertEquals("warn", version.stderr)
    }

    @Test
    fun returnsUnsupportedCapabilityWithoutLaunchingCommand() = runBlocking {
        val runner = FakeRunner(helpWith("describe"))
        val client = client(runner)
        client.probe()
        val before = runner.commands.size
        assertFailsWith<CapabilityUnavailableException> {
            client.execute(command(AndroidCliCommandFamily.BUILD, DeviceRoutingPolicy.NOT_DEVICE_SCOPED), null)
        }
        assertEquals(before, runner.commands.size)
    }

    private fun command(family: AndroidCliCommandFamily, routing: DeviceRoutingPolicy) =
        AndroidCliCommand(family, emptyList(), null, routing, emptySet(), Duration.ofSeconds(5))

    private fun client(runner: AndroidCliCommandRunner, inventory: DeviceInventory = FakeInventory(listOf(DeviceId("d1") to DeviceConnectionState.ONLINE))) =
        DefaultAndroidCliClient("android", runner, inventory)

    private fun ok(text: String) = CommandResult(0, text.toByteArray(), ByteArray(0))

    private fun helpWith(vararg commands: String): Map<String, CommandResult> {
        val root = commands.joinToString("\n") { "  $it" }
        return buildMap {
            put("--version", ok("1.0.16261425\n"))
            put("--help", ok("Usage: android\n$root\n"))
            commands.forEach { put("$it --help", ok("Usage: android $it [--device=PARAM]\n")) }
        }
    }

    private class FakeRunner(private val responses: Map<String, CommandResult>) : AndroidCliCommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(args: List<String>, timeout: Duration): CommandResult {
            val key = args.joinToString(" ")
            commands += key
            return responses[key] ?: CommandResult(0, ByteArray(0), ByteArray(0))
        }
    }

    private class FakeInventory(private val devices: List<Pair<DeviceId, DeviceConnectionState>>) : DeviceInventory {
        override suspend fun devices(): List<DeviceState> = devices.map { DeviceState(it.first, it.second) }
    }
}
