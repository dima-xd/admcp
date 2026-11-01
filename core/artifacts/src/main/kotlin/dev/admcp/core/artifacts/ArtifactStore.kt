package dev.admcp.core.artifacts

import dev.admcp.core.model.ArtifactKind
import dev.admcp.core.model.RedactionState
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ArtifactRecord(
    val id: String,
    val kind: ArtifactKind,
    val relativePath: String,
    val sha256: String,
    val sizeBytes: Long,
    val createdAt: Instant,
    val redaction: RedactionState,
    val truncated: Boolean,
)

data class ArtifactRead(val bytes: ByteArray, val record: ArtifactRecord, val truncated: Boolean)

data class RecoveryReport(val committed: Int, val recovered: Int, val orphaned: Int, val corrupt: Int)

class ArtifactStore(
    private val root: Path,
    private val maxBytes: Long = 100L * 1024 * 1024,
    private val maxReadBytes: Int = 4 * 1024 * 1024,
) {
    private val records = ConcurrentHashMap<String, ArtifactRecord>()
    private val rootReal: Path

    init {
        Files.createDirectories(root)
        rootReal = root.toRealPath()
        loadMetadata()
    }

    fun put(kind: ArtifactKind, bytes: ByteArray, redaction: RedactionState = RedactionState.RAW): ArtifactRecord {
        require(bytes.size.toLong() <= maxBytes) { "artifact exceeds size limit" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val id = UUID.randomUUID().toString()
        val relative = digest.take(2) + "/" + digest + "-" + id
        val target = resolve(relative)
        Files.createDirectories(target.parent)
        val staging = resolve(".staging-$id")
        Files.newOutputStream(staging).use { output ->
            output.write(bytes)
            output.flush()
        }
        Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        val record = ArtifactRecord(id, kind, relative, digest, bytes.size.toLong(), Instant.now(), redaction, truncated = false)
        writeMetadata(target, record)
        records[id] = record
        return record
    }

    fun putText(kind: ArtifactKind, text: String, redaction: RedactionState = RedactionState.RAW): ArtifactRecord =
        put(kind, text.toByteArray(), redaction)

    fun read(id: String, maxBytes: Int = maxReadBytes): ArtifactRead {
        val record = records[id] ?: throw IllegalArgumentException("artifact not found: $id")
        val path = resolve(record.relativePath)
        val size = Files.size(path)
        val limit = minOf(size, maxBytes.toLong(), maxReadBytes.toLong()).toInt()
        val bytes = Files.newInputStream(path).use { it.readNBytes(limit) }
        return ArtifactRead(bytes, record, truncated = limit.toLong() < size)
    }

    fun readAll(id: String, limitBytes: Long = maxBytes): ArtifactRead {
        val record = records[id] ?: throw IllegalArgumentException("artifact not found: $id")
        val path = resolve(record.relativePath)
        val size = Files.size(path)
        require(size <= limitBytes) { "artifact exceeds the full-read limit" }
        return ArtifactRead(Files.readAllBytes(path), record, truncated = false)
    }

    fun metadata(id: String): ArtifactRecord? = records[id]

    fun all(): List<ArtifactRecord> = records.values.toList()

    fun reconcile(): RecoveryReport {
        var orphaned = 0
        var missing = 0
        var corrupt = 0
        val known = records.values.map { it.relativePath }.toSet()
        Files.walk(rootReal).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val relative = rootReal.relativize(path).toString().replace('\\', '/')
                if (!relative.endsWith(".meta") && relative !in known &&
                    !relative.substringAfterLast('/').startsWith(".staging-")
                ) orphaned++
            }
        }
        records.values.toList().forEach { record ->
            val path = resolve(record.relativePath)
            if (!Files.isRegularFile(path)) {
                missing++
                records.remove(record.id)
            } else if (Files.size(path) != record.sizeBytes) {
                corrupt++
            }
        }
        return RecoveryReport(records.size, orphaned, missing, corrupt)
    }

    fun delete(id: String): Boolean {
        val record = records.remove(id) ?: return false
        val path = resolve(record.relativePath)
        Files.deleteIfExists(metadataPath(path))
        Files.deleteIfExists(path)
        return true
    }

    fun resolve(relative: String): Path {
        val candidate = rootReal.resolve(relative).normalize()
        require(candidate.startsWith(rootReal)) { "artifact path escapes store root" }
        return candidate
    }

    private fun loadMetadata() {
        Files.walk(rootReal).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".meta") &&
                    !it.fileName.toString().startsWith(".staging-")
            }.forEach { metadata ->
                    runCatching {
                        val properties = Properties()
                        Files.newInputStream(metadata).use(properties::load)
                        val id = properties.getProperty("id") ?: return@runCatching
                        val relative = properties.getProperty("relativePath") ?: return@runCatching
                        val target = resolve(relative)
                        if (!Files.isRegularFile(target)) return@runCatching
                        val size = properties.getProperty("sizeBytes")?.toLongOrNull() ?: Files.size(target)
                        if (Files.size(target) != size) return@runCatching
                        records[id] = ArtifactRecord(
                            id = id,
                            kind = ArtifactKind.valueOf(properties.getProperty("kind")),
                            relativePath = relative,
                            sha256 = properties.getProperty("sha256"),
                            sizeBytes = size,
                            createdAt = Instant.parse(properties.getProperty("createdAt")),
                            redaction = RedactionState.valueOf(properties.getProperty("redaction")),
                            truncated = properties.getProperty("truncated").toBoolean(),
                        )
                    }
                }
        }
    }

    private fun writeMetadata(target: Path, record: ArtifactRecord) {
        val metadata = metadataPath(target)
        val staging = resolve(".staging-${record.id}.meta")
        val properties = Properties().apply {
            setProperty("id", record.id)
            setProperty("kind", record.kind.name)
            setProperty("relativePath", record.relativePath)
            setProperty("sha256", record.sha256)
            setProperty("sizeBytes", record.sizeBytes.toString())
            setProperty("createdAt", record.createdAt.toString())
            setProperty("redaction", record.redaction.name)
            setProperty("truncated", record.truncated.toString())
        }
        Files.newOutputStream(staging).use { output -> properties.store(output, "admcp artifact") }
        Files.move(
            staging,
            metadata,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun metadataPath(target: Path): Path = target.resolveSibling(target.fileName.toString() + ".meta")
}
