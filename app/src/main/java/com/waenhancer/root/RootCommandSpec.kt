package com.waenhancer.root

/**
 * Spec model for a registered root operation. The registry is compiled-in and
 * immutable at runtime; users toggle entries off, they can never add or edit them.
 *
 * Validation order is fixed: registry lookup -> gate -> param policy -> template
 * (defensive quoting inside) -> engine -> output policy. Two independent layers
 * (validate-then-quote-then-interpolate).
 *
 * Resource ID fields (titleRes/descriptionRes/detailRes) are wired in Phase 2 when
 * the dedicated string files land; they default to 0 here so the registry structure
 * is testable in isolation.
 */
enum class ParamKind {
    CONSTANT,
    PACKAGE_NAME,
    PATH_STRICT,
    FILE_PATH,
    BASE64_TEXT,
    DERIVED_OUTPUT
}

enum class RiskLevel { INFO, LOW, HIGH }

enum class Category { RESTART, UPDATE, DIAGNOSTICS, HMA_REPAIR }

enum class BlockedReason {
    DISABLED_BY_USER,
    UNREGISTERED,
    INVALID_PARAMETER,
    OUTPUT_POLICY_FAILED
}

enum class FailureKind { TIMEOUT, NONZERO_EXIT, SPAWN_ERROR }

data class ParamSpec(
    val name: String,
    val kind: ParamKind,
    val required: Boolean = true,
    val repeated: Boolean = false,
    val extra: Map<String, String> = emptyMap()
)

/**
 * Output contract a command's captured stdout must satisfy. The runner refuses a
 * typed {@link RootResult.Blocked} (OUTPUT_POLICY_FAILED) when the policy fails,
 * and a {@link ParamSpec} of kind DERIVED_OUTPUT may only be fed from an output
 * that passed the previous command's policy.
 */
sealed class OutputPolicy {
    /** Trailing line must equal the sentinel; used by multi-line pidof/HMA scripts. */
    data class Sentinel(val exact: String) : OutputPolicy()
    /** Whole-output single-line regex. */
    data class Regex(val pattern: kotlin.text.Regex) : OutputPolicy()
    /** Any output is accepted as-is (already bounded by the 8 KiB capture cap). */
    object EmptyOk : OutputPolicy()
    /** Output is accepted as long as it parses; caller is responsible for downstream parsing. */
    object RawTextCapped : OutputPolicy()
}

sealed class RootResult {
    object Success : RootResult()
    data class Completed(val output: String) : RootResult()
    data class Blocked(val reason: BlockedReason) : RootResult()
    data class Failed(val kind: FailureKind) : RootResult()

    fun isSuccessful(): Boolean = this is Success || this is Completed
    fun outputOrNull(): String? = (this as? Completed)?.output
}

data class RootCommandSpec(
    val id: String,
    val category: Category,
    val risk: RiskLevel,
    val params: List<ParamSpec>,
    val template: (Map<String, String>) -> String,
    val outputPolicy: OutputPolicy,
    val timeoutMs: Long,
    val toggleable: Boolean,
    val titleRes: Int = 0,
    val descriptionRes: Int = 0,
    val detailRes: Int = 0
)
