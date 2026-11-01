package dev.admcp.app.lifecycle

import dev.admcp.core.model.BuildArtifact
import dev.admcp.core.model.BuildOptions
import dev.admcp.core.model.BuildResult
import dev.admcp.core.model.BuildTarget
import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.ProjectKind
import dev.admcp.workspace.BuildEnvironmentPolicy
import dev.admcp.workspace.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

interface BuildExecutor {
    suspend fun run(executable: Path, args: List<String>, workingDirectory: Path, environment: Map<String, String>): CommandResult
}

interface AppBuildOperations {
    suspend fun build(workspace: Workspace, target: BuildTarget, options: BuildOptions, trusted: Boolean): BuildResult
}

class AppBuildService(private val executor: BuildExecutor) : AppBuildOperations {
    override suspend fun build(workspace: Workspace, target: BuildTarget, options: BuildOptions, trusted: Boolean): BuildResult {
        if (!trusted) throw SecurityException("workspace trust is required before project-controlled build execution")
        val wrapper = resolveWrapper(workspace.root)
        val args = buildList {
            add(target.modulePath + ":assemble" + target.variant.replaceFirstChar { it.uppercase() })
            if (options.clean) add(0, "clean")
            if (options.offline) add("--offline")
            if (options.stacktrace) add("--stacktrace")
        }
        val result = executor.run(wrapper, args, workspace.root, BuildEnvironmentPolicy.allowlist(System.getenv()))
        val success = result.exitCode == 0
        val artifact = if (success) findArtifact(workspace.root, target) else null
        return BuildResult(
            success = success,
            artifact = artifact,
            outputArtifact = null,
            exitCode = result.exitCode,
            stdout = result.stdoutText,
            stderr = result.stderrText,
        )
    }

    private fun resolveWrapper(root: Path): Path {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val preferred = if (windows) "gradlew.bat" else "gradlew"
        val fallback = if (windows) "gradlew" else "gradlew.bat"
        return listOf(preferred, fallback)
            .map(root::resolve)
            .firstOrNull { it.isRegularFile() }
            ?: Path.of(if (windows) "gradle.bat" else "gradle")
    }

    private fun findArtifact(root: Path, target: BuildTarget): BuildArtifact? {
        val module = root.resolve(target.modulePath.trimStart(':').replace(':', '/'))
        val outputRoot = module.resolve("build").resolve("outputs")
        if (!Files.isDirectory(outputRoot)) return null
        val extension = when (target.artifactKind) {
            dev.admcp.core.model.ArtifactKind.AAB -> ".aab"
            dev.admcp.core.model.ArtifactKind.AAR -> ".aar"
            else -> ".apk"
        }
        val candidates = runCatching {
            Files.walk(outputRoot, 6).use { stream ->
                stream.filter { path ->
                    Files.isRegularFile(path) && path.fileName.toString().endsWith(extension, ignoreCase = true)
                }.toList()
            }
        }.getOrDefault(emptyList())
        val selected = candidates
            .filter { path -> path.fileName.toString().contains(target.variant, ignoreCase = true) }
            .ifEmpty { candidates }
            .maxByOrNull { path -> runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0L) }
            ?: return null
        val identity = "${target.variant}-${selected.fileName}-${runCatching { Files.size(selected) }.getOrDefault(0L)}"
        return BuildArtifact(
            path = selected,
            packageName = null,
            variant = target.variant,
            debuggable = target.variant.contains("debug", ignoreCase = true),
            buildIdentity = identity,
        )
    }
}

object AndroidCliBuildPaths {
    fun supports(workspace: Workspace): Boolean = workspace.kind != ProjectKind.UNKNOWN && workspace.root.resolve("gradlew").isRegularFile()
}
