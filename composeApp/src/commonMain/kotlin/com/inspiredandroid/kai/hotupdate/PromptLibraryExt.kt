package com.inspiredandroid.kai.hotupdate

/**
 * Extends the in-app preset/persona library with entries delivered through
 * the remote config, so new chat presets and personas ship without an APK
 * update. Remote entries are always *additive*: baked-in entries keep their
 * ids and are never replaced or removed remotely.
 */
object PromptLibraryExt {

    /**
     * Returns the remote prompt entries merged on top of any baked-in list.
     * Used by the preset/persona pickers and the "new chat" flow — the UI
     * reads this instead of the static list alone.
     */
    fun withRemote(
        bakedIn: List<RemotePromptEntry>,
        remote: List<RemotePromptEntry>,
    ): List<RemotePromptEntry> {
        if (remote.isEmpty()) return bakedIn
        // Remote entries are additive. If an id collision ever happens
        // (a baked-in entry later gets the same id as a remote one), the
        // baked-in version wins so user-visible behaviour stays stable.
        val bakedIds = bakedIn.map { it.id }.toSet()
        return bakedIn + remote.filter { it.id !in bakedIds }
    }
}
