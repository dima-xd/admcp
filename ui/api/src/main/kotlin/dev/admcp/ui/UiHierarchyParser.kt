package dev.admcp.ui

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.UiBounds
import dev.admcp.core.model.UiElement
import dev.admcp.core.model.UiSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class UiHierarchyParser {
    fun parse(deviceId: DeviceId, xml: ByteArray, generation: Long): UiSnapshot {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val elements = mutableListOf<UiElement>()
        val root = document.documentElement ?: error("empty hierarchy")
        walk(root, elements)
        return UiSnapshot(deviceId, Instant.now(), generation, elements, buildJsonObject { put("rotation", root.getAttribute("rotation")) })
    }

    private fun walk(node: org.w3c.dom.Node, output: MutableList<UiElement>) {
        if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE && node.nodeName == "node") {
            val element = node.attributes
            val bounds = parseBounds(element.getNamedItem("bounds")?.nodeValue)
            val raw = buildJsonObject {
                for (index in 0 until element.length) {
                    val attribute = element.item(index)
                    put(attribute.nodeName, attribute.nodeValue)
                }
            }
            output += UiElement(
                id = element.getNamedItem("resource-id")?.nodeValue?.ifBlank { null },
                text = element.getNamedItem("text")?.nodeValue?.ifBlank { null },
                contentDescription = element.getNamedItem("content-desc")?.nodeValue?.ifBlank { null },
                className = element.getNamedItem("class")?.nodeValue,
                bounds = bounds,
                clickable = element.getNamedItem("clickable")?.nodeValue?.toBooleanStrictOrNull(),
                enabled = element.getNamedItem("enabled")?.nodeValue?.toBooleanStrictOrNull(),
                focused = element.getNamedItem("focused")?.nodeValue?.toBooleanStrictOrNull(),
                selected = element.getNamedItem("selected")?.nodeValue?.toBooleanStrictOrNull(),
                checked = element.getNamedItem("checked")?.nodeValue?.toBooleanStrictOrNull(),
                scrollable = element.getNamedItem("scrollable")?.nodeValue?.toBooleanStrictOrNull(),
                children = emptyList(),
                raw = raw,
            )
        }
        val children = node.childNodes
        for (index in 0 until children.length) walk(children.item(index), output)
    }

    private fun parseBounds(value: String?): UiBounds? {
        if (value == null) return null
        val match = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""").find(value) ?: return null
        val numbers = match.groupValues.drop(1).map(String::toInt)
        return UiBounds(numbers[0], numbers[1], numbers[2], numbers[3])
    }
}
