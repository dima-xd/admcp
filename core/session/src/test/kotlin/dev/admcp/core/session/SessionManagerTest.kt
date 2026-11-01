package dev.admcp.core.session

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.SessionId
import dev.admcp.core.model.SessionState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionManagerTest {
    @Test
    fun createsAndClosesSessionWithCleanup() = runBlocking {
        var cleaned = false
        val manager = SessionManager()
        val session = manager.create(DeviceId("d1"), "pkg", null) { cleaned = true }
        assertEquals(SessionState.READY, session.state)
        manager.close(session.id)
        assertTrue(cleaned)
        assertFailsWith<IllegalStateException> { manager.require(session.id) }
    }

    @Test
    fun sessionsAreIsolatedByDeviceAndProcess() = runBlocking {
        val manager = SessionManager()
        val a = manager.create(DeviceId("d1"), "a", null) {}
        val b = manager.create(DeviceId("d2"), "b", null) {}
        assertTrue(a.id != b.id)
        assertEquals(2, manager.list().size)
        manager.close(a.id)
        assertEquals(1, manager.list().size)
        assertEquals(DeviceId("d2"), manager.require(b.id).deviceId)
    }

    @Test
    fun processRestartInvalidatesSessionAttach() = runBlocking {
        val manager = SessionManager()
        val session = manager.create(DeviceId("d1"), "pkg", null) {}
        val first = ProcessRef(DeviceId("d1"), ProcessId(10), "pkg", ProcessInstanceId("i1"), null)
        manager.attachProcess(session.id, first)
        val restarted = first.copy(instanceId = ProcessInstanceId("i2"))
        manager.attachProcess(session.id, restarted)
        assertEquals("i2", manager.require(session.id).process?.instanceId?.value)
    }

    @Test
    fun rejectsUnknownSession() = runBlocking {
        val manager = SessionManager()
        assertFailsWith<IllegalStateException> { manager.require(SessionId("missing")) }
    }

    @Test
    fun closeIsIdempotentOnlyOnce() = runBlocking {
        val manager = SessionManager()
        var calls = 0
        val session = manager.create(DeviceId("d1"), null, null) { calls++ }
        manager.close(session.id)
        manager.close(session.id)
        assertEquals(1, calls)
    }
}
