package dev.admcp.workspace

import java.nio.file.Files
import java.nio.file.Path

class WorkspacePathGuard(root: Path) {
    private val canonicalRoot = root.toRealPath()

    fun resolve(relative: String): Path {
        val candidate = canonicalRoot.resolve(relative).normalize()
        require(candidate.startsWith(canonicalRoot)) { "path escapes workspace root" }
        return canonicalize(candidate)
    }

    fun revalidate(path: Path): Path {
        require(Files.exists(path)) { "path no longer exists" }
        val canonical = canonicalize(path)
        require(canonical.startsWith(canonicalRoot)) { "path escapes workspace root" }
        return canonical
    }

    private fun canonicalize(path: Path): Path {
        var existing = path.toAbsolutePath().normalize()
        val suffix = ArrayDeque<Path>()
        while (!Files.exists(existing)) {
            suffix.addFirst(existing.fileName ?: throw IllegalArgumentException("invalid path"))
            existing = existing.parent ?: throw IllegalArgumentException("path has no existing ancestor")
        }
        val real = existing.toRealPath()
        var result = real
        suffix.forEach { result = result.resolve(it) }
        require(result.normalize().startsWith(canonicalRoot)) { "path escapes workspace root" }
        return result.normalize()
    }
}
