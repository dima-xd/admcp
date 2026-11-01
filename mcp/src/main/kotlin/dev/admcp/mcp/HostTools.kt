package dev.admcp.mcp

import dev.admcp.core.model.CommandResult
import dev.admcp.core.process.DefaultProcessSupervisor
import dev.admcp.core.process.ProcessSpec
import dev.admcp.core.process.StdinMode
import dev.admcp.core.process.collectStderr
import dev.admcp.core.process.collectStdout
import dev.admcp.workspace.BuildEnvironmentPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.streams.toList

internal class HostCommandExecutor {
    suspend fun run(
        executable: Path,
        args: List<String>,
        workingDirectory: Path? = null,
        timeout: Duration = Duration.ofMinutes(5),
        environment: Map<String, String> = BuildEnvironmentPolicy.allowlist(System.getenv()),
    ): CommandResult {
        val process = DefaultProcessSupervisor().start(
            ProcessSpec(
                executable,
                args,
                workingDirectory,
                environment,
                timeout,
                StdinMode.CLOSED,
            ),
        )
        return coroutineScope {
            val stdout = async { process.collectStdout() }
            val stderr = async { process.collectStderr() }
            val exit = process.awaitExit()
            CommandResult(exit.exitCode ?: -1, stdout.await(), stderr.await())
        }
    }
}

internal fun resolveAndroidCli(config: ServerConfig): Path? {
    config.androidCli?.let { candidate ->
        if (Files.isRegularFile(candidate)) return candidate
    }
    val executable = if (isWindows()) "android.exe" else "android"
    System.getenv("ANDROID_CLI")?.let { configured ->
        val candidate = Path.of(configured)
        if (Files.isRegularFile(candidate)) return candidate
        val child = candidate.resolve(executable)
        if (Files.isRegularFile(child)) return child
    }
    System.getenv("PATH")?.split(java.io.File.pathSeparator)?.map(Path::of)?.forEach { directory ->
        val candidate = directory.resolve(executable)
        if (Files.isRegularFile(candidate)) return candidate
    }
    return null
}

internal fun resolveAapt2(config: ServerConfig): Path? {
    val sdk = config.platformTools?.parent
        ?: System.getenv("ANDROID_HOME")?.let(Path::of)
        ?: System.getenv("ANDROID_SDK_ROOT")?.let(Path::of)
        ?: return null
    val buildTools = sdk.resolve("build-tools")
    if (!Files.isDirectory(buildTools)) return null
    val candidates = runCatching {
        Files.list(buildTools).use { directories ->
            directories.filter { Files.isDirectory(it) }.toList()
        }
    }.getOrDefault(emptyList())
    val name = if (isWindows()) "aapt2.exe" else "aapt2"
    return candidates.sortedByDescending { it.fileName.toString() }
        .map { it.resolve(name) }
        .firstOrNull { Files.isRegularFile(it) }
}

internal data class ApkBadging(
    val packageName: String?,
    val versionCode: String?,
    val versionName: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val debuggable: Boolean,
    val nativeCode: List<String>,
    val launchableActivity: String?,
    val appLabel: String?,
)

internal fun readBadging(aapt2: Path, apk: Path, executor: HostCommandExecutor): ApkBadging? = runCatching {
    val result = kotlinx.coroutines.runBlocking {
        executor.run(
            aapt2,
            listOf("dump", "badging", apk.toString()),
            timeout = Duration.ofSeconds(60),
            environment = System.getenv(),
        )
    }
    if (result.exitCode != 0) return@runCatching null
    var packageName: String? = null
    var versionCode: String? = null
    var versionName: String? = null
    var minSdk: String? = null
    var targetSdk: String? = null
    var debuggable = false
    var nativeCode = emptyList<String>()
    var launchableActivity: String? = null
    var appLabel: String? = null
    result.stdoutText.lineSequence().forEach { line ->
        when {
            line.startsWith("package:") -> {
                packageName = valueAfter(line, "name='")
                versionCode = valueAfter(line, "versionCode='")
                versionName = valueAfter(line, "versionName='")
            }
            line.startsWith("minSdkVersion:") -> minSdk = line.substringAfter(':').trim().trim('\'')
            line.startsWith("targetSdkVersion:") -> targetSdk = line.substringAfter(':').trim().trim('\'')
            line.startsWith("application-debuggable") -> debuggable = true
            line.startsWith("native-code:") -> {
                nativeCode = line.substringAfter(':').trim().split(' ').map { it.trim('\'') }.filter { it.isNotBlank() }
            }
            line.startsWith("launchable-activity:") -> launchableActivity = valueAfter(line, "name='")
            line.startsWith("application-label:") -> appLabel = valueAfter(line, "'")
        }
    }
    ApkBadging(packageName, versionCode, versionName, minSdk, targetSdk, debuggable, nativeCode, launchableActivity, appLabel)
}.getOrNull()

private fun valueAfter(line: String, marker: String): String? {
    val start = line.indexOf(marker)
    if (start < 0) return null
    val begin = start + marker.length
    val end = line.indexOf('\'', begin)
    return if (end <= begin) null else line.substring(begin, end)
}

internal data class ZipSummary(
    val entries: Int,
    val dexFiles: Int,
    val nativeLibraries: List<String>,
    val hasManifest: Boolean,
    val hasResources: Boolean,
)

internal fun summarizeZip(path: Path): ZipSummary? = runCatching {
    ZipFile(path.toFile()).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        ZipSummary(
            entries = names.size,
            dexFiles = names.count { it.matches(Regex("""classes\d*\.dex""")) },
            nativeLibraries = names.filter { it.startsWith("lib/") && it.endsWith(".so") }.take(500),
            hasManifest = names.any { it == "AndroidManifest.xml" },
            hasResources = names.any { it == "resources.arsc" },
        )
    }
}.getOrNull()

internal fun parseJsonLoose(text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val objectStart = trimmed.indexOf('{')
    val arrayStart = trimmed.indexOf('[')
    val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull() ?: return null
    val end = maxOf(trimmed.lastIndexOf('}'), trimmed.lastIndexOf(']'))
    if (end <= start) return null
    return runCatching { Json.parseToJsonElement(trimmed.substring(start, end + 1)) }.getOrNull()
}

internal fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
