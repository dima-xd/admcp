package dev.admcp.device.adb

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import java.time.Duration

class AdbShell(private val client: AdbClient) {
    suspend fun run(deviceId: DeviceId, vararg args: String): CommandResult =
        client.shell(deviceId, args.toList(), Duration.ofSeconds(30))

    suspend fun getprop(deviceId: DeviceId, property: String): String? {
        val result = run(deviceId, "getprop", property)
        return if (result.exitCode == 0) result.stdoutText.trim().ifBlank { null } else null
    }
}
