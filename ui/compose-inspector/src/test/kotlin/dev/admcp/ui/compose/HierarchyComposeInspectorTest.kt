package dev.admcp.ui.compose

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.UiElement
import dev.admcp.core.model.UiSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HierarchyComposeInspectorTest {
    @Test
    fun detectsComposeMarkerAndExtractsTextOrDescription() = runBlocking {
        val snapshot = snapshot(
            element("androidx.compose.ui.platform.ComposeView", "COMPOSE COUNTER", null),
            element("android.widget.Button", null, "COMPOSE INCREMENT"),
        )
        val inspection = HierarchyComposeInspector().detect(snapshot)
        assertTrue(inspection.supported)
        assertEquals(listOf("COMPOSE COUNTER", "COMPOSE INCREMENT"), inspection.semantics)
    }

    @Test
    fun reportsUnsupportedWithoutComposeMarker() = runBlocking {
        val snapshot = snapshot(element("android.widget.TextView", "plain", null))
        val inspection = HierarchyComposeInspector().detect(snapshot)
        assertFalse(inspection.supported)
        assertEquals("no Compose hierarchy marker was observed", inspection.reason)
    }

    private fun snapshot(vararg elements: UiElement) = UiSnapshot(
        DeviceId("d1"),
        Instant.EPOCH,
        1,
        elements.toList(),
        buildJsonObject {},
    )

    private fun element(className: String, text: String?, contentDescription: String?) = UiElement(
        id = null,
        text = text,
        contentDescription = contentDescription,
        className = className,
        bounds = null,
        clickable = null,
        enabled = null,
        focused = null,
        selected = null,
        checked = null,
        scrollable = null,
        children = emptyList(),
        raw = buildJsonObject {},
    )
}
