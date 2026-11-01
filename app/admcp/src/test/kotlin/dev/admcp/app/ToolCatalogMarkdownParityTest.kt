package dev.admcp.app

import dev.admcp.app.CatalogFixture.markdown
import dev.admcp.app.CatalogFixture.string
import dev.admcp.app.CatalogFixture.tools
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCatalogMarkdownParityTest {

    private data class MarkdownToolRow(val name: String, val requiredCell: String)

    @Test
    fun markdownTablesMatchJsonToolNames() {
        val rows = toolRows()
        val markdownNames = rows.map { it.name }
        val jsonNames = tools.map { it.string("name") }

        assertEquals(51, rows.size, "expected 51 tool rows in the Markdown catalog")
        assertEquals(
            markdownNames.size,
            markdownNames.toSet().size,
            "duplicate tool rows: ${markdownNames.groupBy { it }.filter { it.value.size > 1 }.keys}",
        )
        assertEquals(jsonNames.toSet(), markdownNames.toSet(), "Markdown and JSON tool names differ")
    }

    @Test
    fun markdownRequiredInputsMatchJsonRequiredFields() {
        val toolsByName = tools.associateBy { it.string("name") }
        toolRows().forEach { row ->
            val tool = toolsByName[row.name] ?: error("Markdown lists '${row.name}' but JSON does not")
            assertEquals(
                requiredTokens(row.requiredCell),
                jsonRequiredFields(tool),
                "required input mismatch for '${row.name}'",
            )
        }
    }

    @Test
    fun markdownGateListsMatchJsonReleaseGates() {
        val markdownGates = markdownGates()
        val jsonGates = tools.associate { it.string("name") to it.string("releaseGate") }

        assertEquals(jsonGates.keys, markdownGates.keys, "Markdown and JSON gate coverage differs")
        jsonGates.forEach { (name, gate) ->
            assertEquals(gate, markdownGates[name], "release gate mismatch for '$name'")
        }
    }

    private fun toolRows(): List<MarkdownToolRow> {
        val section = markdown.substringAfter("## 2. Public tools").substringBefore("## 3. MCP resources")
        return section.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("| `") }
            .mapNotNull { line ->
                val cells = line.trim('|').split('|').map { it.trim() }
                if (cells.size < 2) return@mapNotNull null
                val name = Regex("""^`([a-z][a-z0-9_]*)`$""").find(cells[0])?.groupValues?.get(1)
                    ?: return@mapNotNull null
                MarkdownToolRow(name, cells[1])
            }
            .toList()
    }

    private fun requiredTokens(cell: String): Set<String> {
        if (cell.equals("none", ignoreCase = true)) return emptySet()
        return Regex("`([^`]+)`").findAll(cell)
            .map { it.groupValues[1].trim().removeSuffix("[]") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun jsonRequiredFields(tool: JsonObject): Set<String> {
        val schema = tool["inputSchema"]!!.jsonObject
        val direct = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (direct.isNotEmpty()) return direct.toSet()
        return schema["oneOf"]?.jsonArray
            ?.flatMap { branch -> branch.jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty() }
            ?.toSet()
            .orEmpty()
    }

    private fun markdownGates(): Map<String, String> {
        val section = markdown.substringAfter("## 6. Milestone exposure")
        val gates = linkedMapOf<String, String>()
        section.lineSequence().forEach { line ->
            val label = Regex("""^-\s+\*\*([^*]+)\*\*""").find(line.trim())?.groupValues?.get(1) ?: return@forEach
            val gate = label.trim().firstOrNull()?.toString() ?: return@forEach
            if (gate !in setOf("A", "B", "C")) return@forEach
            val names = line.substringAfter("plus ", line)
            Regex("`([a-z][a-z0-9_]*)`").findAll(names).forEach { match ->
                val name = match.groupValues[1]
                val previous = gates.put(name, gate)
                assertTrue(previous == null || previous == gate, "'$name' is listed under multiple gates")
            }
        }
        return gates
    }
}
