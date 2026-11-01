package dev.admcp.workspace

object BuildEnvironmentPolicyDefaults {
    fun filtered(source: Map<String, String>): Map<String, String> = BuildEnvironmentPolicy.allowlist(source)
}
