package dev.admcp.events

import dev.admcp.core.model.ArtifactId
import dev.admcp.core.model.BackendId
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

interface EventStore : AutoCloseable {
    suspend fun append(event: EventEnvelope)
    suspend fun query(query: EventQuery): EventPage
}

class SqliteEventStore(private val database: Path) : EventStore {
    private val url = "jdbc:sqlite:${database.toAbsolutePath()}"

    init {
        database.toAbsolutePath().parent?.let(Files::createDirectories)
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        id TEXT NOT NULL UNIQUE,
                        session_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        host_millis INTEGER NOT NULL,
                        device_millis INTEGER,
                        monotonic_nanos INTEGER,
                        clock_domain TEXT NOT NULL,
                        process_json TEXT,
                        tid INTEGER,
                        severity TEXT,
                        source_sequence INTEGER,
                        operation_id TEXT,
                        calibration_json TEXT,
                        artifacts_json TEXT NOT NULL,
                        data_json TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_session_time ON events(session_id, host_millis, seq)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_type_time ON events(type, host_millis, seq)")
                statement.execute("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, package_name TEXT, workspace_root TEXT, state TEXT NOT NULL, created_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, model TEXT, api_level INTEGER, abi TEXT, fingerprint TEXT, last_seen_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS process_instances (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, pid INTEGER NOT NULL, package_name TEXT, start_token TEXT, created_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS backend_states (session_id TEXT NOT NULL, backend_id TEXT NOT NULL, lifecycle TEXT NOT NULL, execution_state TEXT NOT NULL, stop_epoch INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(session_id, backend_id))")
                statement.execute("CREATE TABLE IF NOT EXISTS suspension_leases (lease_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, backend_id TEXT NOT NULL, process_instance_id TEXT NOT NULL, scope TEXT NOT NULL, state TEXT NOT NULL, backend_stop_id TEXT, operation_id TEXT, acquired_sequence INTEGER, release_sequence INTEGER)")
                statement.execute("CREATE TABLE IF NOT EXISTS clock_calibrations (id INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, source_clock TEXT NOT NULL, target_clock TEXT NOT NULL, offset_nanos INTEGER, uncertainty_nanos INTEGER, sample_count INTEGER NOT NULL, measured_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS artifacts (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, kind TEXT NOT NULL, relative_path TEXT NOT NULL, size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, redaction_state TEXT NOT NULL, commit_state TEXT NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS capabilities (id TEXT PRIMARY KEY, status TEXT NOT NULL, evidence_json TEXT NOT NULL, checked_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS log_segments (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, relative_path TEXT NOT NULL, bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, created_at INTEGER NOT NULL)")
                statement.execute("CREATE TABLE IF NOT EXISTS loss_records (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER, reason TEXT NOT NULL, exact_lost_records INTEGER)")
            }
        }
    }

    override suspend fun append(event: EventEnvelope) {
        connection().use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO events (
                    id, session_id, type, source, host_millis, device_millis, monotonic_nanos, clock_domain,
                    process_json, tid, severity, source_sequence, operation_id, calibration_json, artifacts_json, data_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, event.id.value)
                statement.setString(2, event.sessionId.value)
                statement.setString(3, event.type.name)
                statement.setString(4, event.source.name)
                statement.setLong(5, event.hostTimestamp.toEpochMilli())
                event.deviceTimestamp?.let { statement.setLong(6, it.toEpochMilli()) } ?: statement.setNull(6, java.sql.Types.INTEGER)
                event.monotonicNanos?.let { statement.setLong(7, it) } ?: statement.setNull(7, java.sql.Types.INTEGER)
                statement.setString(8, event.monotonicClockDomain.name)
                statement.setString(9, event.process?.let(::encodeProcess))
                event.tid?.let { statement.setLong(10, it) } ?: statement.setNull(10, java.sql.Types.INTEGER)
                statement.setString(11, event.severity?.name)
                event.sourceSequence?.let { statement.setLong(12, it) } ?: statement.setNull(12, java.sql.Types.INTEGER)
                statement.setString(13, event.operationId?.value)
                statement.setString(14, event.clockCalibration?.let(::encodeCalibration))
                statement.setString(15, kotlinx.serialization.json.JsonArray(event.artifactRefs.map { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
                statement.setString(16, event.data.toString())
                statement.executeUpdate()
            }
        }
    }

    override suspend fun query(query: EventQuery): EventPage {
        require(query.limit in 1..1000) { "limit must be between 1 and 1000" }
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        query.sessionId?.let { clauses += "session_id = ?"; params += it.value }
        query.fromHostTime?.let { clauses += "host_millis >= ?"; params += it.toEpochMilli() }
        query.toHostTime?.let { clauses += "host_millis <= ?"; params += it.toEpochMilli() }
        if (query.types.isNotEmpty()) {
            clauses += "type IN (${query.types.joinToString(",") { "?" }})"
            params.addAll(query.types.map { it.name })
        }
        query.cursor?.let { cursor ->
            val (millis, seq) = parseCursor(cursor)
            clauses += "(host_millis > ? OR (host_millis = ? AND seq > ?))"
            params += millis
            params += millis
            params += seq
        }
        val sql = buildString {
            append("SELECT * FROM events")
            if (clauses.isNotEmpty()) append(" WHERE ").append(clauses.joinToString(" AND "))
            append(" ORDER BY host_millis ASC, seq ASC LIMIT ?")
        }
        val events = connection().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                var index = 1
                params.forEach { parameter ->
                    when (parameter) {
                        is String -> statement.setString(index, parameter)
                        is Long -> statement.setLong(index, parameter)
                        is Int -> statement.setInt(index, parameter)
                        else -> statement.setObject(index, parameter)
                    }
                    index++
                }
                statement.setInt(index, query.limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(decode(rows))
                    }
                }
            }
        }
        val nextCursor = if (events.size == query.limit) {
            connection().use { connection ->
                val last = events.last()
                connection.prepareStatement("SELECT seq FROM events WHERE id = ?").use { statement ->
                    statement.setString(1, last.id.value)
                    statement.executeQuery().use { rows -> if (rows.next()) cursor(rows.getLong(1), last.hostTimestamp) else null }
                }
            }
        } else null
        return EventPage(events, nextCursor)
    }

    private fun decode(rows: java.sql.ResultSet): EventEnvelope = EventEnvelope(
        id = EventId(rows.getString("id")),
        sessionId = SessionId(rows.getString("session_id")),
        type = EventType.valueOf(rows.getString("type")),
        source = EventSource.valueOf(rows.getString("source")),
        hostTimestamp = Instant.ofEpochMilli(rows.getLong("host_millis")),
        deviceTimestamp = rows.getLong("device_millis").takeUnless { rows.wasNull() }?.let(Instant::ofEpochMilli),
        monotonicNanos = rows.getLong("monotonic_nanos").takeUnless { rows.wasNull() },
        monotonicClockDomain = ClockDomain.valueOf(rows.getString("clock_domain")),
        process = rows.getString("process_json")?.let(::decodeProcess),
        tid = rows.getLong("tid").takeUnless { rows.wasNull() },
        severity = rows.getString("severity")?.let(EventSeverity::valueOf),
        sourceSequence = rows.getLong("source_sequence").takeUnless { rows.wasNull() },
        operationId = rows.getString("operation_id")?.let(::OperationId),
        clockCalibration = rows.getString("calibration_json")?.let(::decodeCalibration),
        artifactRefs = Json.parseToJsonElement(rows.getString("artifacts_json")).jsonArray.map { ArtifactId(it.jsonPrimitive.content) },
        data = Json.parseToJsonElement(rows.getString("data_json")).jsonObject,
    )

    private fun encodeProcess(process: ProcessRef): String = buildJsonObject {
        put("deviceId", process.deviceId.value)
        put("pid", process.pid.value)
        put("packageName", process.packageName)
        put("instanceId", process.instanceId.value)
        put("startToken", process.startToken)
    }.toString()

    private fun decodeProcess(raw: String): ProcessRef {
        val json = Json.parseToJsonElement(raw).jsonObject
        return ProcessRef(
            DeviceId(json["deviceId"]!!.jsonPrimitive.content),
            ProcessId(json["pid"]!!.jsonPrimitive.content.toInt()),
            json["packageName"]?.jsonPrimitive?.content,
            ProcessInstanceId(json["instanceId"]!!.jsonPrimitive.content),
            json["startToken"]?.jsonPrimitive?.content,
        )
    }

    private fun encodeCalibration(calibration: ClockCalibration): String = buildJsonObject {
        put("source", calibration.sourceClock.name)
        put("target", calibration.targetClock.name)
        put("offset", calibration.offsetNanos)
        put("uncertainty", calibration.uncertaintyNanos)
        put("samples", calibration.sampleCount)
        put("measuredAt", calibration.measuredAt.toEpochMilli())
        put("method", calibration.method)
        put("status", calibration.status.name)
    }.toString()

    private fun decodeCalibration(raw: String): ClockCalibration {
        val json = Json.parseToJsonElement(raw).jsonObject
        return ClockCalibration(
            ClockDomain.valueOf(json["source"]!!.jsonPrimitive.content),
            ClockDomain.valueOf(json["target"]!!.jsonPrimitive.content),
            json["offset"]?.jsonPrimitive?.content?.toLongOrNull(),
            json["uncertainty"]?.jsonPrimitive?.content?.toLongOrNull(),
            json["samples"]!!.jsonPrimitive.content.toInt(),
            Instant.ofEpochMilli(json["measuredAt"]!!.jsonPrimitive.content.toLong()),
            json["method"]!!.jsonPrimitive.content,
            ClockCalibrationStatus.valueOf(json["status"]!!.jsonPrimitive.content),
        )
    }

    private fun cursor(seq: Long, hostTimestamp: Instant): String = "${hostTimestamp.toEpochMilli()}:$seq"

    private fun parseCursor(cursor: String): Pair<Long, Long> {
        val parts = cursor.split(':')
        require(parts.size == 2) { "invalid event cursor" }
        return parts[0].toLong() to parts[1].toLong()
    }

    private fun connection(): Connection = DriverManager.getConnection(url).apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    override fun close() = Unit
}
