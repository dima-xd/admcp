package dev.admcp.workspace

import dev.admcp.core.model.ModuleDescriptor
import dev.admcp.core.model.ProjectDescription
import dev.admcp.core.model.ProjectKind
import dev.admcp.core.model.TrustStatus
import java.nio.file.Path

data class Workspace(
    val root: Path,
    val kind: ProjectKind,
    val modules: List<ModuleDescriptor>,
    val trust: TrustStatus,
)

interface WorkspaceManager {
    suspend fun open(path: Path): Workspace
    suspend fun inspect(workspace: Workspace): ProjectDescription
}

class DefaultWorkspaceManager(private val detector: ProjectDetector = ProjectDetector()) : WorkspaceManager {
    override suspend fun open(path: Path): Workspace {
        val root = path.toRealPath()
        val detected = detector.detect(root)
        return Workspace(root, detected.kind, detected.modules, TrustStatus.UNTRUSTED)
    }

    override suspend fun inspect(workspace: Workspace): ProjectDescription = detector.detect(workspace.root).description
}
