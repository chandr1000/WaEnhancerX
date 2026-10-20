package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.root.RegisteredRootRunner
import com.waenhancer.root.RootParamPolicy
import com.waenhancer.root.RootResult
import com.waenhancer.root.RootFlowConsent
import com.waenhancer.xposed.utils.AppRestartHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Diagnostics utility for detecting Hide My Applist (HMA) activity and checking
 * whether any WhatsApp target package has configured rules hiding WAEX.
 *
 * Checks are heavy (requiring root file inspection / SQLite parsing), so results
 * are evaluated on demand or cached during app launch.
 */
object HmaDiagnostics {

    private const val HMA_ZYGISK_PATH = "/data/adb/modules/hma_oss_zygisk"
    private const val LSP_CONFIG_DB = "/data/adb/lspd/config/modules_config.db"
    private const val HMA_CONFIG_GLOB = "/data/misc/hide_my_applist*/config.json"
    private const val HMA_ALT_CONFIG = "/data/adb/hma/config.json"

    private val cachedResult = AtomicReference<HmaReport?>(null)

    enum class HmaState {
        CHECKING,
        NOT_ACTIVE,
        NOT_CONFIGURED,
        CLEAN,
        BLOCKING_TARGETS
    }

    data class TargetIsolationDetail(
        val packageName: String,
        val appName: String,
        val isBlocking: Boolean,
        val reason: String
    )

    data class HmaReport(
        val state: HmaState,
        val targetDetails: List<TargetIsolationDetail> = emptyList(),
        val blockedTargets: List<String> = emptyList(),
        val message: String? = null
    )

    fun cached(): HmaReport? = cachedResult.get()

