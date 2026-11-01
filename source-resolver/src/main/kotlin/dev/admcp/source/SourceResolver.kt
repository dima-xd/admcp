package dev.admcp.source

import dev.admcp.core.model.SourceLocation
import dev.admcp.core.model.SourceResolution
import dev.admcp.core.model.SymbolMatch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

data class BuildSourceMap(val buildId: String, val roots: List<Path>)

class SourceResolver(private val roots: List<Path>, private val expectedBuildId: String?) {
    private val canonicalRoots = roots.map { it.toRealPath() }

    fun resolve(path: String, line: Int, currentBuildId: String? = expectedBuildId): SourceResolution {
        if (expectedBuildId != null && currentBuildId != null && expectedBuildId != currentBuildId) {
            return SourceResolution(null, dev.admcp.core.model.Availability.UNAVAILABLE, "build identity mismatch")
        }
        val candidates = canonicalRoots.asSequence()
            .map { root -> root.resolve(path).normalize() }
            .filter { it.startsWithAny(canonicalRoots) }
            .toList()
        val existing = candidates.firstOrNull { it.isRegularFile() }
        if (existing == null) return SourceResolution(null, dev.admcp.core.model.Availability.UNAVAILABLE, "source file not found")
        val real = existing.toRealPath()
        if (!real.startsWithAny(canonicalRoots)) {
            return SourceResolution(null, dev.admcp.core.model.Availability.UNAVAILABLE, "source path escapes workspace")
        }
        val lineCount = Files.readAllLines(real).size
        if (lineCount == 0) return SourceResolution(null, dev.admcp.core.model.Availability.UNAVAILABLE, "source file is empty")
        val resolvedLine = line.coerceIn(1, lineCount)
        return SourceResolution(
            SourceLocation(real, resolvedLine, null),
            if (resolvedLine == line) dev.admcp.core.model.Availability.AVAILABLE else dev.admcp.core.model.Availability.LIMITED,
            if (resolvedLine == line) null else "requested line was outside the file; nearest valid line returned",
        )
    }

    fun matchNativeSymbol(symbolPath: Path, expectedBuildId: String?, actualBuildId: String?): SymbolMatch {
        if (expectedBuildId == null || actualBuildId == null) {
            return SymbolMatch(false, null, expectedBuildId, actualBuildId, "missing build identity evidence")
        }
        if (expectedBuildId != actualBuildId) {
            return SymbolMatch(false, null, expectedBuildId, actualBuildId, "build identity mismatch")
        }
        if (!symbolPath.exists() || !symbolPath.isRegularFile()) {
            return SymbolMatch(false, null, expectedBuildId, actualBuildId, "symbol file not found")
        }
        return SymbolMatch(true, symbolPath, expectedBuildId, actualBuildId, null)
    }

    private fun Path.startsWithAny(rootList: List<Path>): Boolean = rootList.any { startsWith(it) }
}
