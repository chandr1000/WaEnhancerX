package com.waenhancer.root

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.waenhancer.config.PreferenceStores

/**
 * Per-command kill-switch persisted in `private_config` (MODE_PRIVATE, module
 * UID only). Never written to `waex_prefs`, which is world-readable.
 *
 * Runtime checks read a Compose-observable `SnapshotStateMap` populated by
 * [load]; UI reads via [isEnabled] recompose automatically when [setEnabled]
 * mutates the map. Absent key means enabled — deny-by-default applies to
 * registry lookup, not to toggle absence. Unknown command ids (not in the
 * registry) read as disabled to avoid the latent asymmetry where a spec
 * registered only under `BuildConfig.DEBUG` would default to enabled in
 * release.
 *
 * The keys are registered in [com.waenhancer.config.PreferenceSchema] with
 * `Sensitivity.RUNTIME` so a settings backup cannot silently re-enable a
 * root capability the user disabled.
 */
object RootGate {

    private const val KEY_PREFIX = "rootop_enabled_"
    private const val KEY_AUDIT_LOG_ENABLED = "root_audit_log_enabled"
    const val KEY_CONSENT_REVIEWED = "root_consent_reviewed"

    internal val state: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    @Volatile private var auditLogEnabled = true

    /** Load every toggle value from private_config. Call from `Application.onCreate`. */
    fun load(context: Context) {
        val store = PreferenceStores.privateStore(context)
        // Build a local snapshot then swap atomically so concurrent isEnabled
        // reads never observe a partially-cleared map.
        val incoming = HashMap<String, Boolean>()
        for (spec in RootCommandRegistry.all()) {
            if (!spec.toggleable) continue
            incoming[spec.id] = store.getBoolean(keyFor(spec.id), true)
        }
        state.clear()
        state.putAll(incoming)
        auditLogEnabled = store.getBoolean(KEY_AUDIT_LOG_ENABLED, true)
    }

    fun isEnabled(commandId: String): Boolean {
        // Non-toggleable specs (e.g. root.probe.id) are always enabled.
        val spec = RootCommandRegistry.spec(commandId)
        if (spec != null && !spec.toggleable) return true
        // Unknown specs (not in registry — e.g. debug.echo in release builds) read as disabled.
        if (spec == null) return false
        return state[commandId] ?: true
    }

    fun setEnabled(context: Context, commandId: String, enabled: Boolean) {
        val spec = RootCommandRegistry.spec(commandId) ?: return
        if (!spec.toggleable) return
        state[commandId] = enabled
        PreferenceStores.privateStore(context).edit()
            .putBoolean(keyFor(commandId), enabled).apply()
    }

    fun isAuditLogEnabled(): Boolean = auditLogEnabled

    fun setAuditLogEnabled(context: Context, enabled: Boolean) {
        auditLogEnabled = enabled
        PreferenceStores.privateStore(context).edit()
            .putBoolean(KEY_AUDIT_LOG_ENABLED, enabled).apply()
    }

    fun isConsentReviewed(context: Context): Boolean =
        PreferenceStores.privateStore(context).getBoolean(KEY_CONSENT_REVIEWED, false)

    fun markConsentReviewed(context: Context) {
        PreferenceStores.privateStore(context).edit()
            .putBoolean(KEY_CONSENT_REVIEWED, true).apply()
    }

    private fun keyFor(commandId: String): String =
        KEY_PREFIX + commandId.replace('.', '_')
}
