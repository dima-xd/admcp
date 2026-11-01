package dev.admcp.toolchain

import dev.admcp.core.model.ToolKind
import java.nio.file.Path

data class ToolDescriptor(
    val kind: ToolKind,
    val executableName: String,
    val versionArguments: List<String> = listOf("--version"),
)

data class ResolverEnvironment(
    val workspaceRoot: Path?,
    val environment: Map<String, String>,
    val pathDirectories: List<Path>,
    val hostOs: String,
    val hostArch: String,
)

interface ExecutableInspector {
    fun version(path: Path): String?
    fun sha256(path: Path): String?
}
