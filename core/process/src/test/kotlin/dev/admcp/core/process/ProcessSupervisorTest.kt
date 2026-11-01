package dev.admcp.core.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessSupervisorTest {
    private val supervisor = DefaultProcessSupervisor()

    @Test
    fun passesArgumentsWithoutShellExpansion() = runBlocking {
        val process = start("args", "a b", "a;b", "a&b", "\$HOME")
        val out = collect(process)
        process.awaitExit()
        assertEquals(listOf("a b", "a;b", "a&b", "\$HOME"), out.lines().filter { it.isNotEmpty() })
    }

    @Test
    fun preservesUtf8AcrossChunkBoundaries() = runBlocking {
        val process = start("utf8")
        val out = collect(process)
        process.awaitExit()
        assertEquals("hello-\u4e16\u754c-\u20ac-\ud83d\ude80", out)
    }

    @Test
    fun capturesStdoutAndStderrSeparately() = runBlocking {
        val process = start("both")
        val out = async(Dispatchers.IO) { collect(process) }
        val err = async(Dispatchers.IO) { collectErr(process) }
        process.awaitExit()
        assertEquals("OUT-", out.await())
        assertEquals("ERR-", err.await())
    }

    @Test
    fun drainsHighVolumeStdoutAndStderrConcurrentlyWithoutDeadlock() = runBlocking {
        val process = start("flood")
        val out = async(Dispatchers.IO) { collect(process) }
        val err = async(Dispatchers.IO) { collectErr(process) }
        process.awaitExit()
        assertEquals(16 * 512 * 300, out.await().length)
        assertEquals(16 * 512 * 300, err.await().length)
    }

    @Test
    fun boundedOutputDoesNotGrowHeapWithoutLimit() = runBlocking {
        val process = supervisor.start(spec("floodmax"))
        process.awaitExit()
        val captured = process.stdout.toList().sumOf { it.size }
        assertTrue(captured <= process.outputStats.maxBytes, "captured=$captured")
        assertTrue(process.outputStats.droppedBytes > 0)
    }

    @Test
    fun backpressureOrSpoolingIsObservable() = runBlocking {
        val process = supervisor.start(spec("floodmax"))
        process.awaitExit()
        assertTrue(process.outputStats.overflowEvents > 0)
    }

    @Test
    fun timesOutAndTerminatesEntireChildProcessTree() = runBlocking {
        val started = System.nanoTime()
        val process = supervisor.start(spec("sleep", timeout = Duration.ofMillis(300)))
        val exit = process.awaitExit()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(exit.timedOut)
        assertTrue(elapsedMs < 10_000, "elapsed=$elapsedMs")
        assertTrue(!process.isAlive)
    }

    @Test
    fun cleansUpOnCancellation() = runBlocking {
        val process = supervisor.start(spec("sleep"))
        process.terminate(Duration.ofMillis(100))
        val exit = process.awaitExit()
        assertTrue(exit.cancelled || exit.timedOut)
        assertTrue(!process.isAlive)
    }

    @Test
    fun supportsWorkingDirectoryWithSpacesAndUnicode() = runBlocking {
        val dir = Files.createTempDirectory("admcp space \u00e9 ")
        val current = Path.of("").toAbsolutePath()
        val spec = spec("args").copy(workingDirectory = dir)
        val process = supervisor.start(spec)
        val out = collect(process)
        process.awaitExit()
        assertTrue(out.isEmpty())
        assertTrue(Files.exists(dir))
        assertTrue(current.toString().isNotBlank())
    }

    @Test
    fun returnsExitCodeAndSeparateStreams() = runBlocking {
        val process = start("fail")
        collect(process)
        val exit = process.awaitExit()
        assertEquals(7, exit.exitCode)
    }

    private suspend fun collect(process: ManagedProcess): String = buildString {
        val output = ByteArrayOutputStream()
        process.stdout.collect { output.write(it) }
        append(output.toString(Charsets.UTF_8))
    }

    private suspend fun collectErr(process: ManagedProcess): String = buildString {
        val output = ByteArrayOutputStream()
        process.stderr.collect { output.write(it) }
        append(output.toString(Charsets.UTF_8))
    }

    private suspend fun start(vararg args: String): ManagedProcess = supervisor.start(spec(*args))

    private fun spec(vararg args: String, timeout: Duration? = Duration.ofSeconds(20)): ProcessSpec =
        ProcessSpec(
            executable = ProcessTestHelper.javaExecutable().toPath(),
            args = listOf("-cp", ProcessTestHelper.classpath(), ProcessTestHelper::class.java.name) + args,
            workingDirectory = Path.of("").toAbsolutePath(),
            environment = emptyMap(),
            timeout = timeout,
            stdinMode = StdinMode.CLOSED,
        )
}
