package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceImportPolicyTest {
    @Test
    fun `accepts only bounded project names`() {
        assertTrue(WorkspaceImportPolicy.validProjectName("moataz-app"))
        assertFalse(WorkspaceImportPolicy.validProjectName("../project"))
        assertFalse(WorkspaceImportPolicy.validProjectName("project name"))
    }

    @Test
    fun `accepts public https github repository urls only`() {
        assertTrue(WorkspaceImportPolicy.validGithubUrl("https://github.com/Mtzallqmy/MoatazAlalqamiAI.git"))
        assertFalse(WorkspaceImportPolicy.validGithubUrl("http://github.com/owner/repo"))
        assertFalse(WorkspaceImportPolicy.validGithubUrl("https://evil.example/owner/repo"))
        assertFalse(WorkspaceImportPolicy.validGithubUrl("git@github.com:owner/repo.git"))
    }

    @Test
    fun `archive must be an analyzed upload without traversal`() {
        assertTrue(WorkspaceImportPolicy.validUploadedArchive("/root/uploads/project.tar.xz"))
        assertFalse(WorkspaceImportPolicy.validUploadedArchive("/root/uploads/../secret.zip"))
        assertFalse(WorkspaceImportPolicy.validUploadedArchive("/workspace/project.zip"))
        assertFalse(WorkspaceImportPolicy.validUploadedArchive("/root/uploads/project.apk"))
    }
}
