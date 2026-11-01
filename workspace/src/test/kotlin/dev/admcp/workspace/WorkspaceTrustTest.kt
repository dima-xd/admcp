package dev.admcp.workspace

import dev.admcp.core.model.ApprovalPurpose
import dev.admcp.core.model.ExplicitUserApproval
import dev.admcp.core.model.TrustExecutionMode
import dev.admcp.core.model.TrustStatus
import dev.admcp.core.model.WorkspaceOperation
import dev.admcp.core.model.WorkspaceRef
import dev.admcp.core.model.ProjectKind
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceTrustTest {
    @Test
    fun rejectsPathTraversalOutsideWorkspace() {
        val root = tempDir()
        assertFailsWith<IllegalArgumentException> { WorkspacePathGuard(root).resolve("../../escape.txt") }
    }

    @Test
    fun rejectsSymlinkEscapeOutsideWorkspace() {
        val root = tempDir()
        val outside = tempDir()
        val link = root.resolve("link")
        runCatching { Files.createSymbolicLink(link, outside) }.onFailure { return }
        assertFailsWith<IllegalArgumentException> { WorkspacePathGuard(root).resolve("link/secret.txt") }
    }

    @Test
    fun revalidatesCanonicalPathImmediatelyBeforeUse() {
        val root = tempDir()
        val guard = WorkspacePathGuard(root)
        val target = root.resolve("app.txt")
        target.writeText("ok")
        assertEquals(target.toRealPath(), guard.revalidate(target))
        Files.delete(target)
        assertFailsWith<IllegalArgumentException> { guard.revalidate(target) }
    }

    @Test
    fun requiresApprovalBeforeGradleExecution() = runBlocking {
        val workspace = workspace()
        val policy = WorkspaceTrustPolicy(ReceiptVerifier())
        val decision = policy.requireTrusted(workspace, WorkspaceOperation.BUILD)
        assertFalse(decision.allowed)
    }

    @Test
    fun trustApprovalExplainsSameUserCodeExecutionAndIsNotSandbox() = runBlocking {
        val workspace = workspace()
        val verifier = ReceiptVerifier()
        val approval = verifier.issue(workspace)
        val policy = WorkspaceTrustPolicy(verifier)
        val grant = policy.approve(workspace, setOf(WorkspaceOperation.BUILD), approval)
        assertEquals(TrustExecutionMode.SAME_USER_UNSANDBOXED, grant.mode)
        assertTrue(grant.explanationAcknowledged)
        assertTrue(grant.allowedOperations.contains(WorkspaceOperation.BUILD))
    }

    @Test
    fun trustGrantRequiresHostApprovalReceipt() = runBlocking {
        val workspace = workspace()
        val policy = WorkspaceTrustPolicy(ReceiptVerifier())
        val forged = ExplicitUserApproval("forged", ApprovalPurpose.WORKSPACE_TRUST, workspace.root.toString(), "x", Instant.now(), Instant.now().plusSeconds(30))
        assertFailsWith<SecurityException> { policy.approve(workspace, setOf(WorkspaceOperation.BUILD), forged) }
    }

    @Test
    fun trustRevokeInvalidatesCachedGrant() = runBlocking {
        val workspace = workspace()
        val verifier = ReceiptVerifier()
        val approval = verifier.issue(workspace)
        val policy = WorkspaceTrustPolicy(verifier)
        policy.approve(workspace, setOf(WorkspaceOperation.BUILD), approval)
        policy.revoke(workspace)
        assertFalse(policy.requireTrusted(workspace, WorkspaceOperation.BUILD).allowed)
    }

    @Test
    fun grantCannotExpandBeyondCanonicalWorkspaceRoot() = runBlocking {
        val workspace = workspace()
        val outside = tempDir()
        val verifier = ReceiptVerifier()
        val approval = verifier.issue(workspace, setOf(outside))
        val policy = WorkspaceTrustPolicy(verifier)
        assertFailsWith<SecurityException> { policy.approve(workspace, setOf(WorkspaceOperation.BUILD), approval) }
    }

    @Test
    fun doesNotLeakHostEnvironmentIntoBuildByDefault() {
        val env = BuildEnvironmentPolicy.allowlist(mapOf("PATH" to "x", "SECRET_TOKEN" to "bad", "JAVA_HOME" to "jdk"))
        assertFalse(env.containsKey("SECRET_TOKEN"))
        assertEquals("jdk", env["JAVA_HOME"])
    }

    @Test
    fun supportsSpacesAndUnicodePaths() {
        val root = Files.createTempDirectory("admcp space \u00e9 ")
        root.resolve("file.txt").writeText("ok")
        assertEquals(root.resolve("file.txt").toRealPath(), WorkspacePathGuard(root).resolve("file.txt"))
    }

    private fun workspace(): Workspace {
        val root = tempDir()
        root.resolve("settings.gradle.kts").writeText("include(\":app\")\n")
        return Workspace(root.toRealPath(), ProjectKind.ANDROID_GRADLE, emptyList(), TrustStatus.UNTRUSTED)
    }

    private fun tempDir(): Path = Files.createTempDirectory("admcp-trust-")

    private class ReceiptVerifier : TrustApprovalVerifier {
        private val receipts = mutableMapOf<String, ExplicitUserApproval>()
        private var counter = 0

        fun issue(workspace: Workspace, roots: Set<Path> = setOf(workspace.root)): ExplicitUserApproval {
            val approval = ExplicitUserApproval(
                "receipt-${counter++}",
                ApprovalPurpose.WORKSPACE_TRUST,
                workspace.root.toString(),
                roots.joinToString(";"),
                Instant.now(),
                Instant.now().plusSeconds(60),
            )
            receipts[approval.receiptId] = approval
            return approval
        }

        override fun verify(approval: ExplicitUserApproval, subjectId: String, scope: Set<Path>): Boolean =
            receipts[approval.receiptId] == approval && approval.subjectId == subjectId && scope.all { it.normalize().startsWith(Path.of(approval.subjectId).normalize()) }
    }
}
