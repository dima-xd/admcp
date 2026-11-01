package dev.admcp.androidcli

import dev.admcp.core.model.CapabilityId
import dev.admcp.core.model.DeviceId
import java.time.Duration

enum class AndroidCliCommandFamily {
    AUTH, BUILD, CREATE, DESCRIBE, DEVICE, DOCS, EMULATOR, INFO, INIT, INPUT, INSTALL, LAYOUT,
    PREVIEW, PROFILER, RUN, SCREEN, SDK, SIGNALS, SKILLS, STUDIO, ANALYZE,
}

enum class DeviceRoutingPolicy { NOT_DEVICE_SCOPED, EXPLICIT_DEVICE_REQUIRED, SINGLE_DEVICE_ONLY, UNVERIFIED }

data class AndroidCliCommand(
    val family: AndroidCliCommandFamily,
    val args: List<String>,
    val deviceId: DeviceId?,
    val deviceRouting: DeviceRoutingPolicy,
    val requiredCapabilities: Set<CapabilityId>,
    val timeout: Duration,
)

data class AndroidCliResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val routingUsed: DeviceRoutingPolicy,
) {
    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
    val stderrText: String get() = stderr.toString(Charsets.UTF_8)
}

class CapabilityUnavailableException(val family: AndroidCliCommandFamily, message: String) : RuntimeException(message)

data class DeviceState(val id: DeviceId, val state: dev.admcp.core.model.DeviceConnectionState)
