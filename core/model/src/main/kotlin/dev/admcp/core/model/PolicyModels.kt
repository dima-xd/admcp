package dev.admcp.core.model

import java.time.Duration
import java.time.Instant

enum class AvailabilityStatus { AVAILABLE, LIMITED, UNAVAILABLE, UNKNOWN }
enum class DiagnosisConfidence { LOW, MEDIUM, HIGH, UNASSESSED }
enum class ApprovalPurpose { WORKSPACE_TRUST, UI_ACTION, DART_RUNTIME_EVALUATION }
enum class ApprovalRisk { WORKSPACE_CODE_EXECUTION, DESTRUCTIVE_ACTION, EXTERNAL_SIDE_EFFECT, UNKNOWN_SIDE_EFFECT, RUNTIME_CODE_EVALUATION }

data class ExplicitUserApproval(
    val receiptId: String,
    val purpose: ApprovalPurpose,
    val subjectId: String,
    val scopeHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
) {
    init { require(expiresAt.isAfter(issuedAt)) { "approval must expire after issue" } }
}

data class ActionConfirmation(val approval: ExplicitUserApproval)

data class ApprovalRequest(
    val purpose: ApprovalPurpose,
    val subjectId: String,
    val requestedScope: Set<String>,
    val risk: ApprovalRisk,
    val expiresAfter: Duration,
)

data class UiActionAssessment(
    val risk: UiActionRisk,
    val decision: UiActionDecision,
    val reasons: List<String>,
)

enum class UiActionRisk { READ_ONLY, REVERSIBLE, EXTERNAL_SIDE_EFFECT, DESTRUCTIVE, UNKNOWN }
enum class UiActionDecision { ALLOW, REQUIRE_CONFIRMATION, DENY }
enum class UiActionKind { TAP, LONG_PRESS, TYPE_TEXT, SCROLL, SWIPE, KEY, BACK, HOME, WAIT_FOR_STATE }
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

data class UiAction(
    val kind: UiActionKind,
    val selector: UiSelector?,
    val text: String?,
    val direction: ScrollDirection?,
    val key: String?,
    val expectedState: String?,
)

data class UiSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val exact: Boolean = true,
)

data class UiBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right >= left && bottom >= top) { "invalid bounds" } }
}

data class UiElement(
    val id: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val bounds: UiBounds?,
    val clickable: Boolean?,
    val enabled: Boolean?,
    val focused: Boolean?,
    val selected: Boolean?,
    val checked: Boolean?,
    val scrollable: Boolean?,
    val children: List<UiElement>,
    val raw: kotlinx.serialization.json.JsonObject,
)

data class UiSnapshot(
    val deviceId: DeviceId,
    val capturedAt: Instant,
    val generation: Long,
    val elements: List<UiElement>,
    val raw: kotlinx.serialization.json.JsonElement,
)

data class UiActionResult(
    val performed: Boolean,
    val matched: List<UiElement>,
    val verified: Boolean,
    val snapshot: UiSnapshot?,
    val reason: String?,
)

data class VisualSelector(val label: String)
data class ResolvedVisualTarget(val screenshotId: ArtifactId, val bounds: UiBounds, val commandTemplate: String, val generation: Long)

data class ScreenshotArtifact(
    val artifactId: ArtifactId,
    val deviceId: DeviceId,
    val capturedAt: Instant,
    val displayId: Int?,
    val width: Int,
    val height: Int,
    val sha256: String,
)

enum class ExpressionLanguage { JAVA, KOTLIN, DART, NATIVE_CPP }
enum class ExpressionOperation {
    READ_LOCAL, READ_FIELD, READ_ARRAY_ELEMENT, PROPERTY_ACCESS,
    NATIVE_SCOPE_READ, NATIVE_MEMORY_READ, METHOD_INVOCATION, ASSIGNMENT, ARBITRARY_CODE,
}

data class ExpressionPolicy(
    val allowReadOnly: Boolean,
    val allowMethodInvocation: Boolean = false,
    val maxEvaluationTime: Duration,
    val maxObjectDepth: Int,
    val maxArrayElements: Int,
)

data class EvaluationRequest(
    val language: ExpressionLanguage,
    val expression: String,
    val frame: DebugHandle,
    val policy: ExpressionPolicy,
    val timeout: Duration,
    val operation: ExpressionOperation,
)

data class EvaluationResult(
    val value: VariableSnapshot?,
    val sideEffectsPossible: Boolean,
    val error: String?,
    val handle: DebugHandle,
    val operation: ExpressionOperation,
)

data class InspectionContext(val process: ProcessRef, val stopEpoch: StopEpoch, val requestedBytes: Int, val objectDepth: Int)
data class ExpressionPolicyDecision(val allowed: Boolean, val reason: String?)

enum class WorkspaceOperation { INSPECT, BUILD, INSTALL, RUN, PREVIEW, ANALYZE }
enum class TrustStatus { TRUSTED, UNTRUSTED, REVOKED }
enum class TrustExecutionMode { SAME_USER_UNSANDBOXED, OS_SANDBOXED }
data class TrustScope(val allowedOperations: Set<WorkspaceOperation>, val allowedRoots: Set<java.nio.file.Path>)
data class TrustDecision(val allowed: Boolean, val reason: String?)
data class WorkspaceTrustGrant(
    val status: TrustStatus,
    val mode: TrustExecutionMode,
    val allowedOperations: Set<WorkspaceOperation>,
    val canonicalRoot: java.nio.file.Path,
    val approvedAt: Instant,
    val explanationAcknowledged: Boolean,
)
