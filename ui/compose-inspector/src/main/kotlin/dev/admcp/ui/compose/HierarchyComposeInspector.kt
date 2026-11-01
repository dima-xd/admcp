package dev.admcp.ui.compose

import dev.admcp.core.model.UiSnapshot

data class ComposeInspection(
    val supported: Boolean,
    val reason: String?,
    val semantics: List<String>,
)

interface ComposeInspector {
    suspend fun detect(snapshot: UiSnapshot): ComposeInspection
}

class HierarchyComposeInspector : ComposeInspector {
    override suspend fun detect(snapshot: UiSnapshot): ComposeInspection {
        val composeNode = snapshot.elements.firstOrNull { element ->
            val value = (element.className ?: "") + " " + (element.raw.toString())
            value.contains("ComposeView", ignoreCase = true) || value.contains("androidx.compose", ignoreCase = true)
        }
        return if (composeNode == null) {
            ComposeInspection(false, "no Compose hierarchy marker was observed", emptyList())
        } else {
            ComposeInspection(true, null, snapshot.elements.mapNotNull { it.text ?: it.contentDescription })
        }
    }
}
