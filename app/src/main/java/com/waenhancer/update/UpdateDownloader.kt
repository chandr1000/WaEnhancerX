package com.waenhancer.update

import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import com.waenhancer.root.RegisteredRootRunner
import com.waenhancer.root.RootFlowConsent
import com.waenhancer.root.RootParamPolicy
import com.waenhancer.root.RootResult

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit

object UpdateDownloader {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    /** Reject corrupt, partial, and unrelated packages before offering installation. */
    private fun isValidModuleApk(context: Context, file: File, expectedVersion: String? = null): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return runCatching {
            val validZip = ZipFile(file).use { archive ->
                archive.getEntry("AndroidManifest.xml") != null && archive.getEntry("classes.dex") != null
            }
            if (!validZip) return@runCatching false
            @Suppress("DEPRECATION")
            val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            packageInfo?.packageName == context.packageName &&
                (expectedVersion == null || normalizeVersion(packageInfo.versionName.orEmpty())
                    .equals(normalizeVersion(expectedVersion), ignoreCase = true))
        }.getOrDefault(false)
    }

    private fun shellQuote(value: String) = RootParamPolicy.shellQuote(value)
    interface DownloadCallback {
        fun onProgress(progress: Int, currentBytes: Long, totalBytes: Long)
        fun onSuccess(apkFile: File)
        fun onFailure(error: Exception)
    }

    fun hasRootAccess(): Boolean {
        // R7 fix: use the shared ROOT_ID regex (no substring matching). The probe
        // is the registered root.probe.id entry; the result is a typed RootResult.
        val result = RegisteredRootRunner.run("root.probe.id", callerTag = "update")
        val output = (result as? RootResult.Completed)?.output
        return RootParamPolicy.reportsRootId(output)
    }

    fun downloadApk(
        context: Context, url: String, expectedVersion: String, callback: DownloadCallback,
        expectedSha256: String? = null, expectedSize: Long = 0L
    ): Call? {
        val request: Request
        val integrity: UpdateTransferPolicy
        try {
            request = Request.Builder().url(url).build()
            require(request.url.scheme == "https") { "Updates must use HTTPS" }
            integrity = UpdateTransferPolicy(expectedSha256, expectedSize)
        } catch (error: Exception) {
            callback.onFailure(IOException("Invalid update request", error))
            return null
        }
        // Same-version assets may be republished. Never trust a filename/version cache.
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback.onFailure(e)
            }
            override fun onResponse(call: Call, response: Response) {
                var staging: File? = null
                var handedOff = false
                try {
                    response.use {
                        if (call.isCanceled()) return
                        if (response.request.url.scheme != "https") throw IOException("Update redirect left HTTPS")
                        if (!response.isSuccessful) throw IOException("Update service returned HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty update download")
                        val total = body.contentLength()
                        if (total > UpdateTransferPolicy.MAX_BYTES) throw IOException("Update exceeds size limit")
                        if (expectedSize > 0 && total >= 0 && total != expectedSize) throw IOException("Selected asset size mismatch")
                        val file = File.createTempFile("waex_download_", ".apk", context.cacheDir)
                        staging = file
                        val progress = UpdateProgressGate()
                        body.byteStream().use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    if (call.isCanceled()) throw IOException("Download cancelled")
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    integrity.append(buffer, read)
                                    output.write(buffer, 0, read)
                                    if (progress.allow(SystemClock.elapsedRealtime())) {
                                        callback.onProgress(
                                            if (total > 0) (integrity.bytes * 100 / total).toInt().coerceIn(0, 99) else 0,
                                            integrity.bytes, total
                                        )
                                    }
                                }
                            }
                        }
                        if (call.isCanceled()) return
                        integrity.finish(total)
                        if (!isValidModuleApk(context, file, expectedVersion)) throw IOException("APK does not match selected version")
                        if (call.isCanceled()) return
                        callback.onProgress(100, integrity.bytes, total)
                        if (call.isCanceled()) return
                        // Unique final path: a later request cannot replace these verified bytes.
                        handedOff = true
                        callback.onSuccess(file)
                    }
                } catch (error: Exception) {
                    if (!handedOff && !call.isCanceled()) callback.onFailure(error)
                } finally {
                    if (!handedOff) staging?.delete()
                }
            }
        })
        return call
    }

    fun installApk(context: Context, apkFile: File, expectedVersion: String) {
        val activity = context.findActivity() ?: return
        if (!isValidModuleApk(activity, apkFile, expectedVersion)) {
            Toast.makeText(activity, UiText.get(R.string.waex_ui_apk_does_not_match_selected_version_1_2285cdd0, "APK does not match selected version %1\$s", expectedVersion), Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            Toast.makeText(activity, UiText.get(R.string.waex_ui_please_allow_waenhancer_x_to_install_a_cde29fd6, "Please allow WaEnhancer X to install apps"), Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun installApkWithRoot(context: Context, apkFile: File, expectedVersion: String) {
        val activity = context.findActivity() ?: return
        if (!isValidModuleApk(activity, apkFile, expectedVersion)) {
            Toast.makeText(activity, UiText.get(R.string.waex_ui_apk_does_not_match_selected_version_1_2285cdd0, "APK does not match selected version %1\$s", expectedVersion), Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            // Pre-flight consent: if the user disabled any step in the four-command
            // chain, surface the disabled step before touching /data/local/tmp.
            val chain = listOf("update.mktemp", "update.copy", "update.install", "update.cleanup")
            val preFlight = RootFlowConsent.preFlight(chain)
            if (!preFlight.allowed) {
                activity.runOnUiThread {
                    Toast.makeText(activity, UiText.get(R.string.rootop_toast_install_fallback, "Root install disabled by you — using the session installer"), Toast.LENGTH_LONG).show()
                }
                installApk(activity, apkFile, expectedVersion)
                return@Thread
            }
            var staging: String? = null
            val result: String? = try {
                run {
                    // Step 1: mktemp — staging path is a DERIVED_OUTPUT for the next steps.
                    val mktempResult = RegisteredRootRunner.run("update.mktemp", callerTag = "update")
                    val mktempPath = (mktempResult as? RootResult.Completed)?.output?.trim()
                    if (mktempPath == null) return@run "Unable to obtain a private installation temporary file"
                    staging = mktempPath
                    // Re-check immediately before the copy step (TOCTOU guard).
                    if (!RootFlowConsent.isStepEnabled("update.copy")) return@run "Step \"copy\" was skipped: disabled by you"

                    // Step 2: copy + verify byte count.
                    val copyParams = mapOf(
                        "source" to apkFile.absolutePath,
                        "target" to mktempPath
                    )
                    val copyResult = RegisteredRootRunner.run("update.copy", copyParams, "update")
                    val copyOutput = (copyResult as? RootResult.Completed)?.output?.trim()
                    val size = copyOutput?.toLongOrNull() ?: 0
                    if (size != apkFile.length() || size <= 1024) {
                        return@run if (copyOutput.isNullOrEmpty()) "Failed to copy verified APK to temporary storage"
                        else "Failed to copy verified APK: $copyOutput"
                    }
                    if (!RootFlowConsent.isStepEnabled("update.install")) return@run "Step \"install\" was skipped: disabled by you"

                    // Step 3: pm install.
                    val installParams = mapOf("target" to mktempPath)
                    val installResult = RegisteredRootRunner.run("update.install", installParams, "update")
                    (installResult as? RootResult.Completed)?.output?.trim()
                }
            } finally {
                // Step 4: cleanup (best-effort, runs in finally).
                staging?.let { path ->
                    if (RootFlowConsent.isStepEnabled("update.cleanup")) {
                        RegisteredRootRunner.run("update.cleanup", mapOf("target" to path), "update")
                    }
                }
            }
            val success = result?.startsWith("Success", ignoreCase = true) == true
            activity.runOnUiThread {
                if (success) {
                    Toast.makeText(activity, UiText.get(R.string.waex_ui_installation_successful_restarting_dbd45bac, "Installation successful. Restarting..."), Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 2000)
                } else Toast.makeText(
                    activity,
                    UiText.get(R.string.waex_ui_root_installation_failed_1_s_eb2c4718, "Root installation failed: %1\$s", result?.trim().orEmpty().ifEmpty { UiText.get(R.string.waex_ui_unable_to_execute_the_root_installer_798f1547, "Unable to execute the root installer") }),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
