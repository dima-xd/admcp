package dev.admcp.app.lifecycle

import dev.admcp.core.model.BuildArtifact
import dev.admcp.core.model.BuildOptions
import dev.admcp.core.model.BuildTarget
import dev.admcp.core.model.BuildResult
import dev.admcp.core.model.CommandResult
import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.ProjectKind
import dev.admcp.core.model.TrustStatus
import dev.admcp.core.model.WorkspaceOperation
import dev.admcp.workspace.Workspace
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AppLifecycleTest {
    @Test
    fun buildUsesApprovedAndroidCliOrWrapperFallback() = runBlocking {
        val executor = FakeExecutor(0)
        val service = AppBuildService(executor)
        val workspace = workspace()
        val result = service.build(workspace, BuildTarget(":app", "debug", dev.admcp.core.model.ArtifactKind.APK), BuildOptions(), trusted = true)
        assertTrue(result.success)
        assertEquals(1, executor.calls.size)
    }

    @Test
    fun workspaceBuildRequiresTrust() = runBlocking {
        val service = AppBuildService(FakeExecutor(0))
        assertFailsWith<SecurityException> {
            service.build(workspace(), BuildTarget(":app", "debug", dev.admcp.core.model.ArtifactKind.APK), BuildOptions(), trusted = false)
        }
    }

    @Test
    fun installTargetsExplicitDevice() = runBlocking {
        val installer = FakeInstaller()
        val service = AppInstallService(installer)
        val result = service.install(DeviceId("d1"), artifact(), listOf(DeviceId("d1"), DeviceId("d2")))
        assertTrue(result.success)
        assertEquals(DeviceId("d1"), installer.lastDevice)
    }

    @Test
    fun installFailsOnAmbiguousMultiDeviceTarget() {
        assertFailsWith<IllegalArgumentException> {
            DeviceTargeting.requireExplicit(null, listOf(DeviceId("d1"), DeviceId("d2")))
        }
    }

    @Test
    fun runRequiresExplicitDeviceAndBuildReference() {
        assertFailsWith<IllegalArgumentException> { DeviceTargeting.requireExplicit(null, listOf(DeviceId("d1"))) }
        assertFailsWith<IllegalArgumentException> { DeviceTargeting.requireBuildReference(" ") }
    }

    @Test
    fun launchVerifiesPackageAndProcessInstance() = runBlocking {
        val adb = FakeAdb()
        val service = AppLaunchService(adb)
        val process = service.launch(DeviceId("d1"), "dev.admcp.fixture", null)
        assertEquals("dev.admcp.fixture", process.ref.packageName)
        assertTrue(process.ref.instanceId.value.startsWith("pi-"))
    }

    @Test
    fun launchCanWaitForJavaDebugger() = runBlocking {
        val adb = FakeAdb()
        val service = AppLaunchService(adb)
        service.launch(DeviceId("d1"), "dev.admcp.fixture", null, waitForDebugger = true)
        assertEquals(listOf("am", "start", "-D", "-n", "dev.admcp.fixture/.MainActivity"), adb.calls.first())
    }

    @Test
    fun pidReuseProducesDifferentProcessInstanceId() = runBlocking {
        val adb = FakeAdb()
        val service = AppLaunchService(adb)
        val first = service.launch(DeviceId("d1"), "dev.admcp.fixture", null)
        val second = service.launch(DeviceId("d1"), "dev.admcp.fixture", null)
        assertNotEquals(first.ref.instanceId, second.ref.instanceId)
    }

    @Test
    fun buildDiscoversAndDescribesProducedApk() = runBlocking {
        val workspace = workspace()
        val output = workspace.root.resolve("app/build/outputs/apk/debug/app-debug.apk")
        Files.createDirectories(output.parent)
        Files.write(output, byteArrayOf(1, 2, 3))
        val result = AppBuildService(FakeExecutor(0)).build(
            workspace,
            BuildTarget(":app", "debug", dev.admcp.core.model.ArtifactKind.APK),
            BuildOptions(),
            trusted = true,
        )
        assertEquals(output, result.artifact?.path)
        assertEquals("debug", result.artifact?.variant)
        assertTrue(result.artifact?.debuggable == true)
        assertTrue(result.artifact?.buildIdentity?.contains("app-debug.apk") == true)
    }

    @Test
    fun buildFailurePreservesGradleOutputArtifact() = runBlocking {
        val executor = FakeExecutor(1)
        val service = AppBuildService(executor)
        val result = service.build(workspace(), BuildTarget(":app", "debug", dev.admcp.core.model.ArtifactKind.APK), BuildOptions(), trusted = true)
        assertEquals(false, result.success)
        assertEquals(1, executor.calls.size)
    }

    private fun workspace(): Workspace {
        val root = Files.createTempDirectory("admcp-lifecycle-")
        root.resolve("gradlew.bat").writeText("@echo off\n")
        return Workspace(root, ProjectKind.ANDROID_GRADLE, emptyList(), TrustStatus.TRUSTED)
    }

    private fun artifact(): BuildArtifact {
        val path = Files.createTempFile("fixture", ".apk")
        return BuildArtifact(path, "dev.admcp.fixture", "debug", true, "build-1")
    }

    private class FakeExecutor(vararg codes: Int) : BuildExecutor {
        private val queue = codes.toMutableList()
        val calls = mutableListOf<List<String>>()
        override suspend fun run(executable: Path, args: List<String>, workingDirectory: Path, environment: Map<String, String>): CommandResult {
            calls += listOf(executable.toString()) + args
            return CommandResult(queue.removeFirstOrNull() ?: 0, ByteArray(0), ByteArray(0))
        }
    }

    private class FakeInstaller : PackageInstaller {
        var lastDevice: DeviceId? = null
        override suspend fun install(deviceId: DeviceId, artifact: BuildArtifact): dev.admcp.core.model.InstallResult {
            lastDevice = deviceId
            return dev.admcp.core.model.InstallResult(true, artifact.packageName ?: "", deviceId, null)
        }
    }

    private class FakeAdb : LaunchAdb {
        val calls = mutableListOf<List<String>>()
        override suspend fun shell(deviceId: DeviceId, args: List<String>): CommandResult {
            calls += args
            return when {
                args.firstOrNull() == "am" -> CommandResult(0, "Starting: Intent\n".toByteArray(), ByteArray(0))
                args.firstOrNull() == "pidof" -> CommandResult(0, "1234\n".toByteArray(), ByteArray(0))
                else -> CommandResult(0, ByteArray(0), ByteArray(0))
            }
        }
    }
}
