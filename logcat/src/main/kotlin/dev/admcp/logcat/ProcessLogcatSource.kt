package dev.admcp.logcat

import dev.admcp.core.model.DeviceId
import dev.admcp.core.process.DefaultProcessSupervisor
import dev.admcp.core.process.ProcessSpec
import dev.admcp.core.process.ProcessSupervisor
import dev.admcp.core.process.StdinMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Duration

class ProcessLogcatSource(
    private val adb: Path,
    private val deviceId: DeviceId,
    private val buffers: Set<String>,
    private val supervisor: ProcessSupervisor = DefaultProcessSupervisor(),
) : LogcatSource {
    override fun open(): Flow<ByteArray> = flow {
        val args = buildList {
            add("-s")
            add(deviceId.value)
            add("logcat")
            add("-v")
            add("threadtime")
            add("-T")
            add("1")
            buffers.forEach { buffer ->
                add("-b")
                add(buffer)
            }
        }
        val process = supervisor.start(
            ProcessSpec(
                executable = adb,
                args = args,
                workingDirectory = null,
                environment = emptyMap(),
                timeout = null,
                stdinMode = StdinMode.CLOSED,
                outputLimitBytes = 32 * 1024 * 1024,
            ),
        )
        try {
            emitAll(process.stdout)
        } finally {
            runCatching { process.terminate(Duration.ofSeconds(2)) }
        }
    }
}
