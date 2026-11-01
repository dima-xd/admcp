package dev.admcp.events

import dev.admcp.core.model.ClockCalibration
import dev.admcp.core.model.ClockCalibrationStatus
import dev.admcp.core.model.ClockDomain
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.EventId
import dev.admcp.core.model.OperationId
import dev.admcp.core.model.ProcessId
import dev.admcp.core.model.ProcessInstanceId
import dev.admcp.core.model.ProcessRef
import dev.admcp.core.model.SessionId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteEventStoreTest {
    private val session = SessionId("s1")
    private val process = ProcessRef(DeviceId("d1"), ProcessId(10), "pkg", ProcessInstanceId("i1"), "token")

    private fun event(
        id: String,
        time: Long,
        type: EventType = EventType.LOG,
        domain: ClockDomain = ClockDomain.DEVICE_MONOTONIC,
        sessionId: SessionId = session,
    ) = EventEnvelope(
        EventId(id), sessionId, type, EventSource.LOGCAT, Instant.ofEpochMilli(time), Instant.ofEpochMilli(time - 5),
        time * 1_000_000, domain, process, 7, EventSeverity.INFO, time, OperationId("op-$id"),
        ClockCalibration(ClockDomain.DEVICE_MONOTONIC, ClockDomain.HOST_MONOTONIC, 5, 2, 5, Instant.ofEpochMilli(time), "midpoint", ClockCalibrationStatus.ESTIMATED),
        emptyList(),
        buildJsonObject { put("message", "event-$id") },
    )

    @Test
    fun reopensAfterProcessRestart() = runBlocking {
        val db = Files.createTempDirectory("admcp-events-").resolve("events.db")
        SqliteEventStore(db).append(event("e1", 1000))
        val reopened = SqliteEventStore(db)
        val page = reopened.query(EventQuery(sessionId = session))
        assertEquals(1, page.events.size)
        assertEquals("events.db", db.fileName.toString())
    }

    @Test
    fun preservesClockDomainsAndCalibration() = runBlocking {
        val store = SqliteEventStore(Files.createTempDirectory("admcp-events-").resolve("events.db"))
        store.append(event("e1", 1000, domain = ClockDomain.DEVICE_MONOTONIC))
        val stored = store.query(EventQuery()).events.single()
        assertEquals(ClockDomain.DEVICE_MONOTONIC, stored.monotonicClockDomain)
        assertNotNull(stored.deviceTimestamp)
        assertEquals(5, stored.clockCalibration?.offsetNanos)
        assertEquals(OperationId("op-e1"), stored.operationId)
    }

    @Test
    fun paginationUsesStableCursor() = runBlocking {
        val store = SqliteEventStore(Files.createTempDirectory("admcp-events-").resolve("events.db"))
        (1..5).forEach { store.append(event("e$it", 1000L + it)) }
        val first = store.query(EventQuery(limit = 2))
        assertEquals(listOf("e1", "e2"), first.events.map { it.id.value })
        assertNotNull(first.nextCursor)
        val second = store.query(EventQuery(limit = 2, cursor = first.nextCursor))
        assertEquals(listOf("e3", "e4"), second.events.map { it.id.value })
        val third = store.query(EventQuery(limit = 2, cursor = second.nextCursor))
        assertEquals(listOf("e5"), third.events.map { it.id.value })
        assertEquals(null, third.nextCursor)
    }

    @Test
    fun queryFiltersSessionAndType() = runBlocking {
        val store = SqliteEventStore(Files.createTempDirectory("admcp-events-").resolve("events.db"))
        store.append(event("e1", 1000, EventType.LOG))
        store.append(event("e2", 2000, EventType.UI))
        store.append(event("e3", 3000, EventType.LOG, sessionId = SessionId("s2")))
        val logs = store.query(EventQuery(sessionId = session, types = setOf(EventType.LOG)))
        assertEquals(1, logs.events.size)
        assertEquals("e1", logs.events.single().id.value)
    }

    @Test
    fun concurrentReadersObserveCommittedRows() = runBlocking {
        val db = Files.createTempDirectory("admcp-events-").resolve("events.db")
        val writer = SqliteEventStore(db)
        (1..20).forEach { writer.append(event("e$it", 1000L + it)) }
        val reader = SqliteEventStore(db)
        val pages = kotlinx.coroutines.coroutineScope {
            (1..5).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    reader.query(EventQuery(limit = 10)).events.size
                }
            }.awaitAll()
        }
        assertTrue(pages.all { it == 10 })
    }
}
