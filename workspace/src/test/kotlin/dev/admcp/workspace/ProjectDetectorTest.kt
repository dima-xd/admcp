package dev.admcp.workspace

import dev.admcp.core.model.Availability
import dev.admcp.core.model.ProjectKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectDetectorTest {
    @Test
    fun detectsMultiModuleAndroidGradleProject() {
        val root = tempDir()
        root.resolve("settings.gradle.kts").writeText("include(\":app\", \":feature\")\n")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
        root.resolve("app").createDirectories()
        root.resolve("app/build.gradle.kts").writeText("android { namespace = \"dev.admcp.app\" }\n")
        val project = ProjectDetector().detect(root)
        assertEquals(ProjectKind.ANDROID_GRADLE, project.kind)
        assertEquals(2, project.modules.size)
        assertTrue(project.packageNames.contains("dev.admcp.app"))
    }

    @Test
    fun detectsAndroidBazelProjectWhenCliDescribesIt() {
        val root = tempDir()
        root.resolve("WORKSPACE").writeText("workspace(name = \"fixture\")\n")
        root.resolve("app/BUILD.bazel").createDirectories().resolve("BUILD.bazel").writeText("android_binary(name = \"app\")\n")
        val project = ProjectDetector(bazelBuildProven = true).detect(root)
        assertEquals(ProjectKind.ANDROID_BAZEL, project.kind)
        assertEquals(Availability.AVAILABLE, project.buildSupport)
    }

    @Test
    fun reportsBazelUnavailableWhenCliCannotBuildIt() {
        val root = tempDir()
        root.resolve("WORKSPACE").writeText("workspace(name = \"fixture\")\n")
        val project = ProjectDetector(bazelBuildProven = false).detect(root)
        assertEquals(ProjectKind.ANDROID_BAZEL, project.kind)
        assertEquals(Availability.LIMITED, project.buildSupport)
    }

    @Test
    fun detectsFlutterAndroidProject() {
        val root = tempDir()
        root.resolve("pubspec.yaml").writeText("name: fixture\n")
        root.resolve("android/app").createDirectories()
        assertEquals(ProjectKind.FLUTTER, ProjectDetector().detect(root).kind)
    }

    @Test
    fun detectsApkOnlyWorkspace() {
        val root = tempDir()
        root.resolve("fixture.apk").writeText("apk")
        assertEquals(ProjectKind.APK_ONLY, ProjectDetector().detect(root).kind)
    }

    @Test
    fun doesNotCallDetectedBuildSystemSupportedWithoutBuildProbeEvidence() {
        val root = tempDir()
        root.resolve("build.gradle.kts").writeText("plugins { id(\"com.android.application\") }\n")
        val project = ProjectDetector().detect(root)
        assertEquals(Availability.LIMITED, project.buildSupport)
    }

    private fun tempDir(): Path = Files.createTempDirectory("admcp-project-")
}
