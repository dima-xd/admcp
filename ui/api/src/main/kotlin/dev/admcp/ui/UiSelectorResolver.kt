package dev.admcp.ui

import dev.admcp.core.model.UiElement
import dev.admcp.core.model.UiSelector
import dev.admcp.core.model.UiSnapshot

object UiStaleness {
    fun requireCurrent(snapshot: UiSnapshot, expectedGeneration: Long) {
        require(snapshot.generation == expectedGeneration) {
            "stale UI snapshot: expected generation $expectedGeneration but was ${snapshot.generation}"
        }
    }
}

class UiSelectorResolver {
    fun resolveAll(snapshot: UiSnapshot, selector: UiSelector): List<UiElement> {
        require(
            listOf(selector.resourceId, selector.text, selector.contentDescription, selector.className).any { !it.isNullOrBlank() } ||
                selector.clickable != null || selector.enabled != null,
        ) { "selector must constrain at least one attribute" }
        return snapshot.elements.filter { element -> matches(element, selector) }
    }

    fun resolve(snapshot: UiSnapshot, selector: UiSelector): UiElement {
        val matches = resolveAll(snapshot, selector)
        require(matches.isNotEmpty()) { "selector matched no elements" }
        require(matches.size == 1) { "selector is ambiguous: ${matches.size} elements matched" }
        return matches.single()
    }

    private fun matches(element: UiElement, selector: UiSelector): Boolean =
        match(selector.resourceId, element.id, selector.exact) &&
            match(selector.text, element.text, selector.exact) &&
            match(selector.contentDescription, element.contentDescription, selector.exact) &&
            match(selector.className, element.className, selector.exact) &&
            (selector.clickable == null || selector.clickable == element.clickable) &&
            (selector.enabled == null || selector.enabled == element.enabled)

    private fun match(expected: String?, actual: String?, exact: Boolean): Boolean {
        if (expected == null) return true
        if (actual == null) return false
        return if (exact) actual == expected else actual.contains(expected, ignoreCase = true)
    }
}

data class PlannedAction(
    val action: dev.admcp.core.model.UiAction,
    val matched: List<UiElement>,
    val x: Int?,
    val y: Int?,
    val assessment: dev.admcp.core.model.UiActionAssessment,
    val maxAttempts: Int = 1,
    val confirmed: Boolean = false,
)

class UiActionPlanner(private val resolver: UiSelectorResolver = UiSelectorResolver()) {
    fun plan(action: dev.admcp.core.model.UiAction, snapshot: dev.admcp.core.model.UiSnapshot): PlannedAction {
        val selector = action.selector ?: throw IllegalArgumentException("a selector is required; coordinate-only actions are not accepted")
        val matches = resolver.resolveAll(snapshot, selector)
        if (matches.isEmpty()) {
            if (action.kind == dev.admcp.core.model.UiActionKind.SCROLL) {
                return PlannedAction(action, emptyList(), null, null, assess(action, null), maxAttempts = 10)
            }
            throw IllegalArgumentException("selector matched no elements")
        }
        require(matches.size == 1) { "selector is ambiguous: ${matches.size} elements matched" }
        val element = matches.single()
        val bounds = requireNotNull(element.bounds) { "matched element has no bounds" }
        val assessment = assess(action, element)
        return PlannedAction(
            action = action,
            matched = matches,
            x = (bounds.left + bounds.right) / 2,
            y = (bounds.top + bounds.bottom) / 2,
            assessment = assessment,
            maxAttempts = if (action.kind == dev.admcp.core.model.UiActionKind.SCROLL) 10 else 1,
        )
    }

    private fun assess(action: dev.admcp.core.model.UiAction, element: UiElement?): dev.admcp.core.model.UiActionAssessment {
        val text = listOfNotNull(element?.text, element?.contentDescription, element?.id).joinToString(" ").lowercase()
        val destructive = listOf("delete", "uninstall", "clear data", "wipe", "remove", "reset", "factory").any { text.contains(it) }
        return when {
            destructive -> dev.admcp.core.model.UiActionAssessment(
                dev.admcp.core.model.UiActionRisk.DESTRUCTIVE,
                dev.admcp.core.model.UiActionDecision.REQUIRE_CONFIRMATION,
                listOf("target label indicates a destructive action"),
            )
            action.kind == dev.admcp.core.model.UiActionKind.SCROLL -> dev.admcp.core.model.UiActionAssessment(
                dev.admcp.core.model.UiActionRisk.REVERSIBLE,
                dev.admcp.core.model.UiActionDecision.ALLOW,
                listOf("scroll is reversible"),
            )
            else -> dev.admcp.core.model.UiActionAssessment(
                dev.admcp.core.model.UiActionRisk.REVERSIBLE,
                dev.admcp.core.model.UiActionDecision.ALLOW,
                listOf("action targets a resolved UI element"),
            )
        }
    }
}
