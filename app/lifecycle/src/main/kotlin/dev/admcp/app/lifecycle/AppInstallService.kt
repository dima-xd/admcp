package dev.admcp.app.lifecycle

import dev.admcp.core.model.BuildArtifact
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.InstallResult

interface PackageInstaller {
    suspend fun install(deviceId: DeviceId, artifact: BuildArtifact): InstallResult
}

interface AppInstallOperations {
    suspend fun install(deviceId: DeviceId?, artifact: BuildArtifact, connectedDevices: List<DeviceId>): InstallResult
}

class AppInstallService(private val installer: PackageInstaller) : AppInstallOperations {
    override suspend fun install(deviceId: DeviceId?, artifact: BuildArtifact, connectedDevices: List<DeviceId>): InstallResult {
        val target = DeviceTargeting.requireExplicit(deviceId, connectedDevices)
        return installer.install(target, artifact)
    }
}

object DeviceTargeting {
    fun requireExplicit(deviceId: DeviceId?, connected: List<DeviceId>): DeviceId {
        require(connected.isNotEmpty()) { "no connected devices" }
        requireNotNull(deviceId) { "an explicit deviceId is required" }
        require(deviceId in connected) { "requested device is not connected: ${deviceId.value}" }
        return deviceId
    }

    fun requireBuildReference(reference: String?): String {
        require(!reference.isNullOrBlank()) { "an explicit build reference is required" }
        return reference
    }
}
