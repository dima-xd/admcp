package dev.admcp.profiler

import dev.admcp.core.model.Availability
import dev.admcp.core.model.Capability
import dev.admcp.core.model.CapabilityId
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProfileRequest
import dev.admcp.core.model.ProfileSession
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class ProfileCapturePlan(
    val deviceId: DeviceId,
    val command: List<String>,
    val duration: Duration,
    val outputName: String,
    val availability: Availability,
    val evidence: List<String>,
)

class ProfilePlanner {
    fun plan(request: ProfileRequest, perfettoAvailable: Boolean): ProfileCapturePlan {
        if (!perfettoAvailable) {
            return ProfileCapturePlan(
                request.deviceId, emptyList(), request.duration ?: Duration.ofSeconds(5), "", Availability.UNAVAILABLE,
                listOf("perfetto was not observed on the device"),
            )
        }
        val duration = request.duration ?: Duration.ofSeconds(5)
        require(!duration.isNegative && duration <= Duration.ofMinutes(10)) { "profile duration must be between 0 and 10 minutes" }
        val config = when (request.kind.lowercase()) {
            "cpu" -> "sched freq idle am wm gfx view binder_driver"
            "heap" -> "memory"
            else -> throw IllegalArgumentException("unsupported profile kind: ${request.kind}")
        }
        return ProfileCapturePlan(
            request.deviceId,
            listOf("perfetto", "-o", "/data/local/tmp/admcp-${UUID.randomUUID()}.perfetto", "-t", "${duration.seconds}s", "-a", "com.android.", "--categories", config),
            duration,
            "trace.perfetto",
            Availability.AVAILABLE,
            listOf("perfetto probe succeeded"),
        )
    }
}

class ProfileManager(private val planner: ProfilePlanner = ProfilePlanner()) {
    fun start(request: ProfileRequest, perfettoAvailable: Boolean): ProfileSession {
        val plan = planner.plan(request, perfettoAvailable)
        require(plan.availability == Availability.AVAILABLE) { "profiling is unavailable: ${plan.evidence.joinToString()}" }
        return ProfileSession("profile-" + UUID.randomUUID(), request.deviceId, request.kind, "RUNNING", Instant.now())
    }
}

fun perfettoCapability(available: Boolean, evidence: List<String>): Capability = Capability(
    CapabilityId("profiler.perfetto"),
    if (available) Availability.AVAILABLE else Availability.UNAVAILABLE,
    evidence,
    if (available) null else "perfetto unavailable or unverified",
)
