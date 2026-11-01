package dev.admcp.app.lifecycle

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.device.adb.ProcessSnapshot
import java.util.UUID

interface LaunchAdb {
    suspend fun shell(deviceId: DeviceId, args: List<String>): CommandResult
}

interface AppLaunchOperations {
    suspend fun launch(
        deviceId: DeviceId,
        packageName: String,
        activity: String?,
        waitForDebugger: Boolean = false,
    ): ProcessSnapshot
}

class AppLaunchService(private val adb: LaunchAdb) : AppLaunchOperations {
    override suspend fun launch(
        deviceId: DeviceId,
        packageName: String,
        activity: String?,
        waitForDebugger: Boolean,
    ): ProcessSnapshot {
        require(packageName.isNotBlank()) { "packageName is required" }
        val component = activity?.takeIf { it.isNotBlank() }?.let { "$packageName/$it" } ?: "$packageName/.MainActivity"
        val arguments = if (waitForDebugger) {
            listOf("am", "start", "-D", "-n", component)
        } else {
            listOf("am", "start", "-W", "-n", component)
        }
        val start = adb.shell(deviceId, arguments)
        check(start.exitCode == 0) { "launch failed: ${start.stderrText}" }
        val pidResult = adb.shell(deviceId, listOf("pidof", packageName))
        val pid = pidResult.stdoutText.trim().substringBefore(' ').toIntOrNull()
            ?: error("package did not report a process after launch")
        val startToken = adb.shell(deviceId, listOf("cat", "/proc/$pid/stat")).stdoutText.takeIf { it.isNotBlank() }
        val ref = ProcessRef(deviceId, ProcessId(pid), packageName, ProcessInstanceId("pi-" + UUID.randomUUID()), startToken)
        return ProcessSnapshot(ref, packageName, true)
    }
}
