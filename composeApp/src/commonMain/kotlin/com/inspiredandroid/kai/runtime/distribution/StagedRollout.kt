package com.inspiredandroid.kai.runtime.distribution

data class StagedRollout(
    val rolloutId: String,
    val basisPoints: Int,
    val enabled: Boolean = true,
) {
    init {
        require(rolloutId.isNotBlank()) { "Rollout id is required" }
        require(basisPoints in 0..10_000) { "Rollout must be between 0 and 10000 basis points" }
    }
}
object DeterministicRolloutGate {
    fun isEligible(installationId: String, rollout: StagedRollout): Boolean {
        require(installationId.isNotBlank()) { "Installation id is required" }
        if (!rollout.enabled || rollout.basisPoints == 0) return false
        if (rollout.basisPoints == 10_000) return true
        return stableBucket("${rollout.rolloutId}:$installationId") < rollout.basisPoints
    }

    internal fun stableBucket(value: String): Int {
        // FNV-1a is used only for stable allocation, never for integrity or signatures.
        var hash = 0x811c9dc5u
        value.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toUInt()
            hash *= 0x01000193u
        }
        return (hash % 10_000u).toInt()
    }
}
