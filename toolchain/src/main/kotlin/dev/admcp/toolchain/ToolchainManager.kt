package dev.admcp.toolchain

import dev.admcp.core.model.AdmcpError
import dev.admcp.core.model.Capability
import dev.admcp.core.model.ResolvedTool
import dev.admcp.core.model.ToolchainReport
import dev.admcp.core.model.ToolchainRequest
import dev.admcp.core.model.ToolKind

interface ToolchainManager {
    suspend fun resolve(request: ToolchainRequest): ToolchainReport
    suspend fun probe(tool: ResolvedTool): List<Capability>
}

class DefaultToolchainManager(
    private val resolver: ToolResolver,
    private val inspector: ExecutableInspector,
) : ToolchainManager {
    private val probe = CapabilityProbe(inspector)

    override suspend fun resolve(request: ToolchainRequest): ToolchainReport {
        val tools = mutableListOf<ResolvedTool>()
        val capabilities = mutableListOf<Capability>()
        val errors = mutableListOf<AdmcpError>()
        request.requiredKinds.forEach { kind ->
            val resolved = resolver.resolve(kind, request.targetAbi)
            if (resolved == null) {
                errors += AdmcpError.of(
                    "TOOL_UNAVAILABLE",
                    "${kind.name} was not found; tools are never installed or updated automatically",
                    retryable = true,
                )
                capabilities += probe.missing(kind, "not found")
            } else {
                tools += resolved
                capabilities += probe.probe(resolved)
            }
        }
        return ToolchainReport(tools, capabilities, errors)
    }

    override suspend fun probe(tool: ResolvedTool): List<Capability> = probe.probe(tool)
}
