package dev.admcp.integration

import dev.admcp.mcp.AdmcpToolHandler
import dev.admcp.mcp.CatalogLoader
import dev.admcp.mcp.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealDeviceIntegrationTest {
    private fun platformTools(): Path? {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME")?.let { Path.of(it, "platform-tools") },
            System.getenv("ANDROID_SDK_ROOT")?.let { Path.of(it, "platform-tools") },
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Android", "Sdk", "platform-tools") },
            System.getProperty("user.home")?.let { Path.of(it, "Android", "Sdk", "platform-tools") },
        )
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("adb.exe")) || Files.isRegularFile(it.resolve("adb")) }
    }

    private fun handler(): AdmcpToolHandler {
        val tools = platformTools()
        assumeTrue(tools != null, "Android SDK platform-tools is not available")
        return AdmcpToolHandler(
            ServerConfig(
                platformTools = tools,
                artifactRoot = Files.createTempDirectory("admcp-integration-artifacts-"),
            ),
        )
    }

    @Test
    fun deviceListReturnsTheConnectedDeviceAndSessionRoundTrips() = runBlocking {
        val handler = handler()
        val list = handler.call("device_list", buildJsonObject { put("includeOffline", false) })
        assertFalse(list.isError)
        val data = list.structured["data"]!!.jsonObject
        val devices = data["devices"]!!.jsonArray
        assumeTrue(devices.isNotEmpty(), "no online Android device is connected")
        val deviceId = devices.first().jsonObject["deviceId"]!!.jsonPrimitive.content

        val start = handler.call("session_start", buildJsonObject {
            put("deviceId", deviceId)
            put("packageName", "com.android.settings")
        })
        assertFalse(start.isError)
        val sessionId = start.structured["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content

        val status = handler.call("session_status", buildJsonObject { put("sessionId", sessionId) })
        assertFalse(status.isError)
        assertEquals(deviceId, status.structured["data"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)

        val stop = handler.call("session_stop", buildJsonObject { put("sessionId", sessionId) })
        assertFalse(stop.isError)
    }

    @Test
    fun workspaceOpenDetectsThisRepository() = runBlocking {
        val handler = handler()
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val result = handler.call("workspace_open", buildJsonObject { put("path", root.toString()) })
        assertFalse(result.isError)
        val data = result.structured["data"]!!.jsonObject
        assertEquals("ANDROID_GRADLE", data["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun uiInspectReturnsHierarchyWhenDeviceSupportsIt() = runBlocking {
        val handler = handler()
        val deviceId = firstOnlineDevice(handler)
        val start = handler.call("session_start", buildJsonObject { put("deviceId", deviceId) })
        assertFalse(start.isError)
        val sessionId = start.structured["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        try {
            val inspected = handler.call("ui_inspect", buildJsonObject { put("sessionId", sessionId) })
            assumeTrue(!inspected.isError, "uiautomator hierarchy is unavailable: ${inspected.structured}")
            val elements = inspected.structured["data"]!!.jsonObject["elements"]!!.jsonArray
            assertTrue(elements.size > 0, "a screen hierarchy should contain at least the root node")
        } finally {
            handler.call("session_stop", buildJsonObject { put("sessionId", sessionId) })
        }
    }

    @Test
    fun screenshotArtifactRoundTripsThroughMcp() = runBlocking {
        val handler = handler()
        val deviceId = firstOnlineDevice(handler)
        val start = handler.call("session_start", buildJsonObject { put("deviceId", deviceId) })
        assertFalse(start.isError)
        val sessionId = start.structured["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        try {
            val screenshot = handler.call("ui_screenshot", buildJsonObject { put("sessionId", sessionId) })
            assumeTrue(!screenshot.isError, "screencap is unavailable")
            val artifactId = screenshot.structured["data"]!!.jsonObject["artifactId"]!!.jsonPrimitive.content
            val artifact = handler.call("artifact_get", buildJsonObject { put("artifactId", artifactId) })
            assertFalse(artifact.isError)
            assertTrue(artifact.structured["data"]!!.jsonObject["content"]!!.jsonPrimitive.content.isNotBlank())
        } finally {
            handler.call("session_stop", buildJsonObject { put("sessionId", sessionId) })
        }
    }

    private fun firstOnlineDevice(handler: AdmcpToolHandler): String = runBlocking {
        val list = handler.call("device_list", buildJsonObject { put("includeOffline", false) })
        val devices = list.structured["data"]!!.jsonObject["devices"]!!.jsonArray
        assumeTrue(devices.isNotEmpty(), "no online Android device is connected")
        devices.first().jsonObject["deviceId"]!!.jsonPrimitive.content
    }

    @Test
    fun logcatQueryReturnsParsedRecords() = runBlocking {
        val handler = handler()
        val list = handler.call("device_list", buildJsonObject { put("includeOffline", false) })
        val devices = list.structured["data"]!!.jsonObject["devices"]!!.jsonArray
        assumeTrue(devices.isNotEmpty(), "no online Android device is connected")
        val deviceId = devices.first().jsonObject["deviceId"]!!.jsonPrimitive.content
        val result = handler.call("logcat_query", buildJsonObject {
            put("deviceId", deviceId)
            put("limit", 5)
        })
        assumeTrue(!result.isError, "logcat is unavailable: ${result.structured}")
        val records = result.structured["data"]!!.jsonObject["records"]!!.jsonArray
        assertTrue(records.size <= 5)
    }

    @Test
    fun catalogSessionScopedLogcatQueryWorksWithoutDeviceId() = runBlocking {
        val handler = handler()
        val deviceId = firstOnlineDevice(handler)
        val start = handler.call("session_start", buildJsonObject { put("deviceId", deviceId) })
        assertFalse(start.isError)
        val sessionId = start.structured["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        try {
            val result = handler.call("logcat_query", buildJsonObject {
                put("sessionId", sessionId)
                put("limit", 3)
            })
            assumeTrue(!result.isError, "logcat is unavailable: ${result.structured}")
            val records = result.structured["data"]!!.jsonObject["records"]!!.jsonArray
            assertTrue(records.size <= 3)
        } finally {
            handler.call("session_stop", buildJsonObject { put("sessionId", sessionId) })
        }
    }

    @Test
    fun workspaceTrustRequiresStartupOperatorApproval() = runBlocking {
        val tools = platformTools()
        assumeTrue(tools != null, "Android SDK platform-tools is not available")
        val root = Files.createTempDirectory("admcp-trust-")
        val approved = AdmcpToolHandler(
            ServerConfig(
                platformTools = tools,
                artifactRoot = Files.createTempDirectory("admcp-trust-artifacts-"),
                trustedWorkspaceRoots = setOf(root),
            ),
        )
        val granted = approved.call("workspace_trust", buildJsonObject { put("path", root.toString()) })
        assertFalse(granted.isError)
        val revoked = approved.call("workspace_revoke_trust", buildJsonObject { put("path", root.toString()) })
        assertFalse(revoked.isError)

        val unapproved = handler()
        val rejected = unapproved.call("workspace_trust", buildJsonObject { put("path", root.toString()) })
        assertTrue(rejected.isError)
    }

    @Test
    fun toolchainProbeReportsResolvedToolsAndErrors() = runBlocking {
        val handler = handler()
        val result = handler.call("toolchain_probe", buildJsonObject {})
        assertFalse(result.isError)
        assertTrue(result.structured["data"]!!.jsonObject.containsKey("tools"))
    }

    @Test
    fun catalogLoadsV1Contract() {
        val catalog = CatalogLoader.load()
        assertEquals(51, catalog.all.size)
        assertTrue(catalog.all.none { it.name.isBlank() })
    }
}
