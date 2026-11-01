package dev.admcp.toolchain

import dev.admcp.core.model.ResolvedTool
import dev.admcp.core.model.ToolKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

class ToolResolver(
    private val environment: ResolverEnvironment,
    private val inspector: ExecutableInspector,
) {
    fun resolve(kind: ToolKind, targetAbi: String? = null): ResolvedTool? {
        if (kind == ToolKind.LLDB_SERVER) return resolveLldbServer(targetAbi)
        if (kind == ToolKind.NDK) return resolveNdk()
        if (kind == ToolKind.ANDROID_SDK) return resolveSdk()
        if (kind == ToolKind.BUILD_TOOLS) return null
        if (kind == ToolKind.GRADLE) {
            resolveGradleWrapper()?.let { return tool(kind, it, "project-wrapper") }
        }
        explicitCandidates(kind).firstOrNull(::usable)?.let { return tool(kind, it, "explicit-config") }
        sdkCandidates(kind).firstOrNull(::usable)?.let { return tool(kind, it, "android-sdk") }
        environment.pathDirectories.asSequence()
            .map { it.resolve(executableName(kind)) }
            .firstOrNull(::usable)
            ?.let { return tool(kind, it, "path") }
        return null
    }

    private fun resolveGradleWrapper(): Path? {
        val root = environment.workspaceRoot ?: return null
        val name = if (isWindows()) "gradlew.bat" else "gradlew"
        return root.resolve(name).takeIf { it.isRegularFile() }
    }

    private fun resolveNdk(): ResolvedTool? {
        val candidates = buildList {
            listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").forEach { key ->
                environment.environment[key]?.let { add(Path.of(it)) }
            }
            localSdk()?.let { sdk ->
                sdk.resolve("ndk").takeIf { it.isDirectory() }?.let { ndkDir ->
                    runCatching {
                        Files.list(ndkDir).use { stream ->
                            stream.filter { it.isDirectory() }.sorted(Comparator.reverseOrder()).toList()
                        }
                    }.getOrDefault(emptyList()).forEach(::add)
                }
                add(sdk.resolve("ndk-bundle"))
            }
        }
        return candidates.firstOrNull { it.isDirectory() }?.let { tool(ToolKind.NDK, it, "ndk") }
    }

    private fun resolveSdk(): ResolvedTool? = localSdk()?.let { tool(ToolKind.ANDROID_SDK, it, "local-properties") }

    private fun localSdk(): Path? {
        localPropertiesSdk()?.let { configured ->
            if (configured.isDirectory()) return configured
        }
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { key ->
            environment.environment[key]?.let { value ->
                val path = Path.of(value)
                if (path.isDirectory()) return path
            }
        }
        return null
    }

    private fun localPropertiesSdk(): Path? {
        val root = environment.workspaceRoot ?: return null
        val file = root.resolve("local.properties")
        if (!file.isRegularFile()) return null
        return runCatching {
            Files.readAllLines(file).firstOrNull { it.trim().startsWith("sdk.dir=") }
                ?.substringAfter('=')?.trim()
                ?.replace("\\\\", "\\")
                ?.let(Path::of)
        }.getOrNull()
    }

    private fun explicitCandidates(kind: ToolKind): List<Path> = when (kind) {
        ToolKind.FLUTTER -> environment.environment["FLUTTER_ROOT"]?.let { listOf(Path.of(it, "bin", executableName(kind))) } ?: emptyList()
        ToolKind.DART -> environment.environment["DART_SDK"]?.let { listOf(Path.of(it, "bin", executableName(kind))) } ?: emptyList()
        ToolKind.JDK -> environment.environment["JAVA_HOME"]?.let { listOf(Path.of(it, "bin", executableName(kind))) } ?: emptyList()
        ToolKind.LLDB -> lldbRootCandidates().map { it.resolve("bin").resolve(executableName(kind)) }
        ToolKind.LLDB_DAP -> buildList {
            environment.environment["LLDB_DAP"]?.let { add(Path.of(it)) }
            lldbRootCandidates().forEach { add(it.resolve("bin").resolve(executableName(kind))) }
        }
        else -> emptyList()
    }

    private fun lldbRootCandidates(): List<Path> = listOfNotNull(
        environment.environment["LLVM_HOME"],
        environment.environment["LLDB_HOME"],
        environment.environment["LLVM_ROOT"],
    ).map(Path::of)

    private fun sdkCandidates(kind: ToolKind): List<Path> {
        val sdk = localSdk() ?: return emptyList()
        val name = executableName(kind)
        return when (kind) {
            ToolKind.ADB -> listOf(sdk.resolve("platform-tools").resolve(name))
            ToolKind.ANDROID_CLI -> listOf(sdk.resolve("cmdline-tools/latest/bin").resolve(name))
            ToolKind.LLDB -> buildList {
                ndkRoots(sdk).forEach { ndk ->
                    add(ndk.resolve("toolchains/llvm/prebuilt").resolve(ndkHostTag()).resolve("bin").resolve(name))
                }
                add(sdk.resolve("lldb").resolve("bin").resolve(name))
            }
            ToolKind.LLDB_DAP -> buildList {
                add(sdk.resolve("lldb").resolve("bin").resolve(name))
                ndkRoots(sdk).forEach { ndk ->
                    add(ndk.resolve("toolchains/llvm/prebuilt").resolve(ndkHostTag()).resolve("bin").resolve(name))
                }
            }
            else -> emptyList()
        }
    }

    private fun ndkRoots(sdk: Path): List<Path> {
        val roots = buildList {
            listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").forEach { key ->
                environment.environment[key]?.let { add(Path.of(it)) }
            }
            val ndkDir = sdk.resolve("ndk")
            if (ndkDir.isDirectory()) {
                runCatching {
                    Files.list(ndkDir).use { stream ->
                        stream.filter { it.isDirectory() }.sorted(Comparator.reverseOrder()).toList()
                    }
                }.getOrDefault(emptyList()).forEach(::add)
            }
            sdk.resolve("ndk-bundle").takeIf { it.isDirectory() }?.let(::add)
        }
        return roots.filter { it.isDirectory() }
    }

    private fun ndkHostTag(): String {
        val os = environment.hostOs.lowercase()
        val arch = environment.hostArch.lowercase()
        val osTag = when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "darwin"
            else -> "linux"
        }
        val archTag = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x86_64"
        }
        return "$osTag-$archTag"
    }

    private fun resolveLldbServer(targetAbi: String?): ResolvedTool? {
        if (targetAbi.isNullOrBlank()) return null
        val abi = abiToken(targetAbi)
        val roots = localSdk()?.let(::ndkRoots) ?: listOfNotNull(
            environment.environment["ANDROID_NDK_HOME"]?.let(Path::of),
            environment.environment["ANDROID_NDK_ROOT"]?.let(Path::of),
        )
        for (root in roots) {
            if (!root.isDirectory()) continue
            val matches = runCatching {
                Files.walk(root, 12).use { stream ->
                    stream.filter { it.fileName?.toString() == "lldb-server" && it.isRegularFile() }
                        .filter { path ->
                            path.parent?.fileName?.toString()?.lowercase() == abi
                        }
                        .toList()
                }
            }.getOrDefault(emptyList())
            matches.firstOrNull()?.let { return tool(ToolKind.LLDB_SERVER, it, "ndk", targetAbi) }
        }
        return null
    }

    private fun abiToken(targetAbi: String): String = when (targetAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armeabi" -> "arm"
        "x86" -> "x86"
        "x86_64" -> "x86_64"
        else -> targetAbi
    }

    private fun usable(path: Path): Boolean {
        if (!path.isRegularFile()) return false
        if (isWindows()) {
            val name = path.fileName.toString().lowercase()
            return name.endsWith(".exe") || name.endsWith(".bat") || name.endsWith(".cmd")
        }
        return Files.isExecutable(path)
    }

    private fun executableName(kind: ToolKind): String {
        val base = ToolchainManifest.names[kind] ?: kind.name.lowercase()
        return if (isWindows() && kind !in setOf(ToolKind.GRADLE, ToolKind.ANDROID_SDK, ToolKind.NDK, ToolKind.BUILD_TOOLS)) {
            if (base.endsWith(".bat") || base.endsWith(".exe")) base else "$base.exe"
        } else {
            base
        }
    }

    private fun isWindows(): Boolean = environment.hostOs.lowercase().contains("win")

    private fun tool(kind: ToolKind, path: Path, provenance: String, targetAbi: String? = null): ResolvedTool =
        ResolvedTool(kind, path, inspector.version(path), environment.hostOs, environment.hostArch, targetAbi, provenance, inspector.sha256(path))
}