    /**
     * Inspects HMA state and verifies if any target package in [targetPackages]
     * is configured to hide WAEX.
     */
    suspend fun checkHmaStatus(
        context: Context,
        targetPackages: Collection<String>,
        forceRefresh: Boolean = false
    ): HmaReport = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedResult.get()?.let { return@withContext it }
        }

        val report = runCheck(context.applicationContext, targetPackages)
        cachedResult.set(report)
        report
    }

    private fun runCheck(context: Context, targetPackages: Collection<String>): HmaReport {
        if (!isHmaInstalledAndActive(context)) {
            return HmaReport(HmaState.NOT_ACTIVE)
        }

        val allConfigs = readAllHmaConfigs()
        if (allConfigs.isEmpty()) {
            return HmaReport(HmaState.NOT_CONFIGURED)
        }

        // Known module package identifiers to check for concealment
        val currentPackage = context.packageName
        val moduleSignatures = setOf(currentPackage, "com.waenhancer", "com.waenhancer.debug")

        // Packages to check: target packages passed in plus DeclaredWhatsAppTargets if targetPackages is empty
        val packagesToCheck = if (targetPackages.isNotEmpty()) {
            targetPackages.toSet()
        } else {
            DeclaredWhatsAppTargets.packages()
        }

        val details = mutableListOf<TargetIsolationDetail>()
        val blockedList = mutableListOf<String>()

        for (pkg in packagesToCheck) {
            val appLabel = resolveAppLabel(context, pkg)
            var foundInAnyScope = false
            var primaryBlockReason: String? = null

            for ((_, config) in allConfigs) {
                val templates = config.optJSONObject("templates") ?: JSONObject()
                val scope = config.optJSONObject("scope") ?: JSONObject()
                val scopeObj = scope.optJSONObject(pkg) ?: continue

                foundInAnyScope = true
                val blockReason = checkScopeBlockReason(scopeObj, templates, moduleSignatures)
                if (blockReason != null) {
                    primaryBlockReason = blockReason
                    break
                }
            }

            if (!foundInAnyScope) {
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = false,
                        reason = "Not configured in HMA scope"
                    )
                )
            } else if (primaryBlockReason != null) {
                blockedList.add(pkg)
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = true,
                        reason = primaryBlockReason
                    )
                )
            } else {
                details.add(
                    TargetIsolationDetail(
                        packageName = pkg,
                        appName = appLabel,
                        isBlocking = false,
                        reason = "Active in HMA, module visible"
                    )
                )
            }
        }

        return if (blockedList.isEmpty()) {
            HmaReport(HmaState.CLEAN, targetDetails = details)
        } else {
            HmaReport(HmaState.BLOCKING_TARGETS, targetDetails = details, blockedTargets = blockedList)
        }
    }

    private fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            when {
                packageName == "com.whatsapp" -> "WhatsApp"
                packageName == "com.whatsapp.w4b" -> "WhatsApp Business"
                else -> packageName
            }
        }
    }

    private val KNOWN_HMA_PACKAGES = setOf(
        "com.tsng.hidemyapplist",
        "org.frknkrc44.hma_oss",
        "com.google.android.hmal"
    )

    private fun isHmaInstalledAndActive(context: Context): Boolean {
        // 1. Zygisk module directory check
        val zygiskResult = RegisteredRootRunner.run("hma.probe.zygisk", callerTag = "hma")
        val zygiskCheck = (zygiskResult as? RootResult.Completed)?.output
        if (zygiskCheck?.contains("active") == true || zygiskCheck?.contains("hma") == true) {
            return true
        }

        // 2. LSPosed module database check
        val dbResult = RegisteredRootRunner.run("hma.probe.lsposed_db", callerTag = "hma")
        val dbCheck = (dbResult as? RootResult.Completed)?.output
        if (dbCheck?.contains("exists") == true) {
            val cacheDir = context.cacheDir
            val cacheDb = File(cacheDir, "hma_lsposed_inspect.db")
            val walDb = File(cacheDir, "hma_lsposed_inspect.db-wal")
            val shmDb = File(cacheDir, "hma_lsposed_inspect.db-shm")

            listOf(cacheDb, walDb, shmDb).forEach { if (it.exists()) it.delete() }

            RegisteredRootRunner.run(
                "hma.copy_lsposed_db",
                mapOf(
                    "cache_db" to cacheDb.absolutePath,
                    "cache_wal" to walDb.absolutePath,
                    "cache_shm" to shmDb.absolutePath
                ),
                "hma"
            )

            if (cacheDb.exists()) {
                val found = isHmaEnabledInLspDb(cacheDb)
                listOf(cacheDb, walDb, shmDb).forEach { if (it.exists()) it.delete() }
                if (found) return true
            }
        }

        // 3. Installed package manager check dynamically for any HMA / HMAL packages
        val installedHmaPackages = getInstalledHmaPackages(context)
        if (installedHmaPackages.isNotEmpty()) {
            return true
        }

        // 4. Check if any HMA config file exists directly
        val configPaths = findAllHmaConfigPaths()
        return configPaths.isNotEmpty()
    }

    /**
     * Dynamically discovers all installed HMA / HMAL packages on the system.
     */
    private fun getInstalledHmaPackages(context: Context): List<String> {
        val packages = mutableSetOf<String>()
        try {
            val pm = context.packageManager
            val installed = pm.getInstalledPackages(0).map { it.packageName }
            for (pkg in installed) {
                val lower = pkg.lowercase()
                if (lower.contains("hidemyapp") || lower.contains("hma") || lower.endsWith(".hmal")) {
                    packages.add(pkg)
                }
            }
        } catch (_: Exception) {}

        // Also query via root package manager in case package visibility is restricted
        val pmResult = RegisteredRootRunner.run("hma.discover.packages", callerTag = "hma")
        val pmOut = (pmResult as? RootResult.Completed)?.output
        if (!pmOut.isNullOrBlank()) {
            pmOut.lines().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("package:")) {
                    val p = line.removePrefix("package:").trim()
                    val lower = p.lowercase()
                    if (lower.contains("hidemyapp") || lower.contains("hma") || lower.endsWith(".hmal")) {
                        packages.add(p)
                    }
                }
            }
        }

        return packages.toList()
    }

    private fun isHmaEnabledInLspDb(dbFile: File): Boolean {
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT 1 FROM modules WHERE (module_pkg_name LIKE '%hidemyapp%' OR module_pkg_name LIKE '%hma%') AND enabled = 1 LIMIT 1",
                null
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } catch (_: Exception) {
            false
        } finally {
            db?.close()
        }
    }

    /**
     * Reads all available HMA / HMAL configurations across the device.
     */
    private fun readAllHmaConfigs(): List<Pair<String, JSONObject>> {
        val paths = findAllHmaConfigPaths()
        val configs = mutableListOf<Pair<String, JSONObject>>()
        for (path in paths) {
            // R1 fix: read through the registered hma.read_config entry — the path is
            // PATH_STRICT-validated by the registry and shell-quoted in the template.
            val out = readHmaConfig(path)
            if (!out.isNullOrBlank() && out.trimStart().startsWith("{")) {
                try {
                    configs.add(path to JSONObject(out))
                } catch (_: Exception) {}
            }
        }
        return configs
    }

    private fun readHmaConfig(path: String): String? {
        val result = RegisteredRootRunner.run("hma.read_config", mapOf("path" to path), "hma")
        return (result as? RootResult.Completed)?.output
    }

    /**
     * Finds all valid HMA / HMAL configuration paths across system directories,
     * zygisk data directories, and app-internal private files.
     *
     * The find sweep uses the §3.8 hardening rider (-type f -xdev -maxdepth 8) via
     * the registry; every returned path is re-validated against PATH_STRICT locally
     * so a planted symlink or metacharacter-laden filename cannot reach the cat step.
     * First 20 hits are kept (overflow is silently dropped; a future audit entry
     * will count them when the find_configs spec gains PATH_LINES_STRICT output policy).
     */
    private fun findAllHmaConfigPaths(): List<String> {
        val result = RegisteredRootRunner.run("hma.discover.find_configs", callerTag = "hma")
        val out = (result as? RootResult.Completed)?.output ?: return emptyList()
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && RootParamPolicy.isValidPathStrict(it) }
            .take(20)
            .distinct()
    }

    /**
     * Fixes HMA isolation settings for the specified [targetPackage] by removing
     * any blocking rules across all found HMA config files (such as 'xposed' preset,
     * module in blacklist extra list/template, or adding module to whitelist extra list/template
     * when whitelist mode is enabled).
     */
    suspend fun fixTargetIsolation(context: Context, targetPackage: String): Boolean = withContext(Dispatchers.IO) {
        // Pre-flight consent: if the user disabled any step in the backup→write→
        // verify→restore chain, refuse to start.
        val chain = listOf(
            "hma.backup_config",
            "hma.write_config",
            "hma.read_config",
            "hma.restore_config"
        )
        val preFlight = RootFlowConsent.preFlight(chain)
        if (!preFlight.allowed) return@withContext false

        // Force-stop HMA and any related processes BEFORE reading/modifying configuration
        forceStopHmaApps(context)

        val configPaths = findAllHmaConfigPaths()
        if (configPaths.isEmpty()) return@withContext false

        val moduleSignatures = setOf(
            context.packageName,
            "com.waenhancer",
            "com.waenhancer.debug"
        )

        var anySuccess = false

        for (configPath in configPaths) {
            val currentJsonStr = readHmaConfig(configPath) ?: continue
            if (!currentJsonStr.trimStart().startsWith("{")) continue

            try {
                val root = JSONObject(currentJsonStr)
                val scope = root.optJSONObject("scope") ?: continue
                val targetScope = scope.optJSONObject(targetPackage) ?: continue

                val useWhitelist = targetScope.optBoolean("useWhitelist", false)

                if (useWhitelist) {
                    // Whitelist mode: To make module visible, module package MUST be in extraAppList
                    val extraAppList = targetScope.optJSONArray("extraAppList") ?: JSONArray().also {
                        targetScope.put("extraAppList", it)
                    }
                    val currentApps = (0 until extraAppList.length()).map { extraAppList.optString(it) }.toMutableSet()
                    currentApps.add(context.packageName)
                    val newArr = JSONArray()
                    currentApps.forEach { newArr.put(it) }
                    targetScope.put("extraAppList", newArr)

                    // Also remove from extraOppositeAppList if present
                    targetScope.optJSONArray("extraOppositeAppList")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (v !in moduleSignatures) filtered.put(v)
                        }
                        targetScope.put("extraOppositeAppList", filtered)
                    }
                } else {
                    // Blacklist mode: remove module package from extraAppList
                    targetScope.optJSONArray("extraAppList")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (v !in moduleSignatures) filtered.put(v)
                        }
                        targetScope.put("extraAppList", filtered)
                    }

                    // Remove "xposed" preset from applyPresets
                    targetScope.optJSONArray("applyPresets")?.let { arr ->
                        val filtered = JSONArray()
                        for (i in 0 until arr.length()) {
                            val v = arr.optString(i)
                            if (!v.equals("xposed", ignoreCase = true)) filtered.put(v)
                        }
                        targetScope.put("applyPresets", filtered)
                    }
                }

                // Remove module from any blacklist templates
                val templates = root.optJSONObject("templates")
                if (templates != null) {
                    val keys = templates.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val template = templates.optJSONObject(key) ?: continue
                        val isWhitelist = template.optBoolean("isWhitelist", false)
                        if (!isWhitelist) {
                            template.optJSONArray("appList")?.let { arr ->
                                val filtered = JSONArray()
                                for (i in 0 until arr.length()) {
                                    val v = arr.optString(i)
                                    if (v !in moduleSignatures) filtered.put(v)
                                }
                                template.put("appList", filtered)
                            }
                        }
                    }
                }

                // Keep a recoverable copy before replacing HMA's live configuration.
                if (!RootFlowConsent.isStepEnabled("hma.backup_config")) continue
                val backupResult = RegisteredRootRunner.run(
                    "hma.backup_config",
                    mapOf("path" to configPath),
                    "hma"
                )
                val backupOk = (backupResult as? RootResult.Completed)?.output?.let {
                    RootParamPolicy.HMA_BACKUP_OK.matcher(it).matches()
                } ?: false
                if (!backupOk) continue

                // Write modified JSON using a temporary file, preserving owner and SELinux context.
                if (!RootFlowConsent.isStepEnabled("hma.write_config")) continue
                val b64 = android.util.Base64.encodeToString(
                    root.toString().toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                val writeResult = RegisteredRootRunner.run(
                    "hma.write_config",
                    mapOf("path" to configPath, "payload" to b64),
                    "hma"
                )
                val writeOk = (writeResult as? RootResult.Completed)?.output?.let {
                    RootParamPolicy.HMA_WRITE_OK.matcher(it).matches()
                } ?: false
                val verifiedJson = if (writeOk) {
                    readHmaConfig(configPath)
                } else null
                val verified = try {
                    val parsed = JSONObject(verifiedJson ?: "")
                    val verifiedScope = parsed.optJSONObject("scope")?.optJSONObject(targetPackage)
                    val verifiedTemplates = parsed.optJSONObject("templates") ?: JSONObject()
                    verifiedScope != null && checkScopeBlockReason(
                        verifiedScope, verifiedTemplates, moduleSignatures
                    ) == null
                } catch (_: Exception) {
                    false
                }

                if (verified) {
                    anySuccess = true
                } else if (RootFlowConsent.isStepEnabled("hma.restore_config")) {
                    // A failed/partial write must never strand the user with a broken HMA config.
                    RegisteredRootRunner.run(
                        "hma.restore_config",
                        mapOf("path" to configPath),
                        "hma"
                    )
                }
            } catch (_: Exception) {}
        }

        // Force-stop again after updating config to ensure clean state
        forceStopHmaApps(context)

        // Clear cached diagnostic result so next check reflects new state
        cachedResult.set(null)
        anySuccess
    }

    /**
     * Force-stops all HMA and related app instances and processes.
     *
     * R3 fix: every target is PACKAGE_NAME-validated by the registry AND passes the
     * HMA allowlist (RootParamPolicy.isHmaPackage) at the caller. Apps merely named
     * like HMA still get stopped if they pass both checks — that residual is honest
     * and disclosed in the detail string.
     */
    fun forceStopHmaApps(context: Context) {
        val installedHmas = getInstalledHmaPackages(context)
        for (pkg in installedHmas) {
            if (!RootParamPolicy.isHmaPackage(pkg)) continue
            RegisteredRootRunner.run("hma.force_stop", mapOf("package" to pkg), "hma")
        }
        // Also fallback to any packages directly found by pm
        val pmResult = RegisteredRootRunner.run("hma.discover.packages", callerTag = "hma")
        val pmOut = (pmResult as? RootResult.Completed)?.output
        if (!pmOut.isNullOrBlank()) {
            pmOut.lines().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("package:")) {
                    val p = line.removePrefix("package:").trim()
                    if (RootParamPolicy.isHmaPackage(p)) {
                        RegisteredRootRunner.run("hma.force_stop", mapOf("package" to p), "hma")
                    }
                }
            }
        }
    }

    private fun checkScopeBlockReason(
        scopeObj: JSONObject,
        templates: JSONObject,
        moduleSignatures: Set<String>
    ): String? {
        val useWhitelist = scopeObj.optBoolean("useWhitelist", false)
        val extraAppList = scopeObj.optJSONArray("extraAppList")?.toStringSet() ?: emptySet()
        val extraOppositeAppList = scopeObj.optJSONArray("extraOppositeAppList")?.toStringSet() ?: emptySet()

        if (useWhitelist) {
            // Whitelist mode: if module is in opposite list, it is hidden/blocked
            if (extraOppositeAppList.any { it in moduleSignatures }) {
                return "Hidden via Whitelist mode (in invisible apps list)"
            }

            // Check if any applied whitelist template allows the module
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            var allowedByTemplate = false
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()
                if (isTemplateWhitelist && templateApps.any { it in moduleSignatures }) {
                    allowedByTemplate = true
                    break
                }
            }

            // In Whitelist mode, if the module is NOT explicitly allowed (neither in extraAppList nor in a whitelist template), it is hidden!
            val allowedInExtra = extraAppList.any { it in moduleSignatures }
            if (!allowedInExtra && !allowedByTemplate) {
                return "Hidden via Whitelist mode (module not included in visible apps)"
            }
        } else {
            // Blacklist mode: if module is in extraAppList, it is hidden/blocked
            if (extraAppList.any { it in moduleSignatures }) {
                return "Hidden via Blacklist mode (in additional apps to hide)"
            }

            // Check applied blacklist templates
            val appliedTemplates = scopeObj.optJSONArray("applyTemplates")?.toStringSet() ?: emptySet()
            for (templateName in appliedTemplates) {
                val template = templates.optJSONObject(templateName) ?: continue
                val isTemplateWhitelist = template.optBoolean("isWhitelist", false)
                val templateApps = template.optJSONArray("appList")?.toStringSet() ?: emptySet()

                if (!isTemplateWhitelist && templateApps.any { it in moduleSignatures }) {
                    return "Hidden by blacklist template \"$templateName\""
                }
            }

            // Check presets (e.g. "xposed" preset hides all Xposed modules / manager)
            val appliedPresets = scopeObj.optJSONArray("applyPresets")?.toStringSet() ?: emptySet()
            if (appliedPresets.any { it.equals("xposed", ignoreCase = true) }) {
                return "Hidden by HMA \"xposed\" preset"
            }
        }

        return null
    }

    private fun JSONArray.toStringSet(): Set<String> {
        val set = mutableSetOf<String>()
        for (i in 0 until length()) {
            optString(i, null)?.let { set.add(it) }
        }
        return set
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
