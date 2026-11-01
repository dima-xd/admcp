package dev.admcp.core.model

import java.nio.file.Path

enum class ProjectKind { ANDROID_GRADLE, ANDROID_BAZEL, FLUTTER, APK_ONLY, UNKNOWN }
enum class SessionState { CREATED, PROBING, READY, STARTING, RUNNING, STOPPING, TERMINATED, FAILED }
enum class ModuleKind { ANDROID_APP, ANDROID_LIBRARY, FLUTTER_MODULE, NATIVE_LIBRARY, GRADLE_PLUGIN, BAZEL_TARGET, UNKNOWN }
enum class ArtifactKind { APK, AAB, AAR, JAR, PNG, JPEG, TEXT_LOG, BINARY_LOG, PROTO_LOG, PERFETTO_TRACE, JVM_HEAP_DUMP, JSON, OTHER }
enum class RedactionState { RAW, REDACTED_VIEW, NOT_APPLICABLE }

data class WorkspaceRef(val root: Path, val kind: ProjectKind)

data class SessionCreateRequest(
    val workspace: WorkspaceRef?,
    val deviceId: DeviceId,
    val packageName: String?,
    val buildTarget: String?,
)

data class SessionSnapshot(
    val id: SessionId,
    val state: SessionState,
    val deviceId: DeviceId,
    val process: ProcessRef?,
    val backends: List<BackendStatus>,
)

data class ModuleDescriptor(val name: String, val path: Path, val kind: ModuleKind)

data class BuildTarget(val modulePath: String, val variant: String, val artifactKind: ArtifactKind)

data class ProjectDescription(
    val kind: ProjectKind,
    val packageNames: List<String>,
    val buildTargets: List<BuildTarget>,
    val sourceRoots: List<Path>,
)

data class BuildIdentity(val variant: String, val buildId: String?, val packageName: String, val mappingHash: String?)

data class DebugTarget(
    val sessionId: SessionId,
    val process: ProcessRef,
    val sourceRoots: List<Path>,
    val buildIdentity: String?,
)

data class DisplaySnapshot(val displayId: Int, val width: Int, val height: Int, val density: Float, val rotation: Int)

enum class DeviceConnectionState { ONLINE, OFFLINE, UNAUTHORIZED, RECOVERY, BOOTLOADER, SIDELOAD, NO_PERMISSIONS, UNKNOWN }

data class BuildOptions(val clean: Boolean = false, val offline: Boolean = false, val stacktrace: Boolean = false)

data class BuildArtifact(
    val path: Path,
    val packageName: String?,
    val variant: String,
    val debuggable: Boolean,
    val buildIdentity: String,
)

data class BuildResult(
    val success: Boolean,
    val artifact: BuildArtifact?,
    val outputArtifact: ArtifactId?,
    val exitCode: Int?,
    val stdout: String = "",
    val stderr: String = "",
)

data class InstallResult(val success: Boolean, val packageName: String, val deviceId: DeviceId, val message: String?)

data class ProfileRequest(
    val deviceId: DeviceId,
    val process: ProcessRef?,
    val kind: String,
    val duration: java.time.Duration?,
    val options: kotlinx.serialization.json.JsonObject,
)

data class ProfileSession(val id: String, val deviceId: DeviceId, val kind: String, val state: String, val startedAt: java.time.Instant)

data class PreviewDescriptor(val fullyQualifiedName: String, val displayName: String?)

data class AnalysisArtifact(
    val artifactId: ArtifactId,
    val raw: kotlinx.serialization.json.JsonElement,
    val normalized: kotlinx.serialization.json.JsonObject,
)

data class FieldCrashQuery(val appId: String, val from: java.time.Instant?, val to: java.time.Instant?, val issueType: String?)
data class FieldCrashPage(val items: List<kotlinx.serialization.json.JsonObject>, val nextCursor: String?)
