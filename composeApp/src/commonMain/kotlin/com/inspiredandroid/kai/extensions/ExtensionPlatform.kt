package com.inspiredandroid.kai.extensions

import kotlinx.serialization.Serializable

/** Unified contract for CLI, MCP and Skill extensions. */
@Serializable
data class ExtensionManifest(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val kind: ExtensionKind,
    val version: String,
    val displayName: String,
    val source: ExtensionSource,
    val integrity: ExtensionIntegrity,
    val compatibility: ExtensionCompatibility = ExtensionCompatibility(),
    val requestedPermissions: Set<ExtensionPermission> = emptySet(),
    val healthCheck: ExtensionHealthCheck,
) {
    fun validate(): Result<Unit> = runCatching {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "unsupported extension schema: $schemaVersion" }
        require(id.matches(ID_PATTERN)) { "invalid extension id" }
        require(version.matches(VERSION_PATTERN)) { "invalid extension version" }
        require(displayName.isNotBlank() && displayName.length <= 80) { "invalid extension display name" }
        source.validate()
        integrity.validate(source.kind)
        compatibility.validate()
        healthCheck.validate(kind)
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        private val ID_PATTERN = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
    }
}

@Serializable
enum class ExtensionKind { CLI, MCP, SKILL }

@Serializable
data class ExtensionSource(
    val kind: ExtensionSourceKind,
    val uri: String? = null,
    /** Immutable catalog artifact id, Git commit SHA, or local import id. */
    val immutableRef: String? = null,
) {
    internal fun validate() {
        when (kind) {
            ExtensionSourceKind.BUILT_IN -> require(uri == null) { "built-in source cannot have a remote URI" }
            ExtensionSourceKind.SIGNED_CATALOG -> {
                require(uri?.startsWith("https://") == true) { "catalog source must use HTTPS" }
                require(!immutableRef.isNullOrBlank()) { "catalog source requires an immutable reference" }
            }
            ExtensionSourceKind.GIT -> {
                require(uri?.startsWith("https://") == true) { "Git source must use HTTPS" }
                require(immutableRef?.matches(Regex("[0-9a-fA-F]{40}")) == true) {
                    "Git extensions must pin a full commit SHA"
                }
            }
            ExtensionSourceKind.LOCAL_IMPORT -> require(!immutableRef.isNullOrBlank()) {
                "local import requires an immutable import id"
            }
        }
    }
}

@Serializable
enum class ExtensionSourceKind { BUILT_IN, SIGNED_CATALOG, GIT, LOCAL_IMPORT }

@Serializable
data class ExtensionIntegrity(
    val sha256: String = "",
    val signature: String? = null,
    val keyId: String? = null,
) {
    internal fun validate(sourceKind: ExtensionSourceKind) {
        if (sourceKind == ExtensionSourceKind.BUILT_IN) {
            require(signature == null && keyId == null) { "built-in integrity is provided by APK signing" }
            return
        }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "extension SHA-256 is required" }
        if (signature != null || keyId != null) {
            require(!signature.isNullOrBlank() && !keyId.isNullOrBlank()) {
                "extension signature and key id must be supplied together"
            }
        }
        if (sourceKind == ExtensionSourceKind.SIGNED_CATALOG) {
            require(!signature.isNullOrBlank() && !keyId.isNullOrBlank()) {
                "signed catalog extensions require a signature"
            }
        }
    }
}

@Serializable
data class ExtensionCompatibility(
    val minAppVersion: String? = null,
    val maxAppVersion: String? = null,
    val runtimeApi: Int? = null,
    val distro: String? = null,
    val distroVersion: String? = null,
    val architecture: String? = null,
    val requiredExecutables: Set<String> = emptySet(),
) {
    internal fun validate() {
        require(runtimeApi == null || runtimeApi > 0) { "runtime API must be positive" }
        require(requiredExecutables.all { it.matches(Regex("[A-Za-z0-9._+-]+")) }) {
            "invalid required executable"
        }
    }
}

@Serializable
enum class ExtensionPermission {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    PROCESS_EXECUTE,
    TERMINAL_CONTROL,
    NETWORK,
    PACKAGE_INSTALL,
    SECRET_USE,
    EXTERNAL_EFFECT,
}

@Serializable
data class ExtensionHealthCheck(
    val kind: ExtensionHealthKind,
    val target: String,
    val timeoutSeconds: Int = 10,
) {
    internal fun validate(extensionKind: ExtensionKind) {
        require(target.isNotBlank() && target.length <= 512) { "invalid extension health target" }
        require(timeoutSeconds in 1..60) { "extension health timeout is out of range" }
        when (extensionKind) {
            ExtensionKind.CLI -> require(kind == ExtensionHealthKind.COMMAND)
            ExtensionKind.MCP -> require(kind == ExtensionHealthKind.MCP_INITIALIZE)
            ExtensionKind.SKILL -> require(kind == ExtensionHealthKind.CONTENT_PROBE)
        }
    }
}

@Serializable
enum class ExtensionHealthKind { COMMAND, MCP_INITIALIZE, CONTENT_PROBE }

/** Installed artifact plus the SHA-256 of its canonical manifest bytes. */
@Serializable
data class InstalledExtension(
    val manifest: ExtensionManifest,
    val manifestDigest: String,
) {
    fun validate(): Result<Unit> = runCatching {
        manifest.validate().getOrThrow()
        require(manifestDigest.matches(Regex("[0-9a-f]{64}"))) { "invalid manifest digest" }
    }
}

/** A user decision scoped to one exact immutable manifest revision. */
@Serializable
data class ExtensionGrant(
    val extensionId: String,
    val extensionVersion: String,
    val manifestDigest: String,
    val permissions: Set<ExtensionPermission>,
)

/** Deny-by-default admission policy. No id-only or version-only grant is valid. */
object ExtensionPermissionPolicy {
    fun authorize(
        installed: InstalledExtension,
        permission: ExtensionPermission,
        grant: ExtensionGrant?,
    ): Result<Unit> = runCatching {
        installed.validate().getOrThrow()
        require(permission in installed.manifest.requestedPermissions) {
            "extension did not declare permission $permission"
        }
        requireNotNull(grant) { "extension permission was not granted" }
        require(grant.extensionId == installed.manifest.id) { "grant extension id mismatch" }
        require(grant.extensionVersion == installed.manifest.version) { "grant extension version mismatch" }
        require(grant.manifestDigest == installed.manifestDigest) { "grant manifest digest mismatch" }
        require(permission in grant.permissions) { "extension permission was not granted" }
        require(grant.permissions.all { it in installed.manifest.requestedPermissions }) {
            "grant contains undeclared permissions"
        }
    }
}
