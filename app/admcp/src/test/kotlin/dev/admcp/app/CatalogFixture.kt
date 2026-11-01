package dev.admcp.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal object CatalogFixture {

    private const val JSON_PATH = "docs/architecture/mcp-tool-catalog-v1.json"
    private const val MARKDOWN_PATH = "docs/architecture/mcp-tool-catalog-v1.md"

    val repoRoot: Path by lazy {
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve(JSON_PATH))) return@lazy dir
            dir = dir.parent
        }
        error("$JSON_PATH not found above ${System.getProperty("user.dir")}")
    }

    val catalog: JsonObject by lazy {
        Json.parseToJsonElement(Files.readString(repoRoot.resolve(JSON_PATH))).jsonObject
    }

    val markdown: String by lazy {
        Files.readString(repoRoot.resolve(MARKDOWN_PATH))
    }

    val tools: List<JsonObject> by lazy {
        catalog["tools"]!!.jsonArray.map { it.jsonObject }
    }

    fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("catalog is missing '$key'")

    fun resolvePointer(ref: String): JsonElement? {
        if (!ref.startsWith("#/")) return null
        var current: JsonElement = catalog
        for (rawToken in ref.removePrefix("#/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> token.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    fun JsonElement.walk(visit: (JsonObject) -> Unit) {
        when (this) {
            is JsonObject -> {
                visit(this)
                values.forEach { it.walk(visit) }
            }
            is JsonArray -> forEach { it.walk(visit) }
            else -> Unit
        }
    }
}
