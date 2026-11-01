package dev.admcp.device.adb

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceConnectionState
import dev.admcp.core.model.DeviceId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AdbDeviceManagerTest {
    @Test
    fun parsesMultipleDevicesAndUnauthorizedState() = runBlocking {
        val runner = FakeRunner(
            "devices -l" to result(
                """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:Pixel_6 device:generic transport_id:1
                R58M123 unauthorized usb:1-1 transport_id:2
                """.trimIndent(),
            ),
        )
        val client = client(runner)
        val devices = client.devices()
        assertEquals(2, devices.size)
        assertEquals(DeviceConnectionState.ONLINE, devices[0].state)
        assertEquals(DeviceConnectionState.UNAUTHORIZED, devices[1].state)
    }

    @Test
    fun handlesOfflineDevice() = runBlocking {
        val runner = FakeRunner("devices -l" to result("List of devices attached\nR58M123 offline transport_id:2\n"))
        assertEquals(DeviceConnectionState.OFFLINE, client(runner).devices().single().state)
    }

    @Test
    fun preservesSerialWithTcpEndpoint() = runBlocking {
        val runner = FakeRunner("devices -l" to result("List of devices attached\n192.168.1.2:5555 device product:p model:m device:d transport_id:3\n"))
        assertEquals("192.168.1.2:5555", client(runner).devices().single().id.value)
    }

    @Test
    fun cleansUpForwardOnSessionClose() = runBlocking {
        val runner = FakeRunner(
            "forward tcp:0 jdwp:123" to result("43123\n"),
            "forward --remove tcp:43123" to result(""),
        )
        val client = client(runner)
        val handle = client.forward(DeviceId("d1"), "tcp:0", "jdwp:123")
        assertEquals("tcp:43123", handle.local)
        client.removeForward(handle)
        assertTrue(runner.calls.any { it == listOf("-s", "d1", "forward", "--remove", "tcp:43123") })
    }

    @Test
    fun doesNotMixShellArgs() = runBlocking {
        val runner = FakeRunner("shell getprop ro.product.model" to result("Pixel\n"))
        client(runner).shell(DeviceId("d1"), listOf("getprop", "ro.product.model"), Duration.ofSeconds(2))
        assertEquals(listOf("-s", "d1", "shell", "getprop", "ro.product.model"), runner.calls.single())
    }

    @Test
    fun reportsDeviceRebootAndPidChange() = runBlocking {
        val runner = FakeRunner(
            "shell ps -A -o PID,NAME" to result("PID NAME\n100 dev.admcp.fixture\n"),
        )
        val manager = AdbDeviceManager(client(runner))
        val first = manager.processes(DeviceId("d1")).single()
        val second = manager.processes(DeviceId("d1")).single()
        assertEquals(first.ref.pid, second.ref.pid)
        assertNotEquals(first.ref.instanceId, second.ref.instanceId)
    }

    private fun client(runner: AdbCommandRunner) = DefaultAdbClient("adb", runner)

    private fun result(stdout: String) = CommandResult(0, stdout.toByteArray(), ByteArray(0))

    private class FakeRunner(vararg responses: Pair<String, CommandResult>) : AdbCommandRunner {
        private val responses = responses.toMap()
        val calls = mutableListOf<List<String>>()
        override suspend fun run(args: List<String>, timeout: Duration): CommandResult {
            calls += args
            val key = (if (args.firstOrNull() == "-s") args.drop(2) else args).joinToString(" ")
            return responses[key] ?: CommandResult(1, ByteArray(0), "unexpected: $key".toByteArray())
        }
    }
}
