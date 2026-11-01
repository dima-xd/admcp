package dev.admcp.core.process

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class ProcessOutputStats internal constructor(
    val maxBytes: Int,
    private val dropped: java.util.concurrent.atomic.AtomicLong,
    private val overflow: java.util.concurrent.atomic.AtomicLong,
) {
    val droppedBytes: Long get() = dropped.get()
    val overflowEvents: Long get() = overflow.get()
}

internal class BoundedOutput(val maxBytes: Int) {
    private val chunkSize = 8192
    private val capacity = (maxBytes / chunkSize).coerceAtLeast(1)
    private val channel = Channel<ByteArray>(capacity)
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private val overflow = java.util.concurrent.atomic.AtomicLong()
    val stats = ProcessOutputStats(maxBytes, dropped, overflow)
    val flow: Flow<ByteArray> = channel.receiveAsFlow()

    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        if (bytes.size > maxBytes) {
            dropped.addAndGet(bytes.size.toLong())
            overflow.incrementAndGet()
            return false
        }
        return channel.trySend(bytes).isSuccess.also { accepted ->
            if (!accepted) {
                dropped.addAndGet(bytes.size.toLong())
                overflow.incrementAndGet()
            }
        }
    }

    fun close() = channel.close()
}
