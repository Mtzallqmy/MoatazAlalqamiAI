package com.inspiredandroid.kai.runtime.distribution

import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeSlot { A, B }

@Serializable
enum class RuntimeSlotHealth { Staged, Verified, Active, Failed }

@Serializable
data class InstalledRuntimeSlot(
    val releaseId: String,
    val runtimeVersion: ReleaseVersion,
    val bundleSha256: String,
    val health: RuntimeSlotHealth,
)

@Serializable
data class RuntimeActivationState(
    val activeSlot: RuntimeSlot? = null,
    val rollbackSlot: RuntimeSlot? = null,
    val slots: Map<RuntimeSlot, InstalledRuntimeSlot> = emptyMap(),
)

object RuntimeSlotCoordinator {
    fun stage(
        state: RuntimeActivationState,
        release: InstalledRuntimeSlot,
    ): RuntimeActivationState {
        require(release.health == RuntimeSlotHealth.Staged) { "A new runtime must enter the staged state" }
        require(release.bundleSha256.matches(Regex("^[0-9a-f]{64}$"))) { "Invalid runtime bundle SHA-256" }
        val target = inactiveSlot(state.activeSlot)
        require(target != state.rollbackSlot) { "Retire the verified rollback runtime before reusing its slot" }
        return state.copy(slots = state.slots + (target to release))
    }

    fun retireRollback(state: RuntimeActivationState): RuntimeActivationState = state.copy(rollbackSlot = null)

    fun markVerified(state: RuntimeActivationState, slot: RuntimeSlot): RuntimeActivationState {
        require(slot != state.activeSlot) { "The active runtime cannot be re-verified in place" }
        val installed = state.slots[slot] ?: error("Runtime slot is empty")
        require(installed.health == RuntimeSlotHealth.Staged) { "Only a staged runtime can be verified" }
        return state.copy(slots = state.slots + (slot to installed.copy(health = RuntimeSlotHealth.Verified)))
    }

    fun markFailed(state: RuntimeActivationState, slot: RuntimeSlot): RuntimeActivationState {
        require(slot != state.activeSlot) { "A failed candidate must not overwrite the active runtime" }
        val installed = state.slots[slot] ?: error("Runtime slot is empty")
        return state.copy(slots = state.slots + (slot to installed.copy(health = RuntimeSlotHealth.Failed)))
    }

    fun activate(state: RuntimeActivationState, slot: RuntimeSlot): RuntimeActivationState {
        val candidate = state.slots[slot] ?: error("Runtime slot is empty")
        require(candidate.health == RuntimeSlotHealth.Verified) { "Runtime health checks must pass before activation" }
        val oldActive = state.activeSlot
        val updated = state.slots.toMutableMap()
        if (oldActive != null) {
            val previous = updated.getValue(oldActive)
            updated[oldActive] = previous.copy(health = RuntimeSlotHealth.Verified)
        }
        updated[slot] = candidate.copy(health = RuntimeSlotHealth.Active)
        return RuntimeActivationState(activeSlot = slot, rollbackSlot = oldActive, slots = updated)
    }

    fun rollback(state: RuntimeActivationState): RuntimeActivationState {
        val target = state.rollbackSlot ?: error("No rollback runtime is available")
        val previous = state.slots[target] ?: error("Rollback runtime slot is empty")
        require(previous.health == RuntimeSlotHealth.Verified) { "Rollback runtime is not healthy" }
        val current = state.activeSlot ?: error("No active runtime")
        val currentRuntime = state.slots.getValue(current)
        return state.copy(
            activeSlot = target,
            rollbackSlot = current,
            slots = state.slots +
                (target to previous.copy(health = RuntimeSlotHealth.Active)) +
                (current to currentRuntime.copy(health = RuntimeSlotHealth.Verified)),
        )
    }

    private fun inactiveSlot(active: RuntimeSlot?): RuntimeSlot = when (active) {
        RuntimeSlot.A -> RuntimeSlot.B
        RuntimeSlot.B, null -> RuntimeSlot.A
    }
}

/** Runtime slots are replaceable; user projects remain outside both slots. */
object RuntimeStorageLayout {
    const val slotA = "runtime/slots/a"
    const val slotB = "runtime/slots/b"
    const val stateFile = "runtime/activation-state.json"
    const val projects = "projects"
}
