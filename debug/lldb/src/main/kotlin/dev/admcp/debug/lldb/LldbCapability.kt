package dev.admcp.debug.lldb

import dev.admcp.core.model.Availability
import dev.admcp.core.model.Capability
import dev.admcp.core.model.CapabilityId
import dev.admcp.core.model.ResolvedTool
import dev.admcp.core.model.ToolKind
import java.nio.file.Files
import java.nio.file.Path

data class LldbAvailability(
    val host: Availability,
    val adapter: Availability,
    val server: Availability,
    val abi: String?,
    val evidence: List<String>,
) {
    val nativeDebuggingAvailable: Boolean get() = host == Availability.AVAILABLE && adapter == Availability.AVAILABLE && server == Availability.AVAILABLE
}

class LldbCapabilityProbe {
    fun probe(hostLldb: Path?, hostDap: Path?, server: Path?, targetAbi: String?): LldbAvailability {
        val host = available(hostLldb, "LLDB")
        val adapter = available(hostDap, "LLDB DAP")
        val serverAvailability = if (server == null) Availability.UNAVAILABLE else available(server, "lldb-server")
        val abiEvidence = targetAbi?.let { abi ->
            server?.toString()?.replace('\\', '/')?.lowercase()?.let { path ->
                val expected = abiToken(abi)
                if (path.contains("/$expected/") || path.endsWith("/$expected/lldb-server")) Availability.AVAILABLE else Availability.LIMITED
            }
        } ?: Availability.UNKNOWN
        return LldbAvailability(
            host,
            adapter,
            if (serverAvailability == Availability.AVAILABLE) abiEvidence ?: Availability.UNKNOWN else serverAvailability,
            targetAbi,
            listOf("host=$host", "adapter=$adapter", "server=$serverAvailability", "abi=$abiEvidence"),
        )
    }

    private fun available(path: Path?, label: String): Availability =
        if (path != null && Files.isRegularFile(path)) Availability.AVAILABLE else Availability.UNAVAILABLE

    private fun abiToken(abi: String): String = when (abi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> abi
    }
}

fun LldbAvailability.toCapabilities(): List<Capability> = listOf(
    Capability(CapabilityId("lldb.host"), host, evidence, if (host == Availability.AVAILABLE) null else "host lldb is missing"),
    Capability(CapabilityId("lldb.dap"), adapter, evidence, if (adapter == Availability.AVAILABLE) null else "lldb-dap is missing"),
    Capability(CapabilityId("lldb.server"), server, evidence, if (server == Availability.AVAILABLE) null else "ABI-matched lldb-server is missing or unverified"),
)

object LldbToolKinds {
    val required: Set<ToolKind> = setOf(ToolKind.LLDB, ToolKind.LLDB_DAP, ToolKind.LLDB_SERVER)
}

fun resolvedOrNull(tools: List<ResolvedTool>, kind: ToolKind): Path? = tools.firstOrNull { it.kind == kind }?.executable
