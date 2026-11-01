package dev.admcp.debug.dap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

data class DapMessage(val body: JsonObject)

class DapCodec(private val maxFrameBytes: Int = 4 * 1024 * 1024) {
    fun read(input: InputStream): JsonObject? {
        val header = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (header.size() == 0) return null
                throw EOFException("truncated DAP header")
            }
            header.write(value)
            matched = when {
                value.toByte() == terminator[matched] -> matched + 1
                value.toByte() == terminator[0] -> 1
                else -> 0
            }
            if (matched == terminator.size) break
            if (header.size() > 8192) throw IllegalArgumentException("DAP header too large")
        }
        val text = header.toString(Charsets.US_ASCII)
        val length = text.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("missing Content-Length")
        if (length < 0 || length > maxFrameBytes) throw IllegalArgumentException("invalid Content-Length: $length")
        val body = input.readNBytes(length)
        if (body.size != length) throw EOFException("truncated DAP body")
        return Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
    }

    fun write(output: OutputStream, message: JsonObject) {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    fun frame(message: JsonObject): ByteArray {
        val output = ByteArrayOutputStream()
        write(output, message)
        return output.toByteArray()
    }
}

class DapRequestException(val command: String, val dapMessage: String) : RuntimeException("DAP $command failed: $dapMessage")

class DapClient(
    input: InputStream,
    private val output: OutputStream,
    private val codec: DapCodec = DapCodec(),
) : AutoCloseable {
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.CompletableFuture<JsonObject>>()
    private val eventListeners = java.util.concurrent.CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val sequence = java.util.concurrent.atomic.AtomicInteger(1)
    private val reader = Thread({ readLoop(input) }, "dap-reader").apply { isDaemon = true; start() }

    fun onEvent(listener: (JsonObject) -> Unit) { eventListeners += listener }

    fun requestAsync(command: String, arguments: JsonObject = buildJsonObject {}): java.util.concurrent.CompletableFuture<JsonObject> {
        val seq = sequence.getAndIncrement()
        val future = java.util.concurrent.CompletableFuture<JsonObject>()
        pending[seq] = future
        val message = buildJsonObject {
            put("seq", seq)
            put("type", "request")
            put("command", command)
            put("arguments", arguments)
        }
        if (System.getenv("ADMCP_DAP_DEBUG") == "1") {
            runCatching {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "admcp-dap-wire.log"),
                    message.toString() + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }
        codec.write(output, message)
        return future
    }

    fun request(command: String, arguments: JsonObject = buildJsonObject {}, timeoutSeconds: Long = 30): JsonObject {
        val response = requestAsync(command, arguments).get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        return responseBody(command, response)
    }

    fun responseBody(command: String, response: JsonObject): JsonObject {
        if (response["success"]?.jsonPrimitive?.content == "false") {
            val message = response["message"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val body = response["body"] as? JsonObject
            val detail = body?.get("error")?.toString() ?: body?.toString()
            throw DapRequestException(command, if (detail.isNullOrBlank()) message else "$message: $detail")
        }
        return response["body"]?.jsonObject ?: JsonObject(emptyMap())
    }

    private fun readLoop(input: InputStream) {
        try {
            while (true) {
                val message = codec.read(input) ?: break
                if (System.getenv("ADMCP_DAP_DEBUG") == "1") {
                    runCatching {
                        java.nio.file.Files.writeString(
                            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "admcp-dap-wire.log"),
                            message.toString() + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND,
                        )
                    }
                }
                when (message["type"]?.jsonPrimitive?.content) {
                    "response" -> {
                        val requestSeq = message["request_seq"]?.jsonPrimitive?.int ?: continue
                        pending.remove(requestSeq)?.complete(message)
                    }
                    "event" -> eventListeners.forEach { it(message) }
                }
            }
        } catch (_: Exception) {
        } finally {
            pending.values.forEach { it.completeExceptionally(IllegalStateException("DAP connection closed")) }
        }
    }

    override fun close() {
        runCatching { output.close() }
        reader.interrupt()
    }
}

fun dapMessage(type: String, seq: Int, body: JsonObject = JsonObject(emptyMap())): JsonObject = buildJsonObject {
    put("type", type)
    put("seq", seq)
    put("body", body)
}
