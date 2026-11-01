package dev.admcp.ui.androidcli

import dev.admcp.androidcli.AndroidCliClient
import dev.admcp.androidcli.AndroidCliCommand
import dev.admcp.androidcli.AndroidCliCommandFamily
import dev.admcp.androidcli.CapabilityUnavailableException
import dev.admcp.androidcli.DeviceRoutingPolicy
import dev.admcp.core.model.Availability
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.UiActionResult
import dev.admcp.core.model.UiElement
import dev.admcp.core.model.UiSnapshot
import dev.admcp.ui.PlannedAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

class AndroidCliLayoutBackend(
    private val client: AndroidCliClient,
    private val capabilities: AndroidCliCapabilitiesView,
) {
    val id: String = "android-cli-layout"

    suspend fun availability(): Availability = capabilities.layoutAvailability()

    suspend fun snapshot(deviceId: DeviceId, generation: Long): UiSnapshot {
        if (availability() == Availability.UNAVAILABLE) {
            throw CapabilityUnavailableException(AndroidCliCommandFamily.LAYOUT, "android layout is unavailable")
        }
        val result = client.execute(
            AndroidCliCommand(
                AndroidCliCommandFamily.LAYOUT,
                listOf("--pretty", "--full"),
                deviceId,
                DeviceRoutingPolicy.EXPLICIT_DEVICE_REQUIRED,
                emptySet(),
                Duration.ofSeconds(30),
            ),
            deviceId,
        )
        check(result.exitCode == 0) { "android layout failed: ${result.stderrText}" }
        return UiSnapshot(deviceId, Instant.now(), generation, emptyList(), buildJsonObject { put("raw", result.stdoutText) })
    }

    suspend fun perform(deviceId: DeviceId, plan: PlannedAction, generation: Long): UiActionResult =
        UiActionResult(false, plan.matched, false, null, "android layout backend is read-only; actions use uiautomator or adb input")
}

fun interface AndroidCliCapabilitiesView {
    fun layoutAvailability(): Availability
}

object AndroidCliBackendFactory {
    fun from(capabilities: dev.admcp.androidcli.AndroidCliCapabilities): AndroidCliCapabilitiesView =
        AndroidCliCapabilitiesView { capabilities.status(AndroidCliCommandFamily.LAYOUT) }
}
