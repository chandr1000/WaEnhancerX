package com.waenhancer.root

/**
 * Pre-flight consent for multi-command flows. Checks all required command ids and
 * their toggle states up front, then the caller re-checks each toggle immediately
 * before each step (the check is a map lookup — free).
 *
 * Used from Phase 4 (update chain) and Phase 5 (HMA repair chain).
 */
object RootFlowConsent {

    data class PreFlightResult(
        val allowed: Boolean,
        val blockedCommandId: String? = null,
        val blockedReason: BlockedReason? = null
    )

    /** All required commands must be registered AND enabled. */
    fun preFlight(commandIds: List<String>): PreFlightResult {
        for (id in commandIds) {
            val spec = RootCommandRegistry.spec(id)
            if (spec == null) {
                return PreFlightResult(false, id, BlockedReason.UNREGISTERED)
            }
            if (spec.toggleable && !RootGate.isEnabled(id)) {
                return PreFlightResult(false, id, BlockedReason.DISABLED_BY_USER)
            }
        }
        return PreFlightResult(true)
    }

    /**
     * Re-check immediately before each step. Returns the typed BlockedReason
     * (or null when the step is allowed) so callers can produce the correct
     * toast and audit entry.
     */
    fun stepBlockedReason(commandId: String): BlockedReason? {
        val spec = RootCommandRegistry.spec(commandId) ?: return BlockedReason.UNREGISTERED
        return if (spec.toggleable && !RootGate.isEnabled(commandId))
            BlockedReason.DISABLED_BY_USER else null
    }

    /** Backwards-compatible boolean view of [stepBlockedReason]. */
    fun isStepEnabled(commandId: String): Boolean = stepBlockedReason(commandId) == null
}
