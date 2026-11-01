package dev.admcp.core.process

import kotlinx.coroutines.flow.Flow
import java.time.Duration

interface ManagedProcess {
    val pid: Long?
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>
    val outputStats: ProcessOutputStats
    val isAlive: Boolean
    suspend fun writeStdin(bytes: ByteArray)
    suspend fun awaitExit(): ProcessExit
    suspend fun terminate(grace: Duration)
}
