package com.inspiredandroid.kai.runtime.distribution

import kotlinx.serialization.Serializable

/** Independently versioned release identifier; pre-release builds are intentionally rejected. */
@Serializable
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ReleaseVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Version components must be non-negative" }
    }

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")

        fun parse(value: String): ReleaseVersion {
            val match = pattern.matchEntire(value) ?: error("Invalid release version: $value")
            return ReleaseVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }
    }
}

enum class AppDistribution {
    FullOffline,
    Lite,
}

@Serializable
data class ProductVersions(
    val app: ReleaseVersion,
    val runtime: ReleaseVersion,
    val rootfs: ReleaseVersion,
    val cliBundle: ReleaseVersion,
)

enum class RuntimeBundlePolicy {
    EmbeddedRequired,
    SignedRemoteRequired,
}

fun AppDistribution.runtimeBundlePolicy(): RuntimeBundlePolicy = when (this) {
    AppDistribution.FullOffline -> RuntimeBundlePolicy.EmbeddedRequired
    AppDistribution.Lite -> RuntimeBundlePolicy.SignedRemoteRequired
}

/**
 * Packaging contract kept independent from Gradle variant names. The current
 * `foss`/`playStore` tasks remain backward compatible and map to Full/Offline;
 * a dedicated Lite packaging task can enforce this contract without renaming
 * existing CI entry points.
 */
object RuntimePackagingContract {
    val legacyCiDistribution: AppDistribution = AppDistribution.FullOffline

    fun validate(
        distribution: AppDistribution,
        runtimeManifest: RuntimeReleaseManifest,
        packagedAssetNames: Set<String>,
    ): Result<Unit> = runCatching {
        val bundleAssets = runtimeManifest.bundle.parts.map { it.name }.toSet()
        when (distribution) {
            AppDistribution.FullOffline -> require(packagedAssetNames.containsAll(bundleAssets)) {
                "Full/Offline package is missing runtime bundle parts"
            }
            AppDistribution.Lite -> require(packagedAssetNames.intersect(bundleAssets).isEmpty()) {
                "Lite package must not embed runtime bundle parts"
            }
        }
    }
}
