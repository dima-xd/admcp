package dev.admcp.core.model

import java.time.Instant

enum class Availability { AVAILABLE, LIMITED, UNAVAILABLE, UNKNOWN, FAILED }

data class Capability(
    val name: CapabilityId,
    val availability: Availability,
    val evidence: List<String>,
    val reason: String? = null,
    val backendVersion: String? = null,
)

data class CapabilitySubject(
    val deviceId: DeviceId?,
    val sessionId: SessionId?,
    val process: ProcessRef?,
)

data class CapabilitySnapshot(
    val subject: CapabilitySubject,
    val capabilities: List<Capability>,
    val probedAt: Instant,
)

data class ToolchainRequest(
    val workspaceRoot: java.nio.file.Path?,
    val requiredKinds: Set<ToolKind>,
    val targetAbi: String?,
)

data class ToolchainReport(
    val tools: List<ResolvedTool>,
    val capabilities: List<Capability>,
    val errors: List<AdmcpError>,
)

enum class ToolKind {
    JDK, ADB, ANDROID_CLI, GRADLE, ANDROID_SDK, BUILD_TOOLS, NDK, CMAKE, NINJA,
    LLDB, LLDB_DAP, LLDB_SERVER, FLUTTER, DART, SQLITE,
}

data class ResolvedTool(
    val kind: ToolKind,
    val executable: java.nio.file.Path,
    val version: String?,
    val hostOs: String,
    val hostArch: String,
    val targetAbi: String?,
    val provenance: String,
    val sha256: String?,
)
