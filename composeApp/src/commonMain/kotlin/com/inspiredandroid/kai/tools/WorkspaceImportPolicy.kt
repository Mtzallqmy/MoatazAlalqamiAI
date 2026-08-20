package com.inspiredandroid.kai.tools

/** Pure validation shared by the Android importer and host-side tests. */
object WorkspaceImportPolicy {
    private val projectName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val branchName = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
    private val githubUrl = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?/?")
    private val archiveExtensions = listOf(".zip", ".tar", ".tgz", ".tar.gz", ".tar.xz")

    fun validProjectName(value: String): Boolean = projectName.matches(value) && value != "." && value != ".."
    fun validBranch(value: String): Boolean = branchName.matches(value) && ".." !in value
    fun validGithubUrl(value: String): Boolean = githubUrl.matches(value)
    fun validUploadedArchive(value: String): Boolean {
        val lower = value.lowercase()
        return value.startsWith("/root/uploads/") && ".." !in value && !value.endsWith('/') &&
            archiveExtensions.any(lower::endsWith)
    }
}
