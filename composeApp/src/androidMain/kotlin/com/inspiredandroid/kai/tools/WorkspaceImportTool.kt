package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
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
        if (!WorkspaceImportPolicy.validProjectName(name)) return failure("Invalid project_name")
        val target = "/workspace/$name"
        val executor = sandboxManager.createProotExecutor()
        val exists = executor.execute("test -e ${quote(target)}", timeoutSeconds = 10)
        if (exists["success"] == true) return failure("Project already exists: $target")

        val result = when (type) {
            "github" -> {
                if (!WorkspaceImportPolicy.validGithubUrl(source)) return failure("Only public HTTPS github.com repository URLs are accepted")
                val branch = args["branch"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                if (branch != null && !WorkspaceImportPolicy.validBranch(branch)) return failure("Invalid branch")
                val branchArg = branch?.let { "--branch ${quote(it)} " }.orEmpty()
                executor.execute(
                    "git clone --depth 1 $branchArg-- ${quote(source)} ${quote(target)}",
                    timeoutSeconds = 180,
                    workingDir = "/workspace",
                )
            }
            "archive" -> importArchive(executor, source, target, name)
            else -> return failure("Unsupported source_type: $type")
        }
        val success = result["success"] == true
        return if (success) {
            mapOf(
                "success" to true,
                "project_path" to target,
                "stdout" to result["stdout"]?.toString().orEmpty(),
                "message" to "Project imported. Inspect its files and run its tests before reporting completion.",
            )
        } else {
            failure(result["stderr"]?.toString()?.takeIf { it.isNotBlank() }
                ?: result["error"]?.toString() ?: "Project import failed")
        }
    }

    private fun importArchive(
        executor: com.inspiredandroid.kai.sandbox.ProotExecutor,
        source: String,
        target: String,
        name: String,
    ): Map<String, Any> {
        if (!WorkspaceImportPolicy.validUploadedArchive(source)) {
            return failure("Archive must be a file created by analyze_file under /root/uploads")
        }
        val staged = "/workspace/.moataz-import-$name"
        val script = SAFE_ARCHIVE_SCRIPT.trimIndent()
        val command = "rm -rf ${quote(staged)} && mkdir -p ${quote(staged)} && " +
            "python3 -c ${quote(script)} ${quote(source)} ${quote(staged)} && " +
            "mv -- ${quote(staged)} ${quote(target)}"
        return executor.execute(command, timeoutSeconds = 180, workingDir = "/workspace")
    }

    private fun failure(message: String): Map<String, Any> = mapOf("success" to false, "error" to message)
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    val toolInfo = ToolInfo(
        id = "workspace_import_project",
        name = "Import Workspace Project",
        description = "Safely clone or extract a project into /workspace",
        isEnabled = false,
        userToggleable = false,
    )

    private const val SAFE_ARCHIVE_SCRIPT = """
import pathlib, sys, tarfile, zipfile
src, dst = sys.argv[1], pathlib.Path(sys.argv[2])
MAX_FILES, MAX_BYTES = 20000, 2 * 1024 * 1024 * 1024
def safe_name(name):
    p = pathlib.PurePosixPath(name.replace('\\', '/'))
    return not p.is_absolute() and '..' not in p.parts
if zipfile.is_zipfile(src):
    with zipfile.ZipFile(src) as z:
        members = z.infolist()
        if len(members) > MAX_FILES or sum(m.file_size for m in members) > MAX_BYTES: raise SystemExit('archive limits exceeded')
        if any(not safe_name(m.filename) or ((m.external_attr >> 16) & 0o170000) == 0o120000 for m in members): raise SystemExit('unsafe archive member')
        z.extractall(dst)
else:
    with tarfile.open(src, 'r:*') as t:
        members = t.getmembers()
        if len(members) > MAX_FILES or sum(m.size for m in members) > MAX_BYTES: raise SystemExit('archive limits exceeded')
        if any(not safe_name(m.name) or m.issym() or m.islnk() or m.isdev() for m in members): raise SystemExit('unsafe archive member')
        t.extractall(dst, filter='data')
"""
}
