package dev.admcp.app

import dev.admcp.app.CatalogFixture.catalog
import dev.admcp.app.CatalogFixture.resolvePointer
import dev.admcp.app.CatalogFixture.string
import dev.admcp.app.CatalogFixture.tools
import dev.admcp.app.CatalogFixture.walk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCatalogContractTest {

    @Test
    fun catalogHasExpectedMetadata() {
        assertEquals("1", catalog.string("catalogVersion"))
        assertEquals("3.2", catalog.string("designRevision"))
        assertEquals("3.2.1", catalog.string("implementationPlanRevision"))
        assertEquals("STABLE", catalog.string("status"))
    }

    @Test
    fun catalogContainsExactly51Tools() {
        assertEquals(51, tools.size)
    }

    @Test
    fun catalogContainsNoDuplicateToolNames() {
        val names = tools.map { it.string("name") }
        assertEquals(names.size, names.toSet().size, "duplicate tool names: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}")
    }

    @Test
    fun everyToolNameIsAPublicSnakeCaseIdentifier() {
        val pattern = Regex("""^[a-z][a-z0-9_]*$""")
        tools.forEach { tool ->
            val name = tool.string("name")
            assertTrue(pattern.matches(name), "invalid public tool name: '$name'")
        }
    }

    @Test
    fun allToolsHaveAValidReleaseGate() {
        val allowed = setOf("A", "B", "C")
        tools.forEach { tool ->
            val gate = tool.string("releaseGate")
            assertTrue(gate in allowed, "tool '${tool.string("name")}' has invalid releaseGate '$gate'")
        }
        val gates = tools.groupingBy { it.string("releaseGate") }.eachCount()
        assertEquals(setOf("A", "B", "C"), gates.keys)
        assertEquals(51, gates.values.sum())
    }

    @Test
    fun everyToolHasASchemaAndClassification() {
        val sideEffectClasses = catalog["sideEffectClasses"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        tools.forEach { tool ->
            val name = tool.string("name")
            assertEquals("object", tool["inputSchema"]!!.jsonObject.string("type"), "$name inputSchema must be an object")
            assertTrue(tool["outputSchema"] != null, "$name must declare outputSchema")
            assertTrue(tool.string("capability").isNotBlank(), "$name must declare a capability")
            assertTrue(tool.string("sideEffect") in sideEffectClasses, "$name has an unknown sideEffect")
            assertTrue(tool.string("confirmation").isNotBlank(), "$name must declare a confirmation rule")
            assertTrue(tool["optional"] != null, "$name must declare optional")
        }
    }

    @Test
    fun resourceCatalogIsPresent() {
        assertTrue(catalog["resources"]!!.jsonArray.isNotEmpty(), "catalog must declare resources")
        assertTrue(catalog["\$defs"] != null, "catalog must declare shared schema definitions")
    }

    @Test
    fun packagedCatalogMatchesDocumentedCatalog() {
        val text = javaClass.getResourceAsStream("/mcp-tool-catalog-v1.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("packaged catalog resource is missing")
        assertEquals(catalog, Json.parseToJsonElement(text).jsonObject)
    }

    @Test
    fun everyRequiredInputIsDeclaredInProperties() {
        tools.forEach { tool ->
            val name = tool.string("name")
            val schema = tool["inputSchema"]!!.jsonObject
            val properties: JsonObject = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val requiredGroups = buildList {
                schema["required"]?.let { add(it.jsonArray) }
                schema["oneOf"]?.jsonArray?.forEach { branch ->
                    branch.jsonObject["required"]?.let { add(it.jsonArray) }
                }
            }
            requiredGroups.flatMap { group -> group.map { it.jsonPrimitive.content } }.forEach { key ->
                assertTrue(key in properties, "$name requires '$key' but does not declare it in inputSchema.properties")
            }
        }
    }

    @Test
    fun everyToolInputRejectsUnknownProperties() {
        tools.forEach { tool ->
            val name = tool.string("name")
            val schema = tool["inputSchema"]!!.jsonObject
            assertEquals(
                "false",
                schema["additionalProperties"]?.jsonPrimitive?.content,
                "$name inputSchema must declare additionalProperties=false",
            )
            assertEquals("object", schema.string("type"), "$name inputSchema must be an object")
        }
    }

    @Test
    fun allInternalRefsResolve() {
        var seen = 0
        catalog.walk { node ->
            val ref = node["\$ref"]?.jsonPrimitive?.content ?: return@walk
            if (ref.startsWith("#")) {
                seen++
                assertNotNull(resolvePointer(ref), "unresolved internal \$ref '$ref'")
            }
        }
        assertTrue(seen > 0, "catalog must contain internal \$ref entries")
    }

    @Test
    fun everyOutputSchemaRefResolves() {
        tools.forEach { tool ->
            val name = tool.string("name")
            val outputSchema = tool["outputSchema"]!!.jsonObject
            val ref = outputSchema["\$ref"]?.jsonPrimitive?.content
            if (ref != null) {
                assertNotNull(resolvePointer(ref), "$name outputSchema has unresolved \$ref '$ref'")
            } else {
                assertEquals("object", outputSchema.string("type"), "$name outputSchema must be a ref or an object schema")
            }
        }
    }
}
