package com.inspiredandroid.kai.workspace

data class WorkspaceFileEntry(val path: String, val directory: Boolean, val sizeBytes: Long?)

class WorkspaceFileService(private val runner: WorkspaceCommandRunner) {
    suspend fun list(projectName: String, relativeDirectory: String = "", maxDepth: Int = 2): List<WorkspaceFileEntry> {
        val root = resolve(projectName, relativeDirectory, allowRoot = true)
        val project = WorkspacePaths.project(projectName)
        val result = runner.run(
            WorkspaceCommandRequest(
                command = projectGuard(project) + "resolved=${'$'}(realpath -e -- ${shellQuote(root)}) && " +
                    "case \"${'$'}resolved\" in ${shellQuote(project)}|${shellQuote("$project/")}*) ;; *) exit 64;; esac && " +
                    "find \"${'$'}resolved\" -mindepth 1 -maxdepth ${maxDepth.coerceIn(1, 8)} " +
                    "-not -path '*/.git/*' -not -path '*/.moataz/*' -printf '%y\\t%s\\t%P\\n' | head -n 5000",
                workingDirectory = project,
                maxOutputChars = 500_000,
            ),
        )
        check(result.success) { result.stderr.ifBlank { "Unable to list project files" } }
        return result.stdout.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3 || !WorkspacePathPolicy.validRelativePath(parts[2])) null
            else WorkspaceFileEntry(
                if (relativeDirectory.isEmpty()) parts[2] else "$relativeDirectory/${parts[2]}",
                parts[0] == "d",
                parts[1].toLongOrNull(),
            )
        }.toList()
    }

    suspend fun readText(projectName: String, relativePath: String, maxBytes: Int = 1_000_000): String {
        val file = resolve(projectName, relativePath)
        val project = WorkspacePaths.project(projectName)
        val limit = maxBytes.coerceIn(1, 2_000_000)
        val result = runner.run(
            WorkspaceCommandRequest(
                projectGuard(project) + "resolved=${'$'}(realpath -e -- ${shellQuote(file)}) && " +
                    "case \"${'$'}resolved\" in ${shellQuote("$project/")}*) ;; *) exit 64;; esac && " +
                    "test -f \"${'$'}resolved\" && test ! -L ${shellQuote(file)} && head -c $limit -- \"${'$'}resolved\"",
                workingDirectory = project,
                maxOutputChars = limit,
            ),
        )
        check(result.success) { result.stderr.ifBlank { "Unable to read project file" } }
        return result.stdout
    }

    /** Atomically replaces a UTF-8 text file. Project deletion is intentionally not exposed. */
    suspend fun writeText(projectName: String, relativePath: String, content: String): WorkspaceCommandResult {
        require(content.encodeToByteArray().size <= 64 * 1024) { "Text file limit exceeded" }
        val file = resolve(projectName, relativePath)
        val project = WorkspacePaths.project(projectName)
        val parent = file.substringBeforeLast('/')
        val command = projectGuard(project) + "parent=${'$'}(realpath -m -- ${shellQuote(parent)}) && " +
            "case \"${'$'}parent\" in ${shellQuote(project)}|${shellQuote("$project/")}*) ;; *) exit 64;; esac && " +
            "mkdir -p \"${'$'}parent\" && test ! -L ${shellQuote(file)} && umask 077 && " +
            "tmp=${'$'}(mktemp \"${'$'}parent/.moataz-write.XXXXXX\") || exit; " +
            "trap 'rm -f \"${'$'}tmp\"' EXIT; printf %s ${shellQuote(content)} > \"${'$'}tmp\" && " +
            "mv -- \"${'$'}tmp\" ${shellQuote(file)}"
        return runner.run(WorkspaceCommandRequest(command, workingDirectory = WorkspacePaths.project(projectName)))
    }

    suspend fun search(projectName: String, query: String, glob: String? = null): WorkspaceCommandResult {
        require(query.isNotEmpty() && query.length <= 500) { "Invalid search query" }
        require(glob == null || (glob.length <= 200 && '\u0000' !in glob)) { "Invalid search glob" }
        val globArg = glob?.let { "--glob ${shellQuote(it)} " }.orEmpty()
        return runner.run(
            WorkspaceCommandRequest(
                command = projectGuard(WorkspacePaths.project(projectName)) +
                    "rg --json --max-count 1000 --hidden --glob '!.git/**' --glob '!.moataz/**' " +
                    "$globArg-- ${shellQuote(query)} .",
                workingDirectory = WorkspacePaths.project(projectName),
                timeoutSeconds = 60,
                maxOutputChars = 1_000_000,
            ),
        )
    }

    private fun resolve(projectName: String, relativePath: String, allowRoot: Boolean = false): String {
        require(WorkspacePathPolicy.validProjectName(projectName)) { "Invalid project name" }
        require(WorkspacePathPolicy.validRelativePath(relativePath, allowRoot)) { "Invalid project-relative path" }
        return WorkspacePaths.project(projectName) + if (relativePath.isEmpty()) "" else "/$relativePath"
    }

    private fun projectGuard(project: String) = "test -d ${shellQuote(project)} && test ! -L ${shellQuote(project)} && "
}
