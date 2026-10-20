package com.waenhancer.root

import com.waenhancer.app.BuildConfig
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

    private val SPECS: Map<String, RootCommandSpec> = buildList {
        add(
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
        add(
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
        add(
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
        add(
            RootCommandSpec(
                id = "diag.process.pidof",
                category = Category.DIAGNOSTICS,
                risk = RiskLevel.INFO,
                params = listOf(
                    ParamSpec(
                        name = "packages",
                        kind = ParamKind.PACKAGE_NAME,
                        repeated = true
                    )
                ),
                template = { p ->
                    // The registry constructs the pidof script itself from the validated
                    // PACKAGE_NAME list — no caller-supplied shell reaches RootEngine.
                    // Each package gets one pidof line; the sentinel authenticates the output.
                    val packages = p["packages"].orEmpty().split("\n").filter { it.isNotBlank() }
                    if (packages.isEmpty()) {
                        "printf 'END|WAEX_PROCESS_SNAPSHOT\\n'"
                    } else {
                        val pidofLines = packages.joinToString("\n") { pkg ->
                            "pids=\$(pidof $pkg 2>/dev/null); rc=\$?; " +
                                "case \"\$rc\" in 0) [ -n \"\$pids\" ] || exit 2;; " +
                                "1) [ -z \"\$pids\" ] || exit 2;; *) exit 2;; esac; " +
                                "printf 'PID|$pkg|%s\\n' \"\$pids\""
                        }
                        "command -v pidof >/dev/null 2>&1 || exit 127\n$pidofLines\n" +
                            "printf 'END|WAEX_PROCESS_SNAPSHOT\\n'"
                    }
                },
                outputPolicy = OutputPolicy.Sentinel("END|WAEX_PROCESS_SNAPSHOT"),
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_diag_process_pidof_title,
                descriptionRes = R.string.rootop_diag_process_pidof_desc,
                detailRes = R.string.rootop_diag_process_pidof_detail
            )
        )
        add(
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
        add(
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
        add(
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
        add(
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
        add(
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
        add(
            RootCommandSpec(
                id = "hma.probe.zygisk",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.INFO,
                params = emptyList(),
                template = {
                    "[ -d /data/adb/modules/hma_oss_zygisk ] && [ ! -f /data/adb/modules/hma_oss_zygisk/disable ] && echo active; " +
                        "ls -d /data/adb/modules/*hma* 2>/dev/null; true"
                },
                outputPolicy = OutputPolicy.RawTextCapped,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_probe_zygisk_title,
                descriptionRes = R.string.rootop_hma_probe_zygisk_desc,
                detailRes = R.string.rootop_hma_probe_zygisk_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.probe.lsposed_db",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = emptyList(),
                template = {
                    "[ -f /data/adb/lspd/config/modules_config.db ] && echo exists; true"
                },
                outputPolicy = OutputPolicy.RawTextCapped,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_probe_lsposed_db_title,
                descriptionRes = R.string.rootop_hma_probe_lsposed_db_desc,
                detailRes = R.string.rootop_hma_probe_lsposed_db_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.copy_lsposed_db",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = listOf(
                    ParamSpec("cache_db", ParamKind.FILE_PATH),
                    ParamSpec("cache_wal", ParamKind.FILE_PATH),
                    ParamSpec("cache_shm", ParamKind.FILE_PATH)
                ),
                template = { p ->
                    val db = RootParamPolicy.shellQuote(p["cache_db"])
                    val wal = RootParamPolicy.shellQuote(p["cache_wal"])
                    val shm = RootParamPolicy.shellQuote(p["cache_shm"])
                    "cp /data/adb/lspd/config/modules_config.db $db 2>/dev/null; " +
                        "cp /data/adb/lspd/config/modules_config.db-wal $wal 2>/dev/null; " +
                        "cp /data/adb/lspd/config/modules_config.db-shm $shm 2>/dev/null; " +
                        "chmod 666 $db $wal $shm 2>/dev/null; true"
                },
                outputPolicy = OutputPolicy.EmptyOk,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_copy_lsposed_db_title,
                descriptionRes = R.string.rootop_hma_copy_lsposed_db_desc,
                detailRes = R.string.rootop_hma_copy_lsposed_db_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.discover.packages",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = emptyList(),
                template = { "pm list packages" },
                outputPolicy = OutputPolicy.RawTextCapped,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_discover_packages_title,
                descriptionRes = R.string.rootop_hma_discover_packages_desc,
                detailRes = R.string.rootop_hma_discover_packages_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.discover.find_configs",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = emptyList(),
                template = {
                    // §3.8 rider: -type f -xdev -maxdepth 8 closes the symlink-oracle
                    // channel; output is re-validated line-by-line by the caller
                    // against PATH_STRICT (refused lines are counted into the audit entry).
                    "find /data/misc /data/system /data/adb /data/user/0 /data/data " +
                        "-type f -xdev -maxdepth 8 -name 'config.json' " +
                        "\\( -path '*hide_my_applist*' -o -path '*hma*' \\) 2>/dev/null; true"
                },
                outputPolicy = OutputPolicy.RawTextCapped,
                timeoutMs = 8_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_discover_find_configs_title,
                descriptionRes = R.string.rootop_hma_discover_find_configs_desc,
                detailRes = R.string.rootop_hma_discover_find_configs_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.read_config",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("path", ParamKind.PATH_STRICT)),
                template = { p ->
                    // R1 fix: PATH_STRICT-validated + shell-quoted, with a 256 KiB read cap
                    // so a planted large file cannot flood the diagnostics buffer.
                    "head -c 262144 " + RootParamPolicy.shellQuote(p["path"])
                },
                outputPolicy = OutputPolicy.RawTextCapped,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_read_config_title,
                descriptionRes = R.string.rootop_hma_read_config_desc,
                detailRes = R.string.rootop_hma_read_config_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.backup_config",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("path", ParamKind.PATH_STRICT)),
                template = { p ->
                    val config = RootParamPolicy.shellQuote(p["path"])
                    val backup = RootParamPolicy.shellQuote(p["path"] + ".waex.bak")
                    "cp -p $config $backup 2>/dev/null && " +
                        "(restorecon $backup 2>/dev/null || true) && " +
                        "echo WAEX_HMA_BACKUP_OK"
                },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.HMA_BACKUP_OK.toRegex()),
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_backup_config_title,
                descriptionRes = R.string.rootop_hma_backup_config_desc,
                detailRes = R.string.rootop_hma_backup_config_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.write_config",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.HIGH,
                params = listOf(
                    ParamSpec("path", ParamKind.PATH_STRICT),
                    ParamSpec("payload", ParamKind.BASE64_TEXT)
                ),
                template = { p ->
                    // The 8-second timeout (raised from 4s in Phase 0) halves the
                    // kill-window between mv -f and the follow-up chown/restorecon
                    // (R9 race from the privilege analysis).
                    val config = RootParamPolicy.shellQuote(p["path"])
                    val tmp = RootParamPolicy.shellQuote(p["path"] + ".tmp")
                    val b64 = p["payload"]
                    "OWNER=\$(stat -c '%u:%g' $config 2>/dev/null); " +
                        "echo '$b64' | base64 -d > $tmp && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $tmp 2>/dev/null) && " +
                        "chmod 600 $tmp 2>/dev/null && " +
                        "(restorecon $tmp 2>/dev/null || true) && " +
                        "mv -f $tmp $config && " +
                        "([ -z \"\$OWNER\" ] || chown \$OWNER $config 2>/dev/null) && " +
                        "chmod 600 $config 2>/dev/null && " +
                        "(restorecon $config 2>/dev/null || true) && " +
                        "echo WAEX_HMA_WRITE_OK"
                },
                outputPolicy = OutputPolicy.Regex(RootParamPolicy.HMA_WRITE_OK.toRegex()),
                timeoutMs = 8_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_write_config_title,
                descriptionRes = R.string.rootop_hma_write_config_desc,
                detailRes = R.string.rootop_hma_write_config_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.restore_config",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("path", ParamKind.PATH_STRICT)),
                template = { p ->
                    val config = RootParamPolicy.shellQuote(p["path"])
                    val backup = RootParamPolicy.shellQuote(p["path"] + ".waex.bak")
                    "cp -p $backup $config 2>/dev/null && " +
                        "restorecon $config 2>/dev/null; true"
                },
                outputPolicy = OutputPolicy.EmptyOk,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_restore_config_title,
                descriptionRes = R.string.rootop_hma_restore_config_desc,
                detailRes = R.string.rootop_hma_restore_config_detail
            )
        )
        add(
            RootCommandSpec(
                id = "hma.force_stop",
                category = Category.HMA_REPAIR,
                risk = RiskLevel.LOW,
                params = listOf(ParamSpec("package", ParamKind.PACKAGE_NAME)),
                template = { p ->
                    // R3 fix: package is PACKAGE_NAME-validated by the registry; the caller
                    // additionally enforces the HMA allowlist (RootParamPolicy.isHmaPackage).
                    "am force-stop '" + p["package"] + "'"
                },
                outputPolicy = OutputPolicy.EmptyOk,
                timeoutMs = 4_000L,
                toggleable = true,
                titleRes = R.string.rootop_hma_force_stop_title,
                descriptionRes = R.string.rootop_hma_force_stop_desc,
                detailRes = R.string.rootop_hma_force_stop_detail
            )
        )
        // §6.5 debug-build-only "inject test command" affordance — owner-added 2026-10-20.
        // Release builds exclude the entry entirely; the enforcement scanner guards this.
        if (BuildConfig.DEBUG) {
            add(
                RootCommandSpec(
                    id = "debug.echo",
                    category = Category.DIAGNOSTICS,
                    risk = RiskLevel.INFO,
                    params = listOf(ParamSpec("text", ParamKind.BASE64_TEXT)),
                    template = { p ->
                        // The app Base64.NO_WRAP-encodes the user's text, so no metacharacter
                        // can reach the shell. The output is the decoded original text.
                        "echo " + p["text"] + " | base64 -d"
                    },
                    outputPolicy = OutputPolicy.RawTextCapped,
                    timeoutMs = 4_000L,
                    toggleable = true,
                    titleRes = R.string.rootop_debug_echo_title,
                    descriptionRes = R.string.rootop_debug_echo_desc,
                    detailRes = R.string.rootop_debug_echo_detail
                )
            )
        }
    }.associateBy { it.id }

    fun spec(commandId: String): RootCommandSpec? = SPECS[commandId]

    fun all(): List<RootCommandSpec> = SPECS.values.toList()

    fun ids(): Set<String> = SPECS.keys
}
