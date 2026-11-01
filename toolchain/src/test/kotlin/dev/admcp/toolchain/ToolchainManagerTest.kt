package dev.admcp.toolchain

import dev.admcp.core.model.Availability
import dev.admcp.core.model.ToolKind
import dev.admcp.core.model.ToolchainRequest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolchainManagerTest {
    private val inspector = FakeInspector()
    private val hostOs = if (System.getProperty("os.name").lowercase().contains("win")) "Windows" else "Linux"
    private val exe = if (hostOs == "Windows") ".exe" else ""

    @Test
    fun doesNotTrustFirstPathEntry() {
        val first = tempDir().resolve("first").createDirectories()
        val second = tempDir().resolve("second").createDirectories()
        first.resolve("adb$exe").createDirectories()
        makeExecutable(Files.createFile(second.resolve("adb$exe")))
        val resolver = resolver(pathDirs = listOf(first, second))
        val resolved = resolver.resolve(ToolKind.ADB)
        assertEquals(second.resolve("adb$exe"), resolved?.executable)
    }

    @Test
    fun prefersProjectGradleWrapper() {
        val root = tempDir()
        makeExecutable(Files.createFile(root.resolve(if (hostOs == "Windows") "gradlew.bat" else "gradlew")))
        val pathDir = tempDir().createDirectories()
        makeExecutable(Files.createFile(pathDir.resolve("gradle$exe")))
        val resolver = resolver(root, pathDirs = listOf(pathDir))
        val resolved = resolver.resolve(ToolKind.GRADLE)
        assertEquals(root.resolve(if (hostOs == "Windows") "gradlew.bat" else "gradlew"), resolved?.executable)
    }

    @Test
    fun resolvesNdkFromLocalPropertiesAndSdk() {
        val sdk = tempDir()
        val ndk = sdk.resolve("ndk/26.1.10909125").createDirectories()
        Files.createFile(ndk.resolve("source.properties"))
        val root = tempDir()
        root.resolve("local.properties").writeText("sdk.dir=${sdk.toString().replace("\\", "\\\\")}\n")
        val resolver = resolver(root, env = mapOf("ANDROID_HOME" to sdk.toString()))
        val resolved = resolver.resolve(ToolKind.NDK)
        assertEquals(ndk, resolved?.executable)
    }

    @Test
    fun rejectsHostIncompatibleExecutable() {
        val dir = tempDir().createDirectories()
        Files.createFile(dir.resolve("adb"))
        val resolver = resolver(pathDirs = listOf(dir), hostOsValue = "Windows")
        assertNull(resolver.resolve(ToolKind.ADB))
    }

    @Test
    fun doesNotTreatLldbAsLldbDap() {
        val dir = tempDir().createDirectories()
        makeExecutable(Files.createFile(dir.resolve("lldb$exe")))
        val resolver = resolver(pathDirs = listOf(dir))
        assertTrue(resolver.resolve(ToolKind.LLDB) != null)
        assertNull(resolver.resolve(ToolKind.LLDB_DAP))
    }

    @Test
    fun resolvesLldbFromNdkToolchain() {
        val sdk = tempDir()
        val bin = sdk.resolve("ndk/29.0.13113456/toolchains/llvm/prebuilt/windows-x86_64/bin").createDirectories()
        val lldb = bin.resolve("lldb.exe")
        Files.createFile(lldb)
        val legacy = sdk.resolve("lldb/bin/lldb.exe")
        Files.createDirectories(legacy.parent)
        Files.createFile(legacy)
        val resolver = resolver(env = mapOf("ANDROID_HOME" to sdk.toString()), hostOsValue = "Windows")
        assertEquals(lldb, resolver.resolve(ToolKind.LLDB)?.executable)
    }

    @Test
    fun prefersNewestNdkLldbServer() {
        val sdk = tempDir()
        val hostTag = if (hostOs == "Windows") "windows-x86_64" else "linux-x86_64"
        val old = sdk.resolve("ndk/21.4.7075529/toolchains/llvm/prebuilt/$hostTag/lib/clang/9/lib/linux/aarch64/lldb-server")
        val newest = sdk.resolve("ndk/29.0.13113456/toolchains/llvm/prebuilt/$hostTag/lib/clang/20/lib/linux/aarch64/lldb-server")
        Files.createDirectories(old.parent)
        Files.createDirectories(newest.parent)
        Files.createFile(old)
        Files.createFile(newest)
        val resolver = resolver(env = mapOf("ANDROID_HOME" to sdk.toString()))
        assertEquals(newest, resolver.resolve(ToolKind.LLDB_SERVER, targetAbi = "arm64-v8a")?.executable)
    }

    @Test
    fun requiresAbiMatchForLldbServer() {
        val ndk = tempDir()
        val abiDir = ndk.resolve("toolchains/llvm/prebuilt/windows-x86_64/lib/clang/17/lib/linux/arm64").createDirectories()
        Files.createFile(abiDir.resolve("lldb-server"))
        val x64Dir = ndk.resolve("toolchains/llvm/prebuilt/windows-x86_64/lib/clang/17/lib/linux/x86_64").createDirectories()
        Files.createFile(x64Dir.resolve("lldb-server"))
        val resolver = resolver(env = mapOf("ANDROID_NDK_HOME" to ndk.toString()))
        assertNull(resolver.resolve(ToolKind.LLDB_SERVER, targetAbi = "arm64-v8a"))
        assertTrue(resolver.resolve(ToolKind.LLDB_SERVER, targetAbi = "x86_64") != null)
    }

    @Test
    fun reportsMissingToolAsUnavailable() = runBlocking {
        val manager = DefaultToolchainManager(resolver(pathDirs = emptyList()), inspector)
        val report = manager.resolve(ToolchainRequest(null, setOf(ToolKind.LLDB), "arm64-v8a"))
        assertEquals(0, report.tools.size)
        assertEquals(1, report.errors.size)
        assertEquals(Availability.UNAVAILABLE, report.capabilities.single().availability)
    }

    @Test
    fun doesNotInstallOrUpdateToolsAutomatically() = runBlocking {
        val dir = tempDir().createDirectories()
        val manager = DefaultToolchainManager(resolver(pathDirs = listOf(dir)), inspector)
        val before = Files.list(dir).count()
        manager.resolve(ToolchainRequest(null, setOf(ToolKind.ADB), null))
        val after = Files.list(dir).count()
        assertEquals(before, after)
        assertNull(resolver(pathDirs = listOf(dir)).resolve(ToolKind.ADB))
    }

    private fun resolver(
        root: Path? = null,
        pathDirs: List<Path> = emptyList(),
        env: Map<String, String> = emptyMap(),
        hostOsValue: String = hostOs,
    ): ToolResolver = ToolResolver(
        ResolverEnvironment(root, env, pathDirs, hostOsValue, "x86_64"),
        inspector,
    )

    // ToolResolver requires the executable bit on POSIX hosts.
    private fun makeExecutable(path: Path): Path {
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        return path
    }

    private fun tempDir(): Path = Files.createTempDirectory("admcp-toolchain-")
}

private class FakeInspector : ExecutableInspector {
    override fun version(path: Path): String? = if (Files.exists(path)) "1.2.3" else null
    override fun sha256(path: Path): String? = "00"
}
