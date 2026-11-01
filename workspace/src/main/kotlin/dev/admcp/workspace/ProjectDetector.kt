package dev.admcp.workspace

import dev.admcp.core.model.Availability
import dev.admcp.core.model.ModuleDescriptor
import dev.admcp.core.model.ModuleKind
import dev.admcp.core.model.ProjectDescription
import dev.admcp.core.model.ProjectKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

data class DetectedProject(
    val kind: ProjectKind,
    val modules: List<ModuleDescriptor>,
    val description: ProjectDescription,
    val buildSupport: Availability,
) {
    val packageNames: List<String> get() = description.packageNames
}

class ProjectDetector(private val bazelBuildProven: Boolean = false) {
    fun detect(root: Path): DetectedProject {
        val canonical = root.toRealPath()
        if (canonical.resolve("settings.gradle.kts").isRegularFile() || canonical.resolve("settings.gradle").isRegularFile() ||
            canonical.resolve("build.gradle.kts").isRegularFile() || canonical.resolve("build.gradle").isRegularFile()
        ) return gradle(canonical)
        if (canonical.resolve("WORKSPACE").isRegularFile() || canonical.resolve("WORKSPACE.bazel").isRegularFile() ||
            canonical.resolve("MODULE.bazel").isRegularFile()
        ) return bazel(canonical)
        if (canonical.resolve("pubspec.yaml").isRegularFile() && canonical.resolve("android").isDirectory()) return flutter(canonical)
        val apk = runCatching {
            Files.walk(canonical, 2).use { stream -> stream.anyMatch { it.fileName?.toString()?.endsWith(".apk") == true } }
        }.getOrDefault(false)
        if (apk) return DetectedProject(
            ProjectKind.APK_ONLY,
            emptyList(),
            ProjectDescription(ProjectKind.APK_ONLY, emptyList(), emptyList(), emptyList()),
            Availability.AVAILABLE,
        )
        return DetectedProject(ProjectKind.UNKNOWN, emptyList(), ProjectDescription(ProjectKind.UNKNOWN, emptyList(), emptyList(), emptyList()), Availability.UNKNOWN)
    }

    private fun gradle(root: Path): DetectedProject {
        val settings = sequenceOf(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"))
            .firstOrNull { it.isRegularFile() }?.let { Files.readString(it) }.orEmpty()
        val included = Regex("""include\s*\(([^)]*)\)""").findAll(settings)
            .flatMap { match -> Regex("""["']:?([^"']+)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .map { it.trim().replace(':', '/') }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val modules = included.map {
            ModuleDescriptor(it.substringAfterLast('/'), root.resolve(it), ModuleKind.ANDROID_APP)
        }
        val packages = runCatching {
            val found = mutableListOf<String>()
            Files.walk(root, 5).use { stream ->
                stream.filter { it.fileName?.toString()?.startsWith("build.gradle") == true && it.isRegularFile() }
                    .forEach { file ->
                        Regex("""namespace\s*=\s*["']([^"']+)["']""").findAll(Files.readString(file))
                            .mapTo(found) { it.groupValues[1] }
                    }
            }
            found
        }.getOrDefault(emptyList())
        return DetectedProject(
            ProjectKind.ANDROID_GRADLE,
            modules,
            ProjectDescription(ProjectKind.ANDROID_GRADLE, packages.distinct(), emptyList(), listOf(root.resolve("app/src/main/java"))),
            Availability.LIMITED,
        )
    }

    private fun bazel(root: Path): DetectedProject = DetectedProject(
        ProjectKind.ANDROID_BAZEL,
        emptyList(),
        ProjectDescription(ProjectKind.ANDROID_BAZEL, emptyList(), emptyList(), emptyList()),
        if (bazelBuildProven) Availability.AVAILABLE else Availability.LIMITED,
    )

    private fun flutter(root: Path): DetectedProject = DetectedProject(
        ProjectKind.FLUTTER,
        listOf(ModuleDescriptor("android", root.resolve("android"), ModuleKind.FLUTTER_MODULE)),
        ProjectDescription(ProjectKind.FLUTTER, emptyList(), emptyList(), listOf(root.resolve("lib"), root.resolve("android/app/src/main"))),
        Availability.AVAILABLE,
    )
}
