package dev.admcp.capability

import dev.admcp.core.model.Capability
import dev.admcp.core.model.CapabilitySnapshot
import dev.admcp.core.model.CapabilitySubject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface CapabilityRegistry {
    suspend fun put(subject: CapabilitySubject, capabilities: List<Capability>): CapabilitySnapshot
    suspend fun get(subject: CapabilitySubject): CapabilitySnapshot?
    suspend fun invalidate(subject: CapabilitySubject, reason: String)
}

class InMemoryCapabilityRegistry : CapabilityRegistry {
    private val entries = ConcurrentHashMap<CapabilitySubject, CapabilitySnapshot>()

    override suspend fun put(subject: CapabilitySubject, capabilities: List<Capability>): CapabilitySnapshot {
        val process = subject.process
        if (process != null) {
            entries.keys.filter { existing ->
                existing.process?.deviceId == process.deviceId &&
                    existing.process?.pid == process.pid &&
                    existing.process?.instanceId != process.instanceId
            }.forEach(entries::remove)
        }
        val snapshot = CapabilitySnapshot(subject, capabilities, Instant.now())
        entries[subject] = snapshot
        return snapshot
    }

    override suspend fun get(subject: CapabilitySubject): CapabilitySnapshot? = entries[subject]

    override suspend fun invalidate(subject: CapabilitySubject, reason: String) {
        val target = subject.process
        entries.keys.filter { existing ->
            existing == subject || (target != null && existing.process?.instanceId == target.instanceId)
        }.forEach(entries::remove)
    }
}
