package dev.admcp.workspace

import dev.admcp.core.model.BuildTarget

data class GradleProjectModel(
    val root: java.nio.file.Path,
    val wrapperPresent: Boolean,
    val targets: List<BuildTarget>,
)
