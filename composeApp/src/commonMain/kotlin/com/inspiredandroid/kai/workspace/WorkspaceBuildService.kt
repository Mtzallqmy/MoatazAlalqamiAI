package com.inspiredandroid.kai.workspace

enum class BuildSystem { GRADLE, NODE, PYTHON, CARGO }

data class BuildCommand(
    val system: BuildSystem,
    val title: String,
    val command: String,
    val kind: Kind,
) {
    enum class Kind { BUILD, TEST, PREVIEW }
}

class WorkspaceBuildService(private val runner: WorkspaceCommandRunner) {
    suspend fun detect(projectName: String): List<BuildCommand> {
        require(WorkspacePathPolicy.validProjectName(projectName)) { "Invalid project name" }
        val result = runner.run(
            WorkspaceCommandRequest(
                command = projectGuard(projectName) + DETECT_COMMAND,
                workingDirectory = WorkspacePaths.project(projectName),
                timeoutSeconds = 15,
            ),
        )
        check(result.success) { result.stderr.ifBlank { "Build system detection failed" } }
        val markers = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return buildList {
            if ("gradle" in markers) {
                add(BuildCommand(BuildSystem.GRADLE, "Gradle build", "./gradlew build", BuildCommand.Kind.BUILD))
                add(BuildCommand(BuildSystem.GRADLE, "Gradle tests", "./gradlew test", BuildCommand.Kind.TEST))
            }
            if ("node:npm" in markers || "node:pnpm" in markers || "node:yarn" in markers) {
                val tool = when {
                    "node:pnpm" in markers -> "pnpm"
                    "node:yarn" in markers -> "yarn"
                    else -> "npm"
                }
                add(BuildCommand(BuildSystem.NODE, "Node build", "$tool run build", BuildCommand.Kind.BUILD))
                add(BuildCommand(BuildSystem.NODE, "Node tests", "$tool test", BuildCommand.Kind.TEST))
                add(BuildCommand(BuildSystem.NODE, "Node preview", "$tool run dev", BuildCommand.Kind.PREVIEW))
            }
            if ("python" in markers) add(BuildCommand(BuildSystem.PYTHON, "Python tests", "python3 -m pytest", BuildCommand.Kind.TEST))
            if ("cargo" in markers) {
                add(BuildCommand(BuildSystem.CARGO, "Cargo build", "cargo build", BuildCommand.Kind.BUILD))
                add(BuildCommand(BuildSystem.CARGO, "Cargo tests", "cargo test", BuildCommand.Kind.TEST))
            }
        }
    }

    suspend fun execute(projectName: String, command: BuildCommand): WorkspaceCommandResult {
        require(command in supportedCommands()) { "Unsupported build command" }
        return runner.run(
            WorkspaceCommandRequest(
                command = projectGuard(projectName) + command.command,
                workingDirectory = WorkspacePaths.project(projectName),
                timeoutSeconds = if (command.kind == BuildCommand.Kind.PREVIEW) 30 else 600,
            ),
        )
    }

    private fun supportedCommands(): Set<BuildCommand> = setOf(
        BuildCommand(BuildSystem.GRADLE, "Gradle build", "./gradlew build", BuildCommand.Kind.BUILD),
        BuildCommand(BuildSystem.GRADLE, "Gradle tests", "./gradlew test", BuildCommand.Kind.TEST),
        BuildCommand(BuildSystem.NODE, "Node build", "npm run build", BuildCommand.Kind.BUILD),
        BuildCommand(BuildSystem.NODE, "Node tests", "npm test", BuildCommand.Kind.TEST),
        BuildCommand(BuildSystem.NODE, "Node preview", "npm run dev", BuildCommand.Kind.PREVIEW),
        BuildCommand(BuildSystem.NODE, "Node build", "pnpm run build", BuildCommand.Kind.BUILD),
        BuildCommand(BuildSystem.NODE, "Node tests", "pnpm test", BuildCommand.Kind.TEST),
        BuildCommand(BuildSystem.NODE, "Node preview", "pnpm run dev", BuildCommand.Kind.PREVIEW),
        BuildCommand(BuildSystem.NODE, "Node build", "yarn run build", BuildCommand.Kind.BUILD),
        BuildCommand(BuildSystem.NODE, "Node tests", "yarn test", BuildCommand.Kind.TEST),
        BuildCommand(BuildSystem.NODE, "Node preview", "yarn run dev", BuildCommand.Kind.PREVIEW),
        BuildCommand(BuildSystem.PYTHON, "Python tests", "python3 -m pytest", BuildCommand.Kind.TEST),
        BuildCommand(BuildSystem.CARGO, "Cargo build", "cargo build", BuildCommand.Kind.BUILD),
        BuildCommand(BuildSystem.CARGO, "Cargo tests", "cargo test", BuildCommand.Kind.TEST),
    )

    private companion object {
        fun projectGuard(projectName: String): String {
            require(WorkspacePathPolicy.validProjectName(projectName)) { "Invalid project name" }
            val project = WorkspacePaths.project(projectName)
            return "test -d ${shellQuote(project)} && test ! -L ${shellQuote(project)} && "
        }

        const val DETECT_COMMAND = """test -x ./gradlew && echo gradle
if test -f package.json; then
  if test -f pnpm-lock.yaml; then echo node:pnpm
  elif test -f yarn.lock; then echo node:yarn
  else echo node:npm; fi
fi
test -f pyproject.toml -o -f pytest.ini -o -f setup.py -o -f requirements.txt && echo python
test -f Cargo.toml && echo cargo
true"""
    }
}
