package dev.admcp.androidcli

import dev.admcp.core.model.CommandResult
import dev.admcp.core.process.DefaultProcessSupervisor
import dev.admcp.core.process.ProcessSpec
import dev.admcp.core.process.ProcessSupervisor
import dev.admcp.core.process.StdinMode
import dev.admcp.core.process.collectStderr
import dev.admcp.core.process.collectStdout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.nio.file.Path
import java.time.Duration

class SupervisorAndroidCliRunner(
    private val executable: Path,
    private val supervisor: ProcessSupervisor = DefaultProcessSupervisor(),
) : AndroidCliCommandRunner {
    override suspend fun run(args: List<String>, timeout: Duration): CommandResult = coroutineScope {
        val process = supervisor.start(
            ProcessSpec(
                executable = executable,
                args = args,
                workingDirectory = null,
                environment = System.getenv().filterKeys { it in setOf("PATH", "SystemRoot", "TEMP", "TMP", "HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT") },
                timeout = timeout,
                stdinMode = StdinMode.CLOSED,
            ),
        )
        val out = async { process.collectStdout() }
        val err = async { process.collectStderr() }
        val exit = process.awaitExit()
        CommandResult(exit.exitCode ?: -1, out.await(), err.await())
    }
}
