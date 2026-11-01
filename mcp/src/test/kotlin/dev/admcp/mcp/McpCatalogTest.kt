package dev.admcp.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpCatalogTest {
    @Test
    fun catalogLoadsAllV1Tools() {
        val catalog = CatalogLoader.load()
        assertEquals(51, catalog.all.size)
        assertEquals(51, catalog.all.map { it.name }.distinct().size)
        assertTrue(catalog.all.all { it.inputSchema["type"]?.jsonPrimitive?.content == "object" })
        assertTrue(catalog.all.any { it.name == "compose_inspect" })
    }

    @Test
    fun buildsServerWithEveryCatalogTool() {
        val catalog = CatalogLoader.load()
        val server = AdmcpMcpServer(catalog, MapToolDispatcher(emptyMap())).build()
        assertEquals(51, server.tools.size)
    }

    @Test
    fun everyCatalogToolHasAHandler() {
        val catalog = CatalogLoader.load()
        val handler = AdmcpToolHandler(ServerConfig())
        assertEquals(catalog.all.map { it.name }.toSet(), handler.handledToolNames())
    }

    @Test
    fun unknownAndUnimplementedToolsReturnStructuredErrors() = kotlinx.coroutines.runBlocking {
        val dispatcher = MapToolDispatcher(emptyMap())
        val unknown = dispatcher.call("not_a_tool", JsonObject(emptyMap()))
        assertTrue(unknown.isError)
        val unimplemented = dispatcher.call("device_list", JsonObject(emptyMap()))
        assertTrue(unimplemented.isError)
        assertEquals("TOOL_NOT_IMPLEMENTED", unimplemented.structured["error"]?.let { (it as JsonObject)["code"]?.jsonPrimitive?.content })
    }

    @Test
    fun catalogValidationRejectsDuplicateOrBadSchemas() {
        val catalog = CatalogLoader.load()
        assertFailsWith<IllegalArgumentException> { catalog.require("missing") }
        val tool = catalog.all.first()
        assertEquals(tool, catalog.require(tool.name))
    }

    @Test
    fun dispatcherWrapsHandlerExceptions() = kotlinx.coroutines.runBlocking {
        val dispatcher = MapToolDispatcher(mapOf("x" to { _: JsonObject -> error("boom") }))
        val result = dispatcher.call("x", JsonObject(emptyMap()))
        assertTrue(result.isError)
        assertEquals("IllegalStateException", (result.structured["error"] as JsonObject)["code"]?.jsonPrimitive?.content)
    }
}
