package com.waenhancer.root

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory ring buffer of root command activity. RAM-only — never persisted; a
 * persisted root log on a rooted device is readable by anything with root, which
 * would invert the feature's purpose. Cleared on process death.
 *
 * Entries hold metadata + a 200-char output preview; no full-output retention
 * anywhere. Blocked attempts are recorded too — transparency is
 * the point of the feature.
 *
 * Recording is skipped when {@link RootGate#isAuditLogEnabled} is false. The UI
 * copy states honestly this is a privacy control for the user, not a security
 * control over WAEX.
 */
object RootAuditLog {

    private const val MAX_ENTRIES = 200
    private const val PREVIEW_CHARS = 200

    data class Entry(
        val epochMs: Long,
        val commandId: String,
        val params: Map<String, String>,
        val outcome: RootResult,
        val outputPreview: String?,
        val durationMs: Long,
        val callerTag: String
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    @Synchronized
    fun record(
        commandId: String,
        params: Map<String, String>,
        outcome: RootResult,
        rawOutput: String?,
        durationMs: Long,
        callerTag: String
    ) {
        if (!RootGate.isAuditLogEnabled()) return
        val preview = rawOutput?.let { if (it.length <= PREVIEW_CHARS) it else it.take(PREVIEW_CHARS) }
        val entry = Entry(
            epochMs = System.currentTimeMillis(),
            commandId = commandId,
            params = params,
            outcome = outcome,
            outputPreview = preview,
            durationMs = durationMs,
            callerTag = callerTag
        )
        val current = _entries.value
        val updated = if (current.size >= MAX_ENTRIES) {
            (current + entry).takeLast(MAX_ENTRIES)
        } else {
            current + entry
        }
        _entries.value = updated
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun count(): Int = _entries.value.size
}
