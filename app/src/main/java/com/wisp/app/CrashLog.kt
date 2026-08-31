package com.wisp.app

import android.annotation.SuppressLint
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

    fun lastCrash(context: Context): String? =
        Prefs.of(context).getString(KEY, null)

    fun clear(context: Context) {
        Prefs.of(context).edit().remove(KEY).apply()
    }
}
