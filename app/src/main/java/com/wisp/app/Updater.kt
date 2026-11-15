package com.wisp.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from this repo's GitHub Releases.
 *
 * CI already publishes a signed APK per build, all signed with the same key,
 * so installing the newest one over the current app keeps contacts and
 * settings. This just finds it, downloads the right file for this phone's CPU,
 * and hands it to Android's package installer. No keys involved: the repo is
 * public, so the release files are too.
 */
object Updater {

    private const val TAG = "WispUpdate"
    private const val REPO = "ferasbusiness666/wisp"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ACTION_STATUS = "com.wisp.app.UPDATE_STATUS"

    data class Release(
        val version: String,
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long
    )

    sealed interface Check {
        data class Available(val release: Release) : Check
        object UpToDate : Check
        data class Failed(val reason: String) : Check
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    suspend fun check(): Check = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Wisp-Android")
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    // A private repo answers 404 to anyone not signed in.
                    response.code == 404 -> Check.Failed("no public releases found")
                    !response.isSuccessful -> Check.Failed("GitHub answered ${response.code}")
                    else -> {
                        val json = JSONObject(response.body?.string().orEmpty())
                        val version = json.optString("tag_name").removePrefix("v")
                        val asset = pickAsset(json.optJSONArray("assets") ?: JSONArray())
                        when {
                            asset == null -> Check.Failed("the latest release has no APK")
                            !isNewer(version, currentVersion) -> Check.UpToDate
                            else -> Check.Available(
                                Release(
                                    version = version,
                                    apkUrl = asset.getString("browser_download_url"),
                                    apkName = asset.getString("name"),
                                    sizeBytes = asset.optLong("size")
                                )
                            )
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "update check failed", error)
            Check.Failed(error.message ?: "couldn't reach GitHub")
        }
    }

    /** The smallest APK that runs on this phone: arm64, then arm32, then universal. */
    private fun pickAsset(assets: JSONArray): JSONObject? {
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk") }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val preferred = when {
            abi.startsWith("arm64") -> "-arm64.apk"
            abi.startsWith("armeabi") -> "-arm32.apk"
            else -> "-universal.apk"
        }
        return apks.firstOrNull { it.optString("name").endsWith(preferred) }
            ?: apks.firstOrNull { it.optString("name").endsWith("-universal.apk") }
            ?: apks.firstOrNull()
    }

    /** Compares versions number by number: 1.0.40 is newer than 1.0.35. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, release.apkName)
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("User-Agent", "Wisp-Android")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("download failed (${response.code})")
                val body = response.body ?: error("empty download")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            }
            file
        }

    /** Android asks once per app before it may install other APKs. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Hands the APK to the system installer. Android shows its own "Update
     * this app?" prompt; on Android 12+ later updates can go through without
     * it once Wisp has installed itself once.
     */
    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("wisp.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer's answer: show its confirm screen, or report a failure. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { context.startActivity(it) } }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("WispUpdate", "install failed: $message")
                Toast.makeText(
                    context,
                    context.getString(R.string.update_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
