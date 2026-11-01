package dev.admcp.workspace

import dev.admcp.core.model.ExplicitUserApproval
import dev.admcp.core.model.TrustDecision
import dev.admcp.core.model.TrustExecutionMode
import dev.admcp.core.model.TrustScope
import dev.admcp.core.model.TrustStatus
import dev.admcp.core.model.WorkspaceOperation
import dev.admcp.core.model.WorkspaceTrustGrant
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface TrustApprovalVerifier {
    fun verify(approval: ExplicitUserApproval, subjectId: String, scope: Set<Path>): Boolean
}

interface WorkspaceTrustService {
    suspend fun requireTrusted(workspace: Workspace, operation: WorkspaceOperation): TrustDecision
    suspend fun approve(
        workspace: Workspace,
        scope: TrustScope,
        approval: ExplicitUserApproval,
        executionMode: TrustExecutionMode = TrustExecutionMode.SAME_USER_UNSANDBOXED,
    ): WorkspaceTrustGrant
    suspend fun revoke(workspace: Workspace): WorkspaceTrustGrant
}

class WorkspaceTrustPolicy(private val verifier: TrustApprovalVerifier) : WorkspaceTrustService {
    private val grants = ConcurrentHashMap<Path, WorkspaceTrustGrant>()

    override suspend fun requireTrusted(workspace: Workspace, operation: WorkspaceOperation): TrustDecision {
        val canonical = workspace.root.toRealPath()
        val grant = grants[canonical]
        if (grant == null || grant.status != TrustStatus.TRUSTED) {
            return TrustDecision(false, "workspace trust grant is missing or revoked")
        }
        if (operation !in grant.allowedOperations) {
            return TrustDecision(false, "operation $operation is outside the trust grant")
        }
        if (!workspace.root.toRealPath().startsWith(grant.canonicalRoot)) {
            return TrustDecision(false, "workspace root changed since approval")
        }
        return TrustDecision(true, null)
    }

    override suspend fun approve(
        workspace: Workspace,
        scope: TrustScope,
        approval: ExplicitUserApproval,
        executionMode: TrustExecutionMode,
    ): WorkspaceTrustGrant {
        val canonical = workspace.root.toRealPath()
        val roots = scope.allowedRoots.map { it.toAbsolutePath().normalize() }
        if (roots.any { !it.startsWith(canonical) }) {
            throw SecurityException("trust scope cannot expand beyond the canonical workspace root")
        }
        if (!verifier.verify(approval, canonical.toString(), roots.toSet())) {
            throw SecurityException("approval receipt is not host-issued for this workspace and scope")
        }
        val grant = WorkspaceTrustGrant(
            status = TrustStatus.TRUSTED,
            mode = executionMode,
            allowedOperations = scope.allowedOperations,
            canonicalRoot = canonical,
            approvedAt = Instant.now(),
            explanationAcknowledged = true,
        )
        grants[canonical] = grant
        return grant
    }

    suspend fun approve(
        workspace: Workspace,
        operations: Set<WorkspaceOperation>,
        approval: ExplicitUserApproval,
        executionMode: TrustExecutionMode = TrustExecutionMode.SAME_USER_UNSANDBOXED,
    ): WorkspaceTrustGrant = approve(workspace, TrustScope(operations, setOf(workspace.root)), approval, executionMode)

    override suspend fun revoke(workspace: Workspace): WorkspaceTrustGrant {
        val canonical = workspace.root.toRealPath()
        val grant = grants.remove(canonical) ?: WorkspaceTrustGrant(
            TrustStatus.REVOKED,
            TrustExecutionMode.SAME_USER_UNSANDBOXED,
            emptySet(),
            canonical,
            Instant.now(),
            true,
        )
        return grant.copy(status = TrustStatus.REVOKED, allowedOperations = emptySet())
    }
}

object BuildEnvironmentPolicy {
    private val allowed = setOf(
        "PATH", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_NDK_HOME",
        "GRADLE_USER_HOME", "SystemRoot", "TEMP", "TMP", "HOME",
    )

    fun allowlist(source: Map<String, String>): Map<String, String> =
        source.filterKeys { it in allowed }
}
