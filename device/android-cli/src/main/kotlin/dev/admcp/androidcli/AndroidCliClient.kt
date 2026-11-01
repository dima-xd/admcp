package dev.admcp.androidcli

import dev.admcp.core.model.Availability
import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

interface AndroidCliCommandRunner {
    suspend fun run(args: List<String>, timeout: Duration): CommandResult
}

interface DeviceInventory {
    suspend fun devices(): List<DeviceState>
}

interface AndroidCliClient {
    suspend fun version(): AndroidCliVersion
    suspend fun probe(): AndroidCliCapabilities
    suspend fun execute(command: AndroidCliCommand, deviceId: DeviceId?): AndroidCliResult
}

class DefaultAndroidCliClient(
    private val executable: String,
    private val runner: AndroidCliCommandRunner,
    private val inventory: DeviceInventory,
) : AndroidCliClient {
    private val cache = ConcurrentHashMap<String, AndroidCliCapabilities>()
    private var cachedVersion: AndroidCliVersion? = null

    override suspend fun version(): AndroidCliVersion {
        cachedVersion?.let { return it }
        val result = runner.run(listOf("--version"), Duration.ofSeconds(10))
        check(result.exitCode == 0) { "android --version failed: ${result.stderrText}" }
        return AndroidCliVersion(result.stdoutText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(), result.stderrText.trim())
            .also { cachedVersion = it }
    }

    override suspend fun probe(): AndroidCliCapabilities {
        val version = version()
        val key = "$executable@${version.value}"
        cache[key]?.let { return it }
        val rootHelp = runner.run(listOf("--help"), Duration.ofSeconds(10))
        check(rootHelp.exitCode == 0) { "android --help failed: ${rootHelp.stderrText}" }
        val commands = mutableMapOf<AndroidCliCommandFamily, Availability>()
        val routing = mutableMapOf<AndroidCliCommandFamily, DeviceRoutingPolicy>()
        val evidence = mutableListOf("version=${version.value}")
        AndroidCliCommandFamily.entries.forEach { family ->
            val name = family.name.lowercase()
            val result = runner.run(listOf(name, "--help"), Duration.ofSeconds(10))
            val listed = rootHelp.stdoutText.contains(Regex("""(?m)^\s*$name\b"""))
            val available = result.exitCode == 0 && (listed || result.stdoutText.contains(name, ignoreCase = true))
            commands[family] = if (available) Availability.AVAILABLE else Availability.UNAVAILABLE
            var hasDevice = available && result.stdoutText.contains("--device")
            if (available && !hasDevice) {
                // Some families only expose --device on a subcommand.
                val subcommands = when (family) {
                    AndroidCliCommandFamily.SCREEN -> listOf(listOf("capture"))
                    AndroidCliCommandFamily.PROFILER -> listOf(listOf("systemtrace", "start"))
                    else -> emptyList()
                }
                for (subcommand in subcommands) {
                    val subResult = runner.run(listOf(name) + subcommand + listOf("--help"), Duration.ofSeconds(10))
                    if (subResult.exitCode == 0 && subResult.stdoutText.contains("--device")) {
                        hasDevice = true
                        evidence += "$name.${subcommand.joinToString(".")}=device-routing"
                        break
                    }
                }
            }
            routing[family] = when {
                !available -> DeviceRoutingPolicy.UNVERIFIED
                family == AndroidCliCommandFamily.DESCRIBE -> DeviceRoutingPolicy.NOT_DEVICE_SCOPED
                hasDevice -> DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED
                else -> DeviceRoutingPolicy.UNVERIFIED
            }
            evidence += "$name=${commands[family]}:${routing[family]}"
        }
        val capabilities = AndroidCliCapabilities(version.value, commands, routing, evidence)
        cache[key] = capabilities
        return capabilities
    }

    override suspend fun execute(command: AndroidCliCommand, deviceId: DeviceId?): AndroidCliResult {
        val capabilities = probe()
        val status = capabilities.status(command.family)
        if (status == Availability.UNAVAILABLE) {
            throw CapabilityUnavailableException(command.family, "Android CLI command family ${command.family} is unavailable")
        }
        val routing = command.deviceRouting
        if (routing == DeviceRoutingPolicy.UNVERIFIED) {
            throw CapabilityUnavailableException(command.family, "device routing for ${command.family} is unverified")
        }
        val online = inventory.devices().filter { it.state == DeviceConnectionState.ONLINE }.map { it.id }
        val target = when (routing) {
            DeviceRoutingPolicy.NOT_DEVICE_SCOPED -> {
                require(deviceId == null) { "this command is not device scoped" }
                null
            }
            DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED -> {
                requireNotNull(deviceId) { "an explicit deviceId is required" }
                require(deviceId in online) { "requested device is not online: ${deviceId.value}" }
                deviceId
            }
            DeviceRoutingPolicy.SINGLE_DEVICE_ONLY -> {
                require(online.size == 1) { "single-device command requires exactly one online device" }
                online.single()
            }
            DeviceRoutingPolicy.UNVERIFIED -> error("unreachable")
        }
        val args = buildList {
            add(command.family.name.lowercase())
            addAll(command.args)
            target?.let { add("--device=${it.value}") }
        }
        val result = runner.run(args, command.timeout)
        return AndroidCliResult(result.exitCode, result.stdout, result.stderr, routing)
    }
}
