package dev.admcp.device.adb

import dev.admcp.core.model.CommandResult
import dev.admcp.core.process.DefaultProcessSupervisor
import dev.admcp.core.process.ManagedProcess
import dev.admcp.core.process.ProcessSpec
import dev.admcp.core.process.ProcessSupervisor
import dev.admcp.core.process.StdinMode
import dev.admcp.core.process.collectStderr
import dev.admcp.core.process.collectStdout
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class SupervisorAdbRunner(
    private val executable: Path,
    private val supervisor: ProcessSupervisor = DefaultProcessSupervisor(),
) : AdbCommandRunner {
    override suspend fun run(args: List<String>, timeout: Duration): CommandResult = coroutineScope {
        val process: ManagedProcess = supervisor.start(
            ProcessSpec(
                executable = executable,
                args = args,
                workingDirectory = null,
                environment = System.getenv().filterKeys { it in setOf("PATH", "SystemRoot", "TEMP", "TMP", "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT") },
                timeout = timeout,
                stdinMode = StdinMode.CLOSED,
            ),
        )
        val stdout = async { process.collectStdout() }
        val stderr = async { process.collectStderr() }
        val exit = process.awaitExit()
        CommandResult(exit.exitCode ?: -1, stdout.await(), stderr.await())
    }
}
