package dev.admcp.ui.uiautomator

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.UiAction
import dev.admcp.core.model.UiActionKind
import dev.admcp.core.model.UiSelector
import dev.admcp.ui.UiHierarchyParser
import dev.admcp.ui.UiActionPlanner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiautomatorBackendTest {
    private val xml = """
        <hierarchy rotation="0">
          <node text="OK" resource-id="app:id/ok" class="android.widget.Button" clickable="true" enabled="true" bounds="[10,20][110,80]" />
        </hierarchy>
    """.trimIndent()

    @Test
    fun dumpsAndParsesHierarchyWithoutShellInterpolation() = runBlocking {
        val adb = FakeAdb(CommandResult(0, xml.toByteArray(), ByteArray(0)))
        val backend = UiautomatorBackend(adb)
        val snapshot = backend.snapshot(DeviceId("d1"), 1)
        assertEquals(1, snapshot.elements.size)
        assertEquals("app:id/ok", snapshot.elements.single().id)
        assertTrue(adb.calls.single().first() == "exec-out")
        assertTrue(adb.calls.single().contains("uiautomator"))
    }

    @Test
    fun rejectsDumpWhenPrefixIsPresent() = runBlocking {
        val adb = FakeAdb(CommandResult(0, "UI hierchary dumped to: /dev/tty\n$xml".toByteArray(), ByteArray(0)))
        val snapshot = UiautomatorBackend(adb).snapshot(DeviceId("d1"), 1)
        assertEquals(1, snapshot.elements.size)
    }

    @Test
    fun tapUsesResolvedCenterAndReturnsVerificationSnapshot() = runBlocking {
        val adb = FakeAdb(CommandResult(0, xml.toByteArray(), ByteArray(0)))
        val backend = UiautomatorBackend(adb)
        val snapshot = backend.snapshot(DeviceId("d1"), 1)
        val plan = UiActionPlanner().plan(UiAction(UiActionKind.TAP, UiSelector(resourceId = "app:id/ok"), null, null, null, null), snapshot)
        val result = backend.perform(DeviceId("d1"), plan, 1)
        assertTrue(result.performed)
        assertTrue(adb.calls.any { it.joinToString(" ") == "shell input tap 60 50" })
    }

    private inner class FakeAdb(vararg results: CommandResult) : UiAdb {
        private val queue = results.toMutableList()
        val calls = mutableListOf<List<String>>()
        override suspend fun shell(deviceId: DeviceId, args: List<String>, timeout: java.time.Duration): CommandResult {
            calls += listOf("shell") + args
            return queue.removeFirstOrNull() ?: CommandResult(0, xml.toByteArray(), ByteArray(0))
        }

        override suspend fun execOut(deviceId: DeviceId, args: List<String>, timeout: java.time.Duration): CommandResult {
            calls += listOf("exec-out") + args
            return queue.removeFirstOrNull() ?: CommandResult(0, xml.toByteArray(), ByteArray(0))
        }
    }
}
