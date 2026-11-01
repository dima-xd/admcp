rootProject.name = "admcp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    ":core:model",
    ":core:process",
    ":core:capability",
    ":core:session",
    ":core:events",
    ":core:artifacts",
    ":toolchain",
    ":device:adb",
    ":device:android-cli",
    ":workspace",
    ":app:lifecycle",
    ":app:admcp",
    ":ui:api",
    ":ui:android-cli",
    ":ui:uiautomator",
    ":ui:compose-inspector",
    ":logcat",
    ":debug:api",
    ":debug:jdi",
    ":debug:expression",
    ":debug:dap",
    ":debug:lldb",
    ":debug:coordinator",
    ":debug:flutter",
    ":source-resolver",
    ":profiler",
    ":diagnostics",
    ":mcp",
    ":integration-tests",
)
