package dev.admcp.workspace

import dev.admcp.core.model.Availability

data class BazelProjectModel(val root: java.nio.file.Path, val support: Availability, val evidence: List<String>)
