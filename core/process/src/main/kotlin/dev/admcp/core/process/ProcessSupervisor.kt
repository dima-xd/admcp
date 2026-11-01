package dev.admcp.core.process

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

interface ProcessSupervisor {
    suspend fun start(spec: ProcessSpec): ManagedProcess
}

class DefaultProcessSupervisor : ProcessSupervisor {
    override suspend fun start(spec: ProcessSpec): ManagedProcess = withContext(Dispatchers.IO) {
        require(spec.executable.toFile().exists()) { "executable not found: ${spec.executable}" }
        DefaultManagedProcess(spec).also { it.start() }
    }
}

private class DefaultManagedProcess(private val spec: ProcessSpec) : ManagedProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exit = CompletableDeferred<ProcessExit>()
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val stdoutOutput = BoundedOutput(spec.outputLimitBytes)
    private val stderrOutput = BoundedOutput(spec.outputLimitBytes)
    private lateinit var process: Process
    private var drainJobs: List<Job> = emptyList()

    override val stdout: Flow<ByteArray> get() = stdoutOutput.flow
    override val stderr: Flow<ByteArray> get() = stderrOutput.flow
    override val outputStats: ProcessOutputStats get() = stdoutOutput.stats
    override val pid: Long? get() = if (::process.isInitialized) process.pid() else null
    override val isAlive: Boolean get() = ::process.isInitialized && process.isAlive

    fun start() {
        val builder = ProcessBuilder(listOf(spec.executable.toString()) + spec.args)
        spec.workingDirectory?.let { builder.directory(it.toFile()) }
        if (spec.environment.isEmpty()) {
            builder.environment().clear()
        } else {
            builder.environment().clear()
            builder.environment().putAll(spec.environment)
        }
        if (spec.stdinMode == StdinMode.CLOSED) {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE)
        }
        process = builder.start()
        if (spec.stdinMode == StdinMode.CLOSED) {
            runCatching { process.outputStream.close() }
        }
        drainJobs = listOf(
            scope.launch { drain(process.inputStream, stdoutOutput) },
            scope.launch { drain(process.errorStream, stderrOutput) },
        )
        spec.timeout?.let { limit ->
            scope.launch {
                delay(limit.toMillis())
                if (process.isAlive) {
                    timedOut.set(true)
                    terminateTree()
                }
            }
        }
        scope.launch {
            val code = process.waitFor()
            drainJobs.forEach { runCatching { it.join() } }
            stdoutOutput.close()
            stderrOutput.close()
            exit.complete(ProcessExit(code, timedOut.get(), cancelled.get()))
            scope.cancel()
        }
    }

    override suspend fun writeStdin(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            process.outputStream.write(bytes)
            process.outputStream.flush()
        }
    }

    override suspend fun awaitExit(): ProcessExit = exit.await()

    override suspend fun terminate(grace: Duration) {
        cancelled.set(true)
        if (!process.isAlive) return
        process.destroy()
        val deadline = System.nanoTime() + grace.toNanos()
        while (process.isAlive && System.nanoTime() < deadline) delay(20)
        if (process.isAlive) terminateTree()
    }

    private fun terminateTree() {
        val handle = process.toHandle()
        if (System.getProperty("os.name").lowercase().contains("win")) {
            runCatching {
                ProcessBuilder("taskkill", "/PID", process.pid().toString(), "/T", "/F")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
        handle.descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { handle.destroyForcibly() }
    }

    private fun drain(input: InputStream, output: BoundedOutput) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) output.offer(buffer.copyOf(count))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { input.close() }
        }
    }
}

suspend fun ManagedProcess.collectStdout(): ByteArray = collect(stdout)
suspend fun ManagedProcess.collectStderr(): ByteArray = collect(stderr)

private suspend fun collect(flow: Flow<ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    flow.collect { output.write(it) }
    return output.toByteArray()
}
