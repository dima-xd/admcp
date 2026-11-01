package dev.admcp.toolchain

import dev.admcp.core.model.Availability
import dev.admcp.core.model.Capability
import dev.admcp.core.model.CapabilityId
import dev.admcp.core.model.ResolvedTool
import dev.admcp.core.model.ToolKind

class CapabilityProbe(private val inspector: ExecutableInspector) {
    suspend fun probe(tool: ResolvedTool): List<Capability> {
        val version = inspector.version(tool.executable)
        return listOf(
            Capability(
                name = CapabilityId("tool.${tool.kind.name.lowercase()}"),
                availability = if (version.isNullOrBlank()) Availability.LIMITED else Availability.AVAILABLE,
                evidence = listOf("${tool.provenance}:${tool.executable}", "version=${version ?: "unknown"}"),
                reason = if (version.isNullOrBlank()) "runtime version probe did not return a version" else null,
                backendVersion = version,
            ),
        )
    }

    suspend fun missing(kind: ToolKind, reason: String): Capability = Capability(
        name = CapabilityId("tool.${kind.name.lowercase()}"),
        availability = Availability.UNAVAILABLE,
        evidence = listOf("not found by deterministic resolver"),
        reason = reason,
    )
}
