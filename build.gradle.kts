import org.apache.tools.ant.filters.ReplaceTokens
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    base
    kotlin("jvm") version "2.4.21" apply false
}

val releaseVersion = providers.gradleProperty("releaseVersion").orElse("0.1.0")

allprojects {
    group = "dev.admcp"
    version = releaseVersion.get()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Unique project coordinates: leaf names collide (device/android-cli vs ui/android-cli,
    // debug/api vs ui/api) and Gradle would otherwise treat them as one module.
    group = "dev.admcp." + path.removePrefix(":").replace(':', '.')
    val moduleArchiveName = path.removePrefix(":").replace(':', '-')
    extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
        archivesName.set(moduleArchiveName)
    }

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    dependencies {
        add("implementation", rootProject.libs.kotlinx.coroutines.core)
        add("testImplementation", rootProject.libs.kotlin.test.junit5)
        add("testImplementation", rootProject.libs.junit.jupiter)
        add("testImplementation", rootProject.libs.kotlinx.coroutines.test)
        add("testRuntimeOnly", rootProject.libs.junit.launcher)
        add("testRuntimeOnly", rootProject.libs.slf4j.nop)
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}

private fun Project.dep(vararg paths: String) {
    paths.forEach { dependencies.add("implementation", dependencies.project(path = it)) }
}

private fun Project.testDep(vararg paths: String) {
    paths.forEach { dependencies.add("testImplementation", dependencies.project(path = it)) }
}

private fun Project.impl(notation: Any) {
    dependencies.add("implementation", notation)
}

private fun Project.testImpl(notation: Any) {
    dependencies.add("testImplementation", notation)
}

project(":core:model") {
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":core:process") {
    dep(":core:model")
}

project(":core:capability") {
    dep(":core:model")
}

project(":core:session") {
    dep(":core:model")
}

project(":core:events") {
    dep(":core:model")
    impl(rootProject.libs.sqlite.jdbc)
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":core:artifacts") {
    dep(":core:model")
}

project(":toolchain") {
    dep(":core:model", ":core:process", ":core:capability")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":device:adb") {
    dep(":core:model", ":core:process")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":device:android-cli") {
    dep(":core:model", ":core:process")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":workspace") {
    dep(":core:model", ":toolchain", ":device:adb", ":device:android-cli")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":app:lifecycle") {
    dep(":core:model", ":workspace", ":device:adb", ":device:android-cli")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":ui:api") {
    dep(":core:model")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":ui:android-cli") {
    dep(":core:model", ":ui:api", ":device:android-cli", ":core:artifacts")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":ui:uiautomator") {
    dep(":core:model", ":ui:api", ":device:adb")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":ui:compose-inspector") {
    dep(":core:model", ":ui:api")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":logcat") {
    dep(":core:model", ":core:process", ":core:events", ":core:artifacts", ":device:adb")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":debug:api") {
    dep(":core:model")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":source-resolver") {
    dep(":core:model")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":debug:jdi") {
    dep(":core:model", ":core:process", ":device:adb", ":debug:api", ":source-resolver")
}

project(":debug:expression") {
    dep(":core:model", ":debug:api", ":debug:jdi")
}

project(":debug:dap") {
    dep(":core:model")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":debug:lldb") {
    dep(":core:model", ":debug:api", ":debug:dap", ":debug:expression", ":device:adb", ":source-resolver")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":debug:coordinator") {
    dep(":core:model", ":debug:api")
}

project(":debug:flutter") {
    dep(":core:model", ":debug:api", ":debug:dap", ":debug:expression")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":profiler") {
    dep(":core:model", ":core:artifacts", ":device:android-cli", ":device:adb")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":diagnostics") {
    dep(":core:model", ":core:events", ":logcat", ":core:artifacts")
    impl(rootProject.libs.kotlinx.serialization.json)
}

project(":mcp") {
    dep(
        ":core:model", ":core:process", ":core:capability", ":core:session", ":core:events", ":core:artifacts",
        ":toolchain", ":device:adb", ":device:android-cli", ":workspace", ":app:lifecycle",
        ":ui:api", ":ui:android-cli", ":ui:uiautomator", ":ui:compose-inspector", ":logcat",
        ":debug:api", ":debug:coordinator", ":debug:expression", ":debug:jdi", ":debug:lldb",
        ":debug:flutter", ":source-resolver", ":profiler", ":diagnostics",
    )
    impl(rootProject.libs.mcp.sdk)
    impl(rootProject.libs.kotlinx.serialization.json)
    testDep(":debug:jdi", ":debug:lldb", ":debug:flutter")
}

project(":app:admcp") {
    apply(plugin = "application")
    extensions.configure<org.gradle.api.plugins.BasePluginExtension> {
        archivesName.set("admcp")
    }
    extensions.configure<org.gradle.api.plugins.JavaApplication> {
        mainClass.set("dev.admcp.app.MainKt")
        applicationName = "admcp"
    }
    tasks.withType<org.gradle.api.tasks.bundling.Jar>().configureEach {
        manifest.attributes["Implementation-Version"] = project.version.toString()
    }
    tasks.named<org.gradle.api.tasks.bundling.Zip>("distZip") {
        archiveFileName.set("admcp-${project.version}.zip")
    }
    tasks.named<org.gradle.api.tasks.bundling.Tar>("distTar") {
        archiveFileName.set("admcp-${project.version}.tar")
    }
    tasks.register<Sync>("mcpbStage") {
        dependsOn(tasks.named("installDist"))
        into(layout.buildDirectory.dir("mcpb"))
        from(layout.buildDirectory.dir("install/admcp")) {
            into("server")
            exclude("bin/**")
        }
        from(rootProject.file("LICENSE"))
        from("mcpb") {
            include("manifest.json")
            filter(
                ReplaceTokens::class,
                "tokens" to mapOf("version" to project.version.toString()),
            )
        }
        from("mcpb") {
            include("launcher.js")
            into("server")
        }
    }
    dep(":mcp", ":core:model", ":debug:jdi", ":debug:lldb", ":debug:flutter", ":debug:coordinator")
    impl(rootProject.libs.mcp.sdk)
    impl(rootProject.libs.kotlinx.serialization.json)
    dependencies.add("runtimeOnly", rootProject.libs.slf4j.nop)
}

project(":integration-tests") {
    dep(
        ":core:model", ":core:process", ":core:session", ":core:events", ":core:artifacts",
        ":toolchain", ":device:adb", ":device:android-cli", ":workspace", ":app:lifecycle",
        ":ui:api", ":ui:android-cli", ":ui:uiautomator", ":logcat", ":debug:api",
        ":debug:coordinator", ":debug:expression", ":profiler", ":diagnostics", ":mcp",
        ":app:admcp",
    )
    testDep(":debug:jdi", ":debug:lldb", ":debug:flutter")
    impl(rootProject.libs.kotlinx.serialization.json)
}
