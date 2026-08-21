package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import com.inspiredandroid.kai.workspace.ProotWorkspaceCommandRunner
import com.inspiredandroid.kai.workspace.WorkspaceImportRequest
import com.inspiredandroid.kai.workspace.WorkspaceImportService
import com.inspiredandroid.kai.workspace.WorkspaceImportSource
import org.koin.java.KoinJavaComponent.inject

/** Imports a real project into the shared /workspace contract. */
object WorkspaceImportTool : Tool {
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val schema = ToolSchema(
        name = "workspace_import_project",
        description = """Import a project into /workspace so chat and Moataz Terminal operate on the same files.
Use source_type=github for a public HTTPS GitHub repository. Use source_type=archive only after analyze_file has materialized the user's attached zip/tar archive under /root/uploads; pass that returned upload path as source. Extraction rejects traversal, links, excessive file counts, and oversized archives. Existing project directories are never overwritten.""",
        parameters = mapOf(
            "source_type" to ParameterSchema("string", "github or archive", true),
            "source" to ParameterSchema("string", "HTTPS GitHub URL or /root/uploads archive path", true),
            "project_name" to ParameterSchema("string", "New folder name under /workspace", true),
            "branch" to ParameterSchema("string", "Optional Git branch for GitHub imports", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        if (sandboxManager.state.value !is SandboxState.Ready) {
            return failure("Moataz Runtime is not ready")
        }
        val type = args["source_type"]?.toString()?.lowercase() ?: return failure("source_type is required")
        val source = args["source"]?.toString()?.trim() ?: return failure("source is required")
        val name = args["project_name"]?.toString()?.trim() ?: return failure("project_name is required")
        val sourceRequest = when (type) {
            "github" -> {
                if (!WorkspaceImportPolicy.validGithubUrl(source)) return failure("Only public HTTPS github.com repository URLs are accepted")
                val branch = args["branch"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (branch != null && !WorkspaceImportPolicy.validBranch(branch)) return failure("Invalid branch")
                WorkspaceImportSource.GitHub(source, branch)
            }
            "archive" -> WorkspaceImportSource.UploadedArchive(source)
            else -> return failure("Unsupported source_type: $type")
        }
        val result = runCatching {
            WorkspaceImportService(ProotWorkspaceCommandRunner(sandboxManager.createProotExecutor()))
                .import(WorkspaceImportRequest(name, sourceRequest))
        }.getOrElse { return failure(it.message ?: "Project import failed") }
        return if (result.commandResult.success) {
            mapOf(
                "success" to true,
                "project_path" to result.projectPath,
                "stdout" to result.commandResult.stdout,
                "message" to "Project imported. Inspect its files and run its tests before reporting completion.",
            )
        } else {
            failure(result.commandResult.stderr.ifBlank { "Project import failed (exit ${result.commandResult.exitCode})" })
        }
    }

    private fun failure(message: String): Map<String, Any> = mapOf("success" to false, "error" to message)

    val toolInfo = ToolInfo(
        id = "workspace_import_project",
        name = "Import Workspace Project",
        description = "Safely clone or extract a project into /workspace",
        isEnabled = false,
        userToggleable = false,
    )
}
