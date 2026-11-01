package dev.admcp.ui.uiautomator

import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ScrollDirection
import dev.admcp.core.model.UiActionKind
import dev.admcp.core.model.UiActionResult
import dev.admcp.core.model.UiSnapshot
import dev.admcp.ui.PlannedAction
import dev.admcp.ui.UiHierarchyParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

interface UiAdb {
    suspend fun shell(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult
    suspend fun execOut(deviceId: DeviceId, args: List<String>, timeout: Duration): CommandResult =
        shell(deviceId, args, timeout)
}

interface UiBackend {
    val id: String
    suspend fun snapshot(deviceId: DeviceId, generation: Long): UiSnapshot
    suspend fun perform(deviceId: DeviceId, plan: PlannedAction, generation: Long): UiActionResult
}

class UiautomatorBackend(private val adb: UiAdb) : UiBackend {
    override val id: String = "uiautomator"
    private val parser = UiHierarchyParser()
    private val snapshotMutex = Mutex()

    override suspend fun snapshot(deviceId: DeviceId, generation: Long): UiSnapshot = snapshotMutex.withLock {
        var lastFailure: String? = null
        repeat(2) { attempt ->
            val result = adb.execOut(deviceId, listOf("uiautomator", "dump", "/dev/tty"), Duration.ofSeconds(30))
            if (result.exitCode == 0) {
                val text = result.stdoutText
                val start = text.indexOf("<hierarchy")
                if (start >= 0) {
                    val end = text.indexOf("</hierarchy>", start).let { if (it < 0) text.length else it + "</hierarchy>".length }
                    return@withLock parser.parse(deviceId, text.substring(start, end).toByteArray(), generation)
                }
                lastFailure = "uiautomator output did not contain a hierarchy document"
            } else {
                lastFailure = "uiautomator dump failed: ${result.stderrText}"
            }
            if (attempt == 0) delay(300)
        }
        error(lastFailure ?: "uiautomator dump failed")
    }

    override suspend fun perform(deviceId: DeviceId, plan: PlannedAction, generation: Long): UiActionResult {
        UiStaleness.requireCurrent(generation, generation)
        if (plan.assessment.decision == dev.admcp.core.model.UiActionDecision.REQUIRE_CONFIRMATION && !plan.confirmed) {
            return UiActionResult(false, plan.matched, false, null, "action requires explicit confirmation")
        }
        if (plan.assessment.decision == dev.admcp.core.model.UiActionDecision.DENY) {
            return UiActionResult(false, plan.matched, false, null, "action denied by policy")
        }
        val args = when (plan.action.kind) {
            UiActionKind.TAP, UiActionKind.LONG_PRESS -> {
                val x = plan.x ?: return UiActionResult(false, plan.matched, false, null, "no resolved coordinates")
                val y = plan.y ?: return UiActionResult(false, plan.matched, false, null, "no resolved coordinates")
                if (plan.action.kind == UiActionKind.LONG_PRESS) listOf("input", "swipe", "$x", "$y", "$x", "$y", "800")
                else listOf("input", "tap", "$x", "$y")
            }
            UiActionKind.TYPE_TEXT -> {
                val text = plan.action.text ?: return UiActionResult(false, plan.matched, false, null, "text is required")
                if (!text.matches(Regex("[A-Za-z0-9 .,@_:/+-]*"))) {
                    return UiActionResult(false, plan.matched, false, null, "text contains characters that cannot be safely encoded for adb input")
                }
                listOf("input", "text", text.replace(" ", "%s"))
            }
            UiActionKind.SCROLL, UiActionKind.SWIPE -> {
                val direction = plan.action.direction ?: ScrollDirection.DOWN
                val (x1, y1, x2, y2) = when (direction) {
                    ScrollDirection.DOWN -> listOf("540", "1800", "540", "600")
                    ScrollDirection.UP -> listOf("540", "600", "540", "1800")
                    ScrollDirection.LEFT -> listOf("900", "1200", "180", "1200")
                    ScrollDirection.RIGHT -> listOf("180", "1200", "900", "1200")
                }
                listOf("input", "swipe", x1, y1, x2, y2, "300")
            }
            UiActionKind.KEY -> listOf("input", "keyevent", requireNotNull(plan.action.key) { "key is required" })
            UiActionKind.BACK -> listOf("input", "keyevent", "4")
            UiActionKind.HOME -> listOf("input", "keyevent", "3")
            UiActionKind.WAIT_FOR_STATE -> emptyList()
        }
        val result = if (args.isEmpty()) CommandResult(0, ByteArray(0), ByteArray(0))
        else adb.shell(deviceId, args, Duration.ofSeconds(15))
        val after = if (result.exitCode == 0) snapshot(deviceId, generation + 1) else null
        return UiActionResult(result.exitCode == 0, plan.matched, after != null, after, if (result.exitCode == 0) null else result.stderrText)
    }
}

private object UiStaleness {
    fun requireCurrent(actual: Long, expected: Long) {
        require(actual == expected) { "stale UI generation" }
    }
}
