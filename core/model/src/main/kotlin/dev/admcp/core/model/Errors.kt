package dev.admcp.core.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

data class AdmcpError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: Map<String, JsonElement> = emptyMap(),
) {
    init {
        require(code.isNotBlank()) { "error code must not be blank" }
        require(message.isNotBlank()) { "error message must not be blank" }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("code", kotlinx.serialization.json.JsonPrimitive(code))
        put("message", kotlinx.serialization.json.JsonPrimitive(message))
        put("retryable", kotlinx.serialization.json.JsonPrimitive(retryable))
        put("details", JsonObject(details))
    }

    companion object {
        fun of(code: String, message: String, retryable: Boolean = false): AdmcpError =
            AdmcpError(code, message, retryable)
    }
}

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>
    data class Failure(val error: AdmcpError) : OperationResult<Nothing>
}
