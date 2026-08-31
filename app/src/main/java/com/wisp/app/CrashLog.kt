package com.wisp.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the stack trace of a fatal crash so it can be shown on next launch.
 *
 * Wisp is sideloaded, so there is no crash reporting service and no practical
 * way for someone holding the phone to read a stack trace. Keeping the last one
 * and offering it as copyable text is the difference between "it crashed" and a
 * fixable bug report.
 */
object CrashLog {

    private const val KEY = "last_crash"
    private const val KEY_STEP = "last_step"
    private const val KEY_IN_PROGRESS = "step_in_progress"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            // Always hand back to the platform handler so the process still dies
            // the way Android expects it to.
            previous?.uncaughtException(thread, error)
        }
    }

    // commit(), not apply(): the process is about to die, and apply() writes in
    // the background — the report would be lost exactly when it matters.
    @SuppressLint("ApplySharedPref")
    private fun save(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        val report = buildString {
            appendLine("Wisp crash - $when_")
            appendLine("app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("thread ${thread.name}")
            appendLine()
            append(stack.toString())
        }

        Prefs.of(context).edit().putString(KEY, report).commit()
    }

    /**
     * Records how far call setup got. A crash inside the native WebRTC library
     * never reaches an uncaught-exception handler, so the Java stack trace above
     * would be empty; the last breadcrumb is then the only thing that says which
     * step the process died on.
     */
    @SuppressLint("ApplySharedPref")
    fun step(context: Context, step: String) {
        Prefs.of(context).edit()
            .putString(KEY_STEP, step)
            .putBoolean(KEY_IN_PROGRESS, true)
            .commit()
    }

    /** Call setup finished — cleanly or not — so the breadcrumb is no longer live. */
    fun stepDone(context: Context) {
        Prefs.of(context).edit().putBoolean(KEY_IN_PROGRESS, false).apply()
    }

    /**
     * Non-null when the last run died partway through call setup: the app was
     * mid-step and never reported finishing.
     */
    fun unfinishedStep(context: Context): String? {
        val prefs = Prefs.of(context)
        if (!prefs.getBoolean(KEY_IN_PROGRESS, false)) return null
        return prefs.getString(KEY_STEP, null)
    }

    fun clearStep(context: Context) {
        Prefs.of(context).edit().putBoolean(KEY_IN_PROGRESS, false).apply()
    }

    /**
     * Why the process died last time, straight from the system.
     *
     * A crash inside a native library produces no Java stack trace, so this is
     * the only way to tell a real native crash apart from the system killing
     * the app for memory or an ANR. Android keeps this record from API 30; on
     * older releases there is nothing to read.
     */
    fun lastExitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull().orEmpty()
        val exit = exits.firstOrNull() ?: return null

        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_CRASH -> "app crash"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "out of memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too many resources"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            ApplicationExitInfo.REASON_OTHER -> "other"
            else -> "reason code ${exit.reason}"
        }
        return buildString {
            append("last exit: ").append(reason)
            append(" (status ").append(exit.status).append(")")
            exit.description?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
        }
    }

    fun lastCrash(context: Context): String? =
        Prefs.of(context).getString(KEY, null)

    fun clear(context: Context) {
        Prefs.of(context).edit().remove(KEY).apply()
    }
}
