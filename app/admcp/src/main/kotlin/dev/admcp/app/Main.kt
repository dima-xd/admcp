package dev.admcp.app

import dev.admcp.mcp.AdmcpMcpServer
import dev.admcp.mcp.AdmcpToolHandler
import dev.admcp.mcp.CatalogLoader
import dev.admcp.mcp.ServerConfig
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

fun main(args: Array<String>) {
    System.err.println("${BuildInfo.NAME} ${BuildInfo.VERSION}")
    var config = ServerConfig()
    var selfTest = false
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--workspace" -> config = config.copy(workspaceRoot = args.getOrNull(++index)?.let(Path::of))
            "--platform-tools" -> config = config.copy(platformTools = args.getOrNull(++index)?.let(Path::of))
            "--android-cli" -> config = config.copy(androidCli = args.getOrNull(++index)?.let(Path::of))
            "--flutter-root" -> config = config.copy(flutterRoot = args.getOrNull(++index)?.let(Path::of))
            "--artifacts" -> config = config.copy(artifactRoot = args.getOrNull(++index)?.let(Path::of))
            "--trust-workspace" -> {
                val path = args.getOrNull(++index)?.let(Path::of) ?: run {
                    System.err.println("--trust-workspace requires a path")
                    kotlin.system.exitProcess(2)
                }
                config = config.copy(trustedWorkspaceRoots = config.trustedWorkspaceRoots + path)
            }
            "--selftest" -> selfTest = true
            "--version" -> {
                System.err.println(BuildInfo.VERSION)
                return
            }
            else -> {
                System.err.println("unknown argument: $arg")
                kotlin.system.exitProcess(2)
            }
        }
        index++
    }
    val catalog = CatalogLoader.load()
    val handler = AdmcpToolHandler(config)
    val server = AdmcpMcpServer(catalog, handler, version = BuildInfo.VERSION)
    if (selfTest) {
        val built = server.build()
        System.err.println("catalog=${catalog.all.size} registered=${built.tools.size} version=${BuildInfo.VERSION}")
        return
    }
    runBlocking { server.serveStdio() }
}
