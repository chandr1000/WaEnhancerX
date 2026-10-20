package com.waenhancer.root

import com.waenhancer.app.R

/**
 * Compiled-in, immutable registry of every root operation the app can perform.
 * Users toggle entries on/off; they can never add, edit, or extend entries.
 *
 * Five low-risk entries land in Phase 1:
 *   - root.probe.id            (the consent mechanism; not toggleable)
 *   - restart.target.force_stop
 *   - restart.target.launch
 *   - diag.process.pidof
 *   - diag.selinux.getenforce
 *
 * The remaining 13 entries are added in Phase 4 (update.*) and Phase 5 (hma.*).
 */
object RootCommandRegistry {

    const val PROBE_ID = "root.probe.id"

    private val SPECS: Map<String, RootCommandSpec> = buildMap {
        put(
            RootCommandSpec(
                id = PROBE_ID,
                category = Category.DIAGNOSTICS,
                risk = RiskLevel.INFO,
                params = emptyList(),
                template = { "id" },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.ROOT_ID.toRegex()),
                timeoutMs = 20_000L,
                toggleable = false,
                titleRes = R.string.rootop_root_probe_id_title,
                descriptionRes = R.string.rootop_root_probe_id_desc,
                detailRes = R.string.rootop_root_probe_id_detail
            )
        )
        put(
            RootCommandSpec(
                id = "restart.target.force_stop",
                category = Category.RESTART,
                risk = RiskLevel.HIGH,
                params = listOf(ParamSpec("package", ParamKind.PACKAGE_NAME)),
                template = { p -> "am force-stop '" + p["package"] + "'" },
                outputPolicy = OutputPolicy.EmptyOk,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_restart_target_force_stop_title,
                descriptionRes = R.string.rootop_restart_target_force_stop_desc,
                detailRes = R.string.rootop_restart_target_force_stop_detail
            )
        )
        put(
            RootCommandSpec(
                id = "restart.target.launch",
                category = Category.RESTART,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("package", ParamKind.PACKAGE_NAME)),
                template = { p ->
                    "monkey -p '" + p["package"] + "' -c android.intent.category.LAUNCHER 1"
                },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.MONKEY_LAUNCHED.toRegex()),
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_restart_target_launch_title,
                descriptionRes = R.string.rootop_restart_target_launch_desc,
                detailRes = R.string.rootop_restart_target_launch_detail
            )
        )
        put(
            RootCommandSpec(
                id = "diag.process.pidof",
                category = Category.DIAGNOSTICS,
                risk = RiskLevel.INFO,
                params = listOf(
                    ParamSpec(
                        name = "packages",
                        kind = ParamKind.PACKAGE_NAME,
                        repeated = true,
                        extra = mapOf("separator" to "\n")
                    ),
                    ParamSpec(
                        name = "script",
                        kind = ParamKind.FILE_PATH,
                        required = false
                    )
                ),
                template = { p ->
                    // The caller assembles the multi-line pidof script (one pidof line
                    // per package) and passes it in the "script" param. The registry
                    // appends the sentinel so the output policy can authenticate the
                    // trailing line.
                    val body = p["script"].orEmpty()
                    if (body.isEmpty()) "printf 'END|WAEX_PROCESS_SNAPSHOT\\n'"
                    else body + "\nprintf 'END|WAEX_PROCESS_SNAPSHOT\\n'"
                },
                outputPolicy = OutputPolicy.Sentinel("END|WAEX_PROCESS_SNAPSHOT"),
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_diag_process_pidof_title,
                descriptionRes = R.string.rootop_diag_process_pidof_desc,
                detailRes = R.string.rootop_diag_process_pidof_detail
            )
        )
        put(
            RootCommandSpec(
                id = "diag.selinux.getenforce",
                category = Category.DIAGNOSTICS,
                risk = RiskLevel.INFO,
                params = emptyList(),
                template = { "getenforce" },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.SELINUX_MODE.toRegex()),
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_diag_selinux_getenforce_title,
                descriptionRes = R.string.rootop_diag_selinux_getenforce_desc,
                detailRes = R.string.rootop_diag_selinux_getenforce_detail
            )
        )
        put(
            RootCommandSpec(
                id = "update.mktemp",
                category = Category.UPDATE,
                risk = RiskLevel.LOW,
                params = emptyList(),
                template = { "mktemp /data/local/tmp/waex.XXXXXX" },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.MKTEMP_PATH.toRegex()),
                timeoutMs = 8_000L,
                toggleable = true,
                titleRes = R.string.rootop_update_mktemp_title,
                descriptionRes = R.string.rootop_update_mktemp_desc,
                detailRes = R.string.rootop_update_mktemp_detail
            )
        )
        put(
            RootCommandSpec(
                id = "update.copy",
                category = Category.UPDATE,
                risk = RiskLevel.LOW,
                params = listOf(
                    ParamSpec("source", ParamKind.FILE_PATH),
                    ParamSpec("target", ParamKind.DERIVED_OUTPUT)
                ),
                template = { p ->
                    "cat " + RootParamPolicy.shellQuote(p["source"]) + " > " +
                        RootParamPolicy.shellQuote(p["target"]) + " && chmod 644 " +
                        RootParamPolicy.shellQuote(p["target"]) + " && wc -c < " +
                        RootParamPolicy.shellQuote(p["target"])
                },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.POSITIVE_LONG.toRegex()),
                timeoutMs = 8_000L,
                toggleable = true,
                titleRes = R.string.rootop_update_copy_title,
                descriptionRes = R.string.rootop_update_copy_desc,
                detailRes = R.string.rootop_update_copy_detail
            )
        )
        put(
            RootCommandSpec(
                id = "update.install",
                category = Category.UPDATE,
                risk = RiskLevel.HIGH,
                params = listOf(ParamSpec("target", ParamKind.DERIVED_OUTPUT)),
                template = { p ->
                    "pm install -r -d --user 0 " + RootParamPolicy.shellQuote(p["target"])
                },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.PM_INSTALL_SUCCESS.toRegex()),
                timeoutMs = 70_000L,
                toggleable = true,
                titleRes = R.string.rootop_update_install_title,
                descriptionRes = R.string.rootop_update_install_desc,
                detailRes = R.string.rootop_update_install_detail
            )
        )
        put(
            RootCommandSpec(
                id = "update.cleanup",
                category = Category.UPDATE,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("target", ParamKind.DERIVED_OUTPUT)),
                template = { p ->
                    "rm -f -- " + RootParamPolicy.shellQuote(p["target"])
                },
                outputPolicy = OutputPolicy.EmptyOk,
                timeoutMs = 8_000L,
                toggleable = true,
                titleRes = R.string.rootop_update_cleanup_title,
                descriptionRes = R.string.rootop_update_cleanup_desc,
                detailRes = R.string.rootop_update_cleanup_detail
            )
        )
    }

    fun spec(commandId: String): RootCommandSpec? = SPECS[commandId]

    fun all(): List<RootCommandSpec> = SPECS.values.toList()

    fun ids(): Set<String> = SPECS.keys
}
