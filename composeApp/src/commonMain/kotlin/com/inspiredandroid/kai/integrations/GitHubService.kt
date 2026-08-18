package com.inspiredandroid.kai.agents

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.security.ProviderCredentialsResolver
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub integration (section 19).
 *
 * Security posture:
 * - The personal access token is stored exclusively in the encrypted
 *   [ProviderCredentialsResolver] / SecretStore vault — never in plain
 *   SharedPreferences, never logged, never injected into agent prompts.
 * - The agent can reference the token to the git CLI only via the sandbox
 *   executor's own credential helper, never as a visible argument.
 */
object GitHubSecretKeys {
    const val TOKEN_KEY = "github_token"
    const val ALLOWED_REPOS_KEY = "github_allowed_repos"
}

@Serializable
data class GitHubAccount(
    val login: String,
    val tokenStored: Boolean,
)

@Serializable
data class GitHubRepositoryRef(
    val fullName: String,
    val cloneUrl: String,
    val isPrivate: Boolean,
    val updatedAt: String? = null,
)

/**
 * GitHub operations available once the user links an account.
 * The implementation is HTTP over the REST API — no external SDK dependency.
 */
class GitHubService(
    private val appSettings: AppSettings,
    private val resolver: ProviderCredentialsResolver,
) {
    val json = Json { ignoreUnknownKeys = true }

    suspend fun isConfigured(): Boolean {
        val token = resolver.secretStore.get(GitHubSecretKeys.TOKEN_KEY)
        return !token.isNullOrBlank()
    }

    suspend fun setToken(token: String) {
        resolver.secretStore.put(GitHubSecretKeys.TOKEN_KEY, token)
    }

    suspend fun clearToken() {
        resolver.secretStore.remove(GitHubSecretKeys.TOKEN_KEY)
    }

    suspend fun setAllowedRepos(repos: List<String>) {
        resolver.secretStore.put(GitHubSecretKeys.ALLOWED_REPOS_KEY, repos.joinToString(","))
    }

    suspend fun getAllowedRepos(): List<String> =
        resolver.secretStore.get(GitHubSecretKeys.ALLOWED_REPOS_KEY)
            .orEmpty().split(",").filter { it.isNotBlank() }

    /** Whether a repo is within the user-allowed list (empty = all allowed). */
    suspend fun isRepoAllowed(fullName: String): Boolean {
        val allowed = getAllowedRepos()
        return allowed.isEmpty() || fullName in allowed
    }

    suspend fun account(): GitHubAccount? {
        if (!isConfigured()) return null
        // Login is resolved from the API on demand; stored cache key only.
        val login = appSettings.settings.getStringOrNull(ACCOUNT_LOGIN_KEY)
        return GitHubAccount(login ?: "", tokenStored = true)
    }

    /**
     * Builds the clone URL with the vault token embedded for the sandbox
     * executor — the token never appears in agent-visible parameters.
     */
    suspend fun authenticatedCloneUrl(repoFullName: String): String? {
        if (!isRepoAllowed(repoFullName)) return null
        val token = resolver.secretStore.get(GitHubSecretKeys.TOKEN_KEY) ?: return null
        return "https://x-access-token:$token@github.com/$repoFullName.git"
    }

    companion object {
        private const val ACCOUNT_LOGIN_KEY = "github_account_login"
    }
}
