package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.workspace.WorkspacePathPolicy
import com.inspiredandroid.kai.workspace.WorkspaceSourcePolicy

/** Pure validation shared by the Android importer and host-side tests. */
object WorkspaceImportPolicy {
    fun validProjectName(value: String): Boolean = WorkspacePathPolicy.validProjectName(value)
    fun validBranch(value: String): Boolean = WorkspaceSourcePolicy.validBranch(value)
    fun validGithubUrl(value: String): Boolean = WorkspaceSourcePolicy.validGithubUrl(value)
    fun validUploadedArchive(value: String): Boolean = WorkspaceSourcePolicy.validUploadedArchive(value)
}
