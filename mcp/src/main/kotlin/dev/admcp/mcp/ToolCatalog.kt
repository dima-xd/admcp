package dev.admcp.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CatalogTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val capability: String,
    val sideEffect: String,
    val confirmation: String,
    val optional: Boolean,
    val releaseGate: String,
) {
    val required: List<String> get() = inputSchema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    val properties: JsonObject get() = inputSchema["properties"]?.jsonObject ?: JsonObject(emptyMap())
}

class ToolCatalog(private val tools: List<CatalogTool>, val version: Int) {
    private val byName = tools.associateBy { it.name }
    val all: List<CatalogTool> = tools
    fun find(name: String): CatalogTool? = byName[name]
    fun require(name: String): CatalogTool = find(name) ?: throw IllegalArgumentException("unknown tool: $name")
}

object CatalogLoader {
    fun load(resource: String = "/mcp-tool-catalog-v1.json"): ToolCatalog {
        val text = CatalogLoader::class.java.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
            ?: error("catalog resource not found: $resource")
        val root = Json.parseToJsonElement(text).jsonObject
        val version = root["catalogVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
        val tools = root["tools"]!!.jsonArray.map { element ->
            val tool = element.jsonObject
            CatalogTool(
                name = tool["name"]!!.jsonPrimitive.content,
                description = tool["description"]!!.jsonPrimitive.content,
                inputSchema = tool["inputSchema"]!!.jsonObject,
                capability = tool["capability"]?.jsonPrimitive?.content ?: "",
                sideEffect = tool["sideEffect"]?.jsonPrimitive?.content ?: "UNKNOWN",
                confirmation = tool["confirmation"]?.jsonPrimitive?.content ?: "NONE",
                optional = tool["optional"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                releaseGate = tool["releaseGate"]?.jsonPrimitive?.content ?: "",
            )
        }
        require(tools.map { it.name }.distinct().size == tools.size) { "tool catalog contains duplicate names" }
        tools.forEach { tool ->
            require(tool.inputSchema["type"]?.jsonPrimitive?.content == "object") { "tool ${tool.name} inputSchema must be an object" }
            require(tool.required.all { it in tool.properties.keys }) { "tool ${tool.name} required fields must exist in properties" }
        }
        return ToolCatalog(tools, version)
    }
}
