package dev.admcp.ui

import dev.admcp.core.model.DeviceId
import dev.admcp.core.model.UiAction
import dev.admcp.core.model.UiActionDecision
import dev.admcp.core.model.UiActionKind
import dev.admcp.core.model.UiActionRisk
import dev.admcp.core.model.UiSelector
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UiHierarchyTest {
    private val device = DeviceId("d1")
    private val xml = """
        <?xml version='1.0' encoding='UTF-8'?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="app" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
            <node index="0" text="Delete" resource-id="app:id/delete" class="android.widget.Button" package="app" clickable="true" enabled="true" bounds="[100,200][300,260]" />
            <node index="1" text="" resource-id="app:id/search" class="android.widget.EditText" package="app" clickable="true" enabled="true" focused="true" bounds="[100,300][900,360]" />
            <node index="2" text="Settings" content-desc="Open settings" resource-id="app:id/settings" class="android.widget.Button" package="app" clickable="true" enabled="true" bounds="[100,400][900,460]" />
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parsesNestedHierarchyAndBounds() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        assertEquals(4, snapshot.elements.size)
        assertEquals(100, snapshot.elements.single { it.id == "app:id/delete" }.bounds?.left)
        assertEquals(1080, snapshot.elements.first().bounds?.right)
        assertEquals(3, snapshot.generation)
    }

    @Test
    fun resolvesByResourceIdTextAndContentDescription() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val resolver = UiSelectorResolver()
        assertEquals("app:id/delete", resolver.resolve(snapshot, UiSelector("app:id/delete", null, null, null, null, null, true)).id)
        assertEquals("app:id/delete", resolver.resolve(snapshot, UiSelector(null, "Delete", null, null, null, null, true)).id)
        assertEquals("app:id/settings", resolver.resolve(snapshot, UiSelector(null, null, "Open settings", null, null, null, true)).id)
    }

    @Test
    fun rejectsAmbiguousOrStaleSelectors() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val ambiguous = UiSelector(text = "e", exact = false)
        assertTrue(UiSelectorResolver().resolveAll(snapshot, ambiguous).size > 1)
        assertFailsWith<IllegalArgumentException> { UiSelectorResolver().resolve(snapshot, ambiguous) }
        assertFailsWith<IllegalArgumentException> { UiStaleness.requireCurrent(snapshot, 4) }
    }

    @Test
    fun detectsClickability() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val resolver = UiSelectorResolver()
        assertTrue(resolver.resolve(snapshot, UiSelector(resourceId = "app:id/delete")).clickable == true)
        assertTrue(resolver.resolve(snapshot, UiSelector(resourceId = "app:id/search")).focused == true)
    }

    @Test
    fun tapUsesResolvedBoundsCenter() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val plan = UiActionPlanner().plan(UiAction(UiActionKind.TAP, UiSelector(resourceId = "app:id/delete"), null, null, null, null), snapshot)
        assertEquals(200, plan.x)
        assertEquals(230, plan.y)
        assertEquals(UiActionDecision.REQUIRE_CONFIRMATION, plan.assessment.decision)
    }

    @Test
    fun classifiesDestructiveAndReversibleActions() {
        val planner = UiActionPlanner()
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val destructive = planner.plan(UiAction(UiActionKind.TAP, UiSelector(text = "Delete"), null, null, null, null), snapshot)
        assertEquals(UiActionRisk.DESTRUCTIVE, destructive.assessment.risk)
        val reversible = planner.plan(UiAction(UiActionKind.TAP, UiSelector(text = "Settings"), null, null, null, null), snapshot)
        assertEquals(UiActionRisk.REVERSIBLE, reversible.assessment.risk)
        assertEquals(UiActionDecision.ALLOW, reversible.assessment.decision)
    }

    @Test
    fun rejectsCoordinateOnlyActionWithoutSelector() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        assertFailsWith<IllegalArgumentException> {
            UiActionPlanner().plan(UiAction(UiActionKind.TAP, null, null, null, null, null), snapshot)
        }
    }

    @Test
    fun scrollUntilVisibleIsBounded() {
        val snapshot = UiHierarchyParser().parse(device, xml.toByteArray(), generation = 3)
        val plan = UiActionPlanner().plan(
            UiAction(UiActionKind.SCROLL, UiSelector(resourceId = "app:id/missing"), null, dev.admcp.core.model.ScrollDirection.DOWN, null, null),
            snapshot,
        )
        assertEquals(10, plan.maxAttempts)
    }
}
