package dev.admcp.app

object BuildInfo {
    val VERSION: String = BuildInfo::class.java.`package`?.implementationVersion ?: "0.1.0"
    const val NAME: String = "admcp"

    val version: String
        get() = VERSION
}
