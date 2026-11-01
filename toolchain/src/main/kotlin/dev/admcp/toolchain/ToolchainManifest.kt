package dev.admcp.toolchain

import dev.admcp.core.model.ToolKind

object ToolchainManifest {
    val names: Map<ToolKind, String> = mapOf(
        ToolKind.JDK to "java",
        ToolKind.ADB to "adb",
        ToolKind.ANDROID_CLI to "android",
        ToolKind.GRADLE to "gradle",
        ToolKind.FLUTTER to "flutter",
        ToolKind.DART to "dart",
        ToolKind.LLDB to "lldb",
        ToolKind.LLDB_DAP to "lldb-dap",
        ToolKind.LLDB_SERVER to "lldb-server",
        ToolKind.SQLITE to "sqlite3",
        ToolKind.CMAKE to "cmake",
        ToolKind.NINJA to "ninja",
        ToolKind.NDK to "ndk",
        ToolKind.ANDROID_SDK to "sdk",
        ToolKind.BUILD_TOOLS to "build-tools",
    )

    val versionArguments: Map<ToolKind, List<String>> = mapOf(
        ToolKind.JDK to listOf("-version"),
        ToolKind.ANDROID_CLI to listOf("--version"),
        ToolKind.LLDB_DAP to listOf("--version"),
        ToolKind.FLUTTER to listOf("--version"),
        ToolKind.DART to listOf("--version"),
    )
}
