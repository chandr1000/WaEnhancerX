package com.waenhancer.root

import android.content.Context
import com.waenhancer.config.PreferenceStores
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-command kill-switch persisted in {@code private_config} (MODE_PRIVATE, module
 * UID only). Never written to {@code waex_prefs}, which is world-readable.
 *
 * Runtime checks read an in-memory cache populated by {@link #load}; the cache is
 * refreshed at app startup and after every {@link #setEnabled} write. Absent key
 * in either store means enabled — deny-by-default applies to registry lookup, not
 * to toggle absence.
 *
 * The keys are registered in {@link com.waenhancer.config.PreferenceSchema} with
 * {@code Sensitivity.RUNTIME} so a settings backup cannot silently re-enable a
 * root capability the user disabled.
 */
object RootGate {

    private const val KEY_PREFIX = "rootop_enabled_"
    private const val KEY_AUDIT_LOG_ENABLED = "root_audit_log_enabled"
    const val KEY_CONSENT_REVIEWED = "root_consent_reviewed"

    private val state = ConcurrentHashMap<String, Boolean>()
    @Volatile private var auditLogEnabled = true

    /** Load every toggle value from private_config. Call from {@code Application.onCreate}. */
    fun load(context: Context) {
        val store = PreferenceStores.privateStore(context)
        state.clear()
        for (spec in RootCommandRegistry.all()) {
            if (!spec.toggleable) continue
            state[spec.id] = store.getBoolean(keyFor(spec.id), true)
        }
        auditLogEnabled = store.getBoolean(KEY_AUDIT_LOG_ENABLED, true)
    }

    fun isEnabled(commandId: String): Boolean {
        if (commandId == RootCommandRegistry.PROBE_ID) return true
        return state[commandId] ?: true
    }

    fun setEnabled(context: Context, commandId: String, enabled: Boolean) {
        if (commandId == RootCommandRegistry.PROBE_ID) return
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
