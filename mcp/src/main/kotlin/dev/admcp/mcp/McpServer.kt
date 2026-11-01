package dev.admcp.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

data class ToolOutcome(val structured: JsonObject, val isError: Boolean = false)

fun interface ToolDispatcher {
    suspend fun call(name: String, arguments: JsonObject): ToolOutcome
}

class MapToolDispatcher(private val handlers: Map<String, suspend (JsonObject) -> ToolOutcome>) : ToolDispatcher {
    override suspend fun call(name: String, arguments: JsonObject): ToolOutcome {
        val handler = handlers[name]
            ?: return ToolOutcome(
                buildJsonObject {
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("code", "TOOL_NOT_IMPLEMENTED")
                        put("message", "tool $name is registered but has no v1 handler")
                        put("retryable", false)
                    })
                },
                isError = true,
            )
        return runCatching { handler(arguments) }.getOrElse { error ->
            ToolOutcome(
                buildJsonObject {
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("code", error::class.simpleName ?: "INTERNAL_ERROR")
                        put("message", error.message ?: "tool failed")
                        put("retryable", false)
                    })
                },
                isError = true,
            )
        }
    }
}

class AdmcpMcpServer(
    val catalog: ToolCatalog,
    private val dispatcher: ToolDispatcher,
    private val name: String = "admcp",
    private val version: String = "0.1.0",
) {
    val registeredTools = mutableListOf<String>()

    fun build(): Server {
        val server = Server(
            serverInfo = Implementation(name = name, version = version),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )
        catalog.all.forEach { tool ->
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = ToolSchema(properties = tool.properties, required = tool.required),
            ) { request ->
                val arguments = request.arguments ?: JsonObject(emptyMap())
                val outcome = dispatcher.call(tool.name, arguments)
                CallToolResult(
                    content = listOf(TextContent(outcome.structured.toString())),
                    structuredContent = outcome.structured,
                    isError = outcome.isError,
                )
            }
            registeredTools += tool.name
        }
        return server
    }

    suspend fun serveStdio() {
        val protocolOut = System.out
        System.setOut(System.err)
        val server = try {
            build()
        } finally {
            System.setOut(protocolOut)
        }
        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            protocolOut.asSink().buffered(),
        )
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = server.createSession(transport)
        session.onClose { closed.complete(Unit) }
        closed.await()
    }
}

data class ServerConfig(
    val workspaceRoot: Path? = null,
    val artifactRoot: Path? = null,
    val platformTools: Path? = null,
    val androidCli: Path? = null,
    val flutterRoot: Path? = null,
    val trustedWorkspaceRoots: Set<Path> = emptySet(),
)
