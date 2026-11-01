package dev.admcp.debug.dap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DapTest {
    @Test
    fun handlesFragmentedHeaderAndBody() {
        val codec = DapCodec()
        val framed = codec.frame(buildJsonObject { put("type", "event") })
        val input = OneByteInputStream(framed)
        val message = codec.read(input)
        assertEquals("event", message?.get("type")?.toString()?.trim('"'))
    }

    @Test
    fun readsMultipleFramesAndCleanEof() {
        val codec = DapCodec()
        val bytes = codec.frame(buildJsonObject { put("seq", 1) }) + codec.frame(buildJsonObject { put("seq", 2) })
        val input = ByteArrayInputStream(bytes)
        assertEquals(1, codec.read(input)?.get("seq")?.toString()?.toInt())
        assertEquals(2, codec.read(input)?.get("seq")?.toString()?.toInt())
        assertNull(codec.read(input))
    }

    @Test
    fun rejectsOversizedAndMalformedFrames() {
        val codec = DapCodec(maxFrameBytes = 3)
        val input = ByteArrayInputStream("Content-Length: 10\r\n\r\n{}".toByteArray())
        assertFailsWith<IllegalArgumentException> { codec.read(input) }
        assertFailsWith<IllegalArgumentException> {
            codec.read(ByteArrayInputStream("Content-Length: x\r\n\r\n{}".toByteArray()))
        }
    }

    @Test
    fun correlatesRequestAndDeliversEvent() {
        val codec = DapCodec()
        val serverIn = java.io.PipedInputStream()
        val clientOut = java.io.PipedOutputStream(serverIn)
        val clientIn = java.io.PipedInputStream()
        val serverOut = java.io.PipedOutputStream(clientIn)
        val eventSeen = java.util.concurrent.CountDownLatch(1)
        val client = DapClient(clientIn, clientOut)
        client.onEvent { event ->
            if (event["event"]?.toString()?.trim('"') == "stopped") eventSeen.countDown()
        }
        val server = Thread({
            val request = codec.read(serverIn)!!
            val seq = request["seq"]!!.toString().toInt()
            codec.write(serverOut, buildJsonObject {
                put("seq", 1)
                put("type", "response")
                put("request_seq", seq)
                put("success", true)
                put("command", "threads")
                put("body", buildJsonObject { put("threads", "ok") })
            })
            codec.write(serverOut, buildJsonObject {
                put("seq", 2)
                put("type", "event")
                put("event", "stopped")
                put("body", buildJsonObject {})
            })
        }, "dap-test-server")
        server.start()
        val body = client.request("threads")
        assertTrue(eventSeen.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("ok", body["threads"]?.toString()?.trim('"'))
        client.close()
    }

    private class OneByteInputStream(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index >= bytes.size) -1 else bytes[index++].toInt() and 0xFF
    }
}
