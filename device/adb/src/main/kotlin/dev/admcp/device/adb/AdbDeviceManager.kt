package dev.admcp.device.adb

import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.DisplaySnapshot
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration

class AdbDeviceManager(private val client: AdbClient) : DeviceManager {
    override fun observeDevices(): Flow<List<DeviceSnapshot>> = flow {
        while (true) {
            emit(client.devices().filter { it.state == DeviceConnectionState.ONLINE }.map { inspect(it.id) })
            delay(2_000)
        }
    }

    override suspend fun inspect(deviceId: DeviceId): DeviceSnapshot {
        val model = prop(deviceId, "ro.product.model")
        val manufacturer = prop(deviceId, "ro.product.manufacturer")
        val api = prop(deviceId, "ro.build.version.sdk")?.toIntOrNull()
        val abis = prop(deviceId, "ro.product.cpu.abilist")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val fingerprint = prop(deviceId, "ro.build.fingerprint")
        val size = shell(deviceId, listOf("wm", "size"))?.lineSequence()
            ?.firstOrNull { it.contains(':') }
            ?.substringAfter(':')?.trim()
        val density = shell(deviceId, listOf("wm", "density"))?.lineSequence()
            ?.firstOrNull { it.contains(':') }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toFloatOrNull()
        val display = size?.let(::parseDisplay)?.copy(density = density ?: 0f)
        return DeviceSnapshot(deviceId, DeviceConnectionState.ONLINE, model, manufacturer, api, abis, fingerprint, display)
    }

    override suspend fun processes(deviceId: DeviceId): List<ProcessSnapshot> {
        val output = client.shell(deviceId, listOf("ps", "-A", "-o", "PID,NAME"), Duration.ofSeconds(15))
        if (output.exitCode != 0) return emptyList()
        return output.stdoutText.lineSequence().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val pid = parts.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
            val name = parts.getOrNull(1) ?: return@mapNotNull null
            ProcessSnapshot(
                ref = ProcessRef(deviceId, ProcessId(pid), name, ProcessInstances.next(), null),
                processName = name,
                debuggable = null,
            )
        }.toList()
    }

    private suspend fun prop(deviceId: DeviceId, name: String): String? {
        val result = client.shell(deviceId, listOf("getprop", name), Duration.ofSeconds(5))
        return if (result.exitCode == 0) result.stdoutText.trim().ifBlank { null } else null
    }

    private suspend fun shell(deviceId: DeviceId, args: List<String>): String? {
        val result = client.shell(deviceId, args, Duration.ofSeconds(5))
        return if (result.exitCode == 0) result.stdoutText else null
    }

    private fun parseDisplay(value: String): DisplaySnapshot? {
        val parts = value.lowercase().split('x')
        val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return DisplaySnapshot(0, width, height, 0f, 0)
    }
}
