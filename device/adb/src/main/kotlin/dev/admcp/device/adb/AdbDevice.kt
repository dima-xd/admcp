package dev.admcp.device.adb

import dev.admcp.core.model.DebugHandle
import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.DisplaySnapshot
import dev.admcp.core.model.ProcessRef

data class AdbDevice(
    val id: DeviceId,
    val state: DeviceConnectionState,
    val model: String? = null,
    val product: String? = null,
    val transportId: String? = null,
)

data class DeviceSnapshot(
    val id: DeviceId,
    val state: DeviceConnectionState,
    val model: String?,
    val manufacturer: String?,
    val apiLevel: Int?,
    val abis: List<String>,
    val fingerprint: String?,
    val display: DisplaySnapshot?,
)

data class ProcessSnapshot(val ref: ProcessRef, val processName: String, val debuggable: Boolean?)

data class ForwardHandle(val deviceId: DeviceId, val local: String, val remote: String)

interface DeviceManager {
    fun observeDevices(): kotlinx.coroutines.flow.Flow<List<DeviceSnapshot>>
    suspend fun inspect(deviceId: DeviceId): DeviceSnapshot
    suspend fun processes(deviceId: DeviceId): List<ProcessSnapshot>
}

typealias DebugThreadHandle = DebugHandle
