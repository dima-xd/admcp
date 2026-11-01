package dev.admcp.androidcli

import dev.admcp.core.model.Availability

data class AndroidCliCapabilities(
    val version: String,
    val commands: Map<AndroidCliCommandFamily, Availability>,
    val routing: Map<AndroidCliCommandFamily, DeviceRoutingPolicy>,
    val evidence: List<String>,
) {
    fun status(family: AndroidCliCommandFamily): Availability = commands[family] ?: Availability.UNAVAILABLE
    fun routing(family: AndroidCliCommandFamily): DeviceRoutingPolicy = routing[family] ?: DeviceRoutingPolicy.UNVERIFIED
}
