package com.inspiredandroid.kai.projects

import com.inspiredandroid.kai.data.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Project workspace entity (section 22). A project scopes conversations,
 * memory, tool availability, and an optional routing profile to a single
 * working context such as a repository.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    /** Local path inside the Linux sandbox, e.g. /home/user/projects/myapp */
    val sandboxPath: String? = null,
    /** Optional remote git origin. */
    val gitRemote: String? = null,
    val gitBranch: String = "main",
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = System.currentTimeMillis(),
    val conversationIds: List<String> = emptyList(),
    val routingProfileId: String = "", // "" → global
    val memoryScope: MemoryScope = MemoryScope.Isolated,
    val color: String = "",
)

/**
 * How a project shares the memory store.
 */
enum class MemoryScope {
    /** Project memories are invisible to other projects and global chat. */
    Isolated,
    /** Project memories are read by other scopes but writes stay private. */
    ReadOnlyShared,
    /** Project memories merge with the global memory store. */
    Shared,
}

/**
 * Persistence for the projects workspace.
 */
class ProjectStore(private val appSettings: AppSettings) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    fun loadProjects(): List<Project> = runCatching {
        val raw = appSettings.settings.getStringOrNull(KEY_PROJECTS)
        if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<Project>>(raw)
    }.getOrDefault(emptyList())

    fun saveProjects(projects: List<Project>) {
        runCatching {
            appSettings.settings.putString(KEY_PROJECTS, json.encodeToString(projects.sortedByDescending { it.lastOpenedAt }))
        }
    }

    fun upsert(project: Project) {
        val list = loadProjects().toMutableList()
        val index = list.indexOfFirst { it.id == project.id }
        if (index >= 0) list[index] = project else list.add(0, project)
        saveProjects(list)
    }

    fun delete(projectId: String) {
        saveProjects(loadProjects().filter { it.id != projectId })
    }

    fun get(projectId: String): Project? = loadProjects().find { it.id == projectId }

    /** Attach a conversation to a project. */
    fun attachConversation(projectId: String, conversationId: String) {
        val project = get(projectId) ?: return
        if (conversationId in project.conversationIds) return
        upsert(project.copy(conversationIds = project.conversationIds + conversationId, lastOpenedAt = System.currentTimeMillis()))
    }

    fun currentProjectId(): String? =
        appSettings.settings.getStringOrNull(KEY_CURRENT_PROJECT_ID).takeIf { !it.isNullOrBlank() }

    fun setCurrentProjectId(id: String?) {
        if (id == null) appSettings.settings.remove(KEY_CURRENT_PROJECT_ID)
        else appSettings.settings.putString(KEY_CURRENT_PROJECT_ID, id)
    }

    companion object {
        private const val KEY_PROJECTS = "projects_v1"
        private const val KEY_CURRENT_PROJECT_ID = "current_project_id"
    }
}
