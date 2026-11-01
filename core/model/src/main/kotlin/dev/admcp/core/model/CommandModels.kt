package dev.admcp.core.model

data class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray) {
    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
    val stderrText: String get() = stderr.toString(Charsets.UTF_8)
}
