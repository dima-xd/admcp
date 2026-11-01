package dev.admcp.device.adb

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import java.time.Duration

interface AdbCommandRunner {
    suspend fun run(args: List<String>, timeout: Duration): CommandResult
}

interface AdbClient {
    suspend fun devices(): List<AdbDevice>
    suspend fun shell(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult
    suspend fun forward(deviceId: DeviceId, local: String, remote: String): ForwardHandle
    suspend fun removeForward(handle: ForwardHandle)
}

class DefaultAdbClient(
    private val executable: String,
    private val runner: AdbCommandRunner,
) : AdbClient {
    override suspend fun devices(): List<AdbDevice> {
        val result = runner.run(listOf("devices", "-l"), Duration.ofSeconds(15))
        check(result.exitCode == 0) { "adb devices failed: ${result.stderrText}" }
        return AdbParser.parseDevices(result.stdoutText)
    }

    override suspend fun shell(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult {
        require(args.isNotEmpty()) { "adb shell requires an argument list" }
        return runner.run(listOf("-s", deviceId.value, "shell") + args, timeout)
    }

    override suspend fun forward(deviceId: DeviceId, local: String, remote: String): ForwardHandle {
        val result = runner.run(listOf("-s", deviceId.value, "forward", local, remote), Duration.ofSeconds(15))
        check(result.exitCode == 0) { "adb forward failed: ${result.stderrText}" }
        val resolvedLocal = result.stdoutText.trim().ifBlank { local }.let {
            if (it.all(Char::isDigit)) "tcp:$it" else it
        }
        return ForwardHandle(deviceId, resolvedLocal, remote)
    }

    override suspend fun removeForward(handle: ForwardHandle) {
        runner.run(listOf("-s", handle.deviceId.value, "forward", "--remove", handle.local), Duration.ofSeconds(15))
    }
}

object AdbParser {
    fun parseDevices(output: String): List<AdbDevice> = output.lineSequence()
        .dropWhile { it.isBlank() || it.startsWith("List of devices") || it.startsWith("*") }
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val attributes = parts.drop(2).mapNotNull { token ->
                val index = token.indexOf(':')
                if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
            }.toMap()
            AdbDevice(
                id = DeviceId(parts[0]),
                state = parseState(parts[1]),
                model = attributes["model"]?.replace('_', ' '),
                product = attributes["product"],
                transportId = attributes["transport_id"],
            )
        }.toList()

    private fun parseState(value: String): dev.admcp.core.model.DeviceConnectionState = when (value) {
        "device" -> dev.admcp.core.model.DeviceConnectionState.ONLINE
        "offline" -> dev.admcp.core.model.DeviceConnectionState.OFFLINE
        "unauthorized" -> dev.admcp.core.model.DeviceConnectionState.UNAUTHORIZED
        "recovery" -> dev.admcp.core.model.DeviceConnectionState.RECOVERY
        "bootloader" -> dev.admcp.core.model.DeviceConnectionState.BOOTLOADER
        "sideload" -> dev.admcp.core.model.DeviceConnectionState.SIDELOAD
        "no permissions" -> dev.admcp.core.model.DeviceConnectionState.NO_PERMISSIONS
        else -> dev.admcp.core.model.DeviceConnectionState.UNKNOWN
    }
}

object ProcessInstances {
    fun next(): dev.admcp.core.model.ProcessInstanceId =
        dev.admcp.core.model.ProcessInstanceId("pi-" + java.util.UUID.randomUUID())
}
