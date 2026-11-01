package dev.admcp.core.model

@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) { "sessionId must not be blank" } }
}

@JvmInline
value class DeviceId(val value: String) {
    init { require(value.isNotBlank()) { "deviceId must not be blank" } }
}

@JvmInline
value class CapabilityId(val value: String) {
    init { require(value.isNotBlank()) { "capabilityId must not be blank" } }
}

@JvmInline
value class ProcessId(val value: Int) {
    init { require(value >= 0) { "pid must not be negative" } }
}

@JvmInline
value class ProcessInstanceId(val value: String) {
    init { require(value.isNotBlank()) { "processInstanceId must not be blank" } }
}

@JvmInline
value class ArtifactId(val value: String) {
    init { require(value.isNotBlank()) { "artifactId must not be blank" } }
}

@JvmInline
value class EventId(val value: String) {
    init { require(value.isNotBlank()) { "eventId must not be blank" } }
}

@JvmInline
value class StopEpoch(val value: Long) {
    init { require(value >= 0) { "stopEpoch must not be negative" } }
}

@JvmInline
value class BackendId(val value: String) {
    init { require(value.isNotBlank()) { "backendId must not be blank" } }
}

@JvmInline
value class OperationId(val value: String) {
    init { require(value.isNotBlank()) { "operationId must not be blank" } }
}

@JvmInline
value class WorkspaceId(val value: String) {
    init { require(value.isNotBlank()) { "workspaceId must not be blank" } }
}
