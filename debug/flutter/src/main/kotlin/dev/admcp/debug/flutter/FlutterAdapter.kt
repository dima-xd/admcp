package dev.admcp.debug.flutter

import dev.admcp.core.model.Availability
import dev.admcp.core.model.Capability
import dev.admcp.core.model.CapabilityId
import dev.admcp.core.model.DeviceId
import java.nio.file.Files
import java.nio.file.Path

data class FlutterSdk(val root: Path, val flutter: Path, val dart: Path, val version: String?) {
    fun adapterCommand(): List<String> = listOf(flutter.toString(), "debug-adapter", "--no-dds")
}

class FlutterResolver(private val allowedRoots: List<Path> = emptyList()) {
    fun resolve(configuredRoot: Path?, pathDirs: List<Path>): FlutterSdk? {
        val roots = buildList {
            configuredRoot?.let(::add)
            pathDirs.forEach { dir ->
                runCatching {
                    val real = dir.toRealPath()
                    if (real.fileName.toString().equals("bin", ignoreCase = true)) add(real.parent)
                }
            }
        }.distinct()
        for (root in roots) {
            val flutter = root.resolve("bin/flutter.bat").takeIf { Files.isRegularFile(it) }
                ?: root.resolve("bin/flutter").takeIf { Files.isRegularFile(it) }
                ?: continue
            if (allowedRoots.isNotEmpty() && allowedRoots.none { flutter.toRealPath().startsWith(it.toRealPath()) }) continue
            val dart = root.resolve("bin/dart.bat").takeIf { Files.isRegularFile(it) }
                ?: root.resolve("bin/dart").takeIf { Files.isRegularFile(it) }
                ?: continue
            return FlutterSdk(root, flutter, dart, null)
        }
        return null
    }
}

data class FlutterAdapterPlan(
    val command: List<String>,
    val environment: Map<String, String>,
    val availability: Availability,
    val evidence: List<String>,
)

class FlutterAdapterPlanner(private val resolver: FlutterResolver) {
    fun plan(configuredRoot: Path?, pathDirs: List<Path>, deviceId: DeviceId, target: Path?): FlutterAdapterPlan {
        val sdk = resolver.resolve(configuredRoot, pathDirs)
            ?: return FlutterAdapterPlan(emptyList(), emptyMap(), Availability.UNAVAILABLE, listOf("Flutter SDK was not resolved"))
        val command = buildList {
            addAll(sdk.adapterCommand())
            add("--device-id")
            add(deviceId.value)
            target?.let { add("--target=$it") }
        }
        return FlutterAdapterPlan(command, mapOf("FLUTTER_ROOT" to sdk.root.toString()), Availability.AVAILABLE, listOf("sdk=${sdk.root}"))
    }
}

fun FlutterAdapterPlan.capabilities(): List<Capability> = listOf(
    Capability(
        CapabilityId("flutter.dap"),
        availability,
        evidence,
        if (availability == Availability.AVAILABLE) null else "Flutter SDK or adapter unavailable",
    ),
)
