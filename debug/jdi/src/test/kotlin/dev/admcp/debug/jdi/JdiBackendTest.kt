package dev.admcp.debug.jdi

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.StopEpoch
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdiBackendTest {
    @Test
    fun socketAttachConnectorIsAvailableOnJdk21() {
        assertTrue(JdiConnectorProbe.available())
    }

    @Test
    fun handleGuardRejectsStaleProcessOrEpoch() {
        val guard = JdiHandleGuard("instance-1") { 3 }
        val handle = dev.admcp.core.model.DebugHandle(
            dev.admcp.core.model.SessionId("s"),
            dev.admcp.core.model.BackendId("JDI"),
            ProcessRef(DeviceId("d"), ProcessId(1), "pkg", ProcessInstanceId("instance-1"), null),
            StopEpoch(3),
            1,
            "frame:0",
        )
        guard.check(handle)
        assertFailsWith<IllegalArgumentException> { guard.check(handle.copy(stopEpoch = StopEpoch(2))) }
        assertFailsWith<IllegalArgumentException> {
            guard.check(handle.copy(process = handle.process.copy(instanceId = ProcessInstanceId("instance-2"))))
        }
    }
}
