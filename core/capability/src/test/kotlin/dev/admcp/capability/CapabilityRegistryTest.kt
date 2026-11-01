package dev.admcp.capability

import dev.admcp.core.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CapabilityRegistryTest {
    private val device = DeviceId("d1")
    private val session = SessionId("s1")
    private val process1 = ProcessRef(device, ProcessId(10), "pkg", ProcessInstanceId("i1"), "start-1")
    private val process2 = process1.copy(instanceId = ProcessInstanceId("i2"), startToken = "start-2")

    @Test
    fun doesNotMarkCapabilityAvailableFromExecutableExistence() = runBlocking {
        val registry = InMemoryCapabilityRegistry()
        val capability = Capability(CapabilityId("lldb.dap"), Availability.UNKNOWN, listOf("executable exists"), "runtime probe required")
        val snapshot = registry.put(CapabilitySubject(null, null, null), listOf(capability))
        assertEquals(Availability.UNKNOWN, snapshot.capabilities.single().availability)
        assertEquals(listOf("executable exists"), snapshot.capabilities.single().evidence)
    }

    @Test
    fun invalidatesProcessCapabilitiesAfterProcessRestart() = runBlocking {
        val registry = InMemoryCapabilityRegistry()
        registry.put(CapabilitySubject(device, session, process1), listOf(cap(CapabilityId("jdi"))))
        assertNotNull(registry.get(CapabilitySubject(device, session, process1)))
        registry.put(CapabilitySubject(device, session, process2), listOf(cap(CapabilityId("jdi"))))
        assertNull(registry.get(CapabilitySubject(device, session, process1)))
        assertNotNull(registry.get(CapabilitySubject(device, session, process2)))
    }

    @Test
    fun keepsDeviceAndSessionCapabilitiesSeparate() = runBlocking {
        val registry = InMemoryCapabilityRegistry()
        registry.put(CapabilitySubject(device, null, null), listOf(cap(CapabilityId("adb"))))
        registry.put(CapabilitySubject(device, session, null), listOf(cap(CapabilityId("ui"))))
        assertEquals("adb", registry.get(CapabilitySubject(device, null, null))?.capabilities?.single()?.name?.value)
        assertEquals("ui", registry.get(CapabilitySubject(device, session, null))?.capabilities?.single()?.name?.value)
    }

    @Test
    fun recordsEvidenceForLimitedCapability() = runBlocking {
        val registry = InMemoryCapabilityRegistry()
        val limited = Capability(CapabilityId("android-cli.input"), Availability.LIMITED, listOf("input command absent"), "ADB fallback")
        registry.put(CapabilitySubject(device, null, null), listOf(limited))
        val stored = registry.get(CapabilitySubject(device, null, null))!!.capabilities.single()
        assertEquals(Availability.LIMITED, stored.availability)
        assertEquals("ADB fallback", stored.reason)
    }

    @Test
    fun invalidateRemovesMatchingSubjectEntries() = runBlocking {
        val registry = InMemoryCapabilityRegistry()
        registry.put(CapabilitySubject(device, session, process1), listOf(cap(CapabilityId("jdi"))))
        registry.invalidate(CapabilitySubject(device, session, process1), "detached")
        assertNull(registry.get(CapabilitySubject(device, session, process1)))
    }

    private fun cap(id: CapabilityId) = Capability(id, Availability.AVAILABLE, listOf("probe"))
}
