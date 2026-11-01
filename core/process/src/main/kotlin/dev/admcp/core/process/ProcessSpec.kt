package dev.admcp.core.process

import java.nio.file.Path
import java.time.Duration

enum class StdinMode { CLOSED, PIPE }

data class ProcessSpec(
    val executable: Path,
    val args: List<String>,
    val workingDirectory: Path?,
    val environment: Map<String, String>,
    val timeout: Duration?,
    val stdinMode: StdinMode,
    val outputLimitBytes: Int = 16 * 1024 * 1024,
)

data class ProcessExit(val exitCode: Int?, val timedOut: Boolean, val cancelled: Boolean)
