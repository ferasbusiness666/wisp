package com.wisp.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The one-time nags a call app has to get through on modern Android: runtime
 * permissions, the battery-optimization exemption, the separate full-screen
 * intent grant on 14+, and the OEM background-killer settings.
 */
object SetupFlow {

    private const val KEY_BATTERY_ASKED = "battery_asked"
    private const val KEY_FSI_ASKED = "fsi_asked"

    fun callPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    fun hasCallPermissions(context: Context): Boolean =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missingPermissions(context: Context): Array<String> =
        callPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /**
     * Runs through the optional grants once. Each dialog is shown at most one
     * time so re-opening the app is not an interrogation.
     */
    fun runOnce(
        activity: AppCompatActivity,
        settingsLauncher: ActivityResultLauncher<Intent>
    ) {
        if (maybeAskBatteryExemption(activity, settingsLauncher)) return
        if (maybeAskFullScreenIntent(activity, settingsLauncher)) return
        maybeAskOem(activity)
    }

    private fun maybeAskBatteryExemption(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<Intent>
    ): Boolean {
        val prefs = Prefs.of(activity)
        if (prefs.getBoolean(KEY_BATTERY_ASKED, false)) return false
        val power = activity.getSystemService(PowerManager::class.java) ?: return false
        if (power.isIgnoringBatteryOptimizations(activity.packageName)) return false

        prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                @Suppress("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${activity.packageName}"))
                runCatching { launcher.launch(intent) }.onFailure {
                    runCatching {
                        launcher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
        return true
    }

    private fun maybeAskFullScreenIntent(
        activity: AppCompatActivity,
        launcher: ActivityResultLauncher<Intent>
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val prefs = Prefs.of(activity)
        if (prefs.getBoolean(KEY_FSI_ASKED, false)) return false
        if (Notifications.canUseFullScreenIntent(activity)) return false

        prefs.edit().putBoolean(KEY_FSI_ASKED, true).apply()
        AlertDialog.Builder(activity)
            .setTitle(R.string.fsi_title)
            .setMessage(R.string.fsi_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${activity.packageName}"))
                runCatching { launcher.launch(intent) }
                    .onFailure { OemHelper.openAppInfo(activity) }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
        return true
    }

    private fun maybeAskOem(activity: AppCompatActivity) {
        if (OemHelper.alreadyPrompted(activity)) return
        val advice = OemHelper.advice() ?: return
        OemHelper.markPrompted(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.oem_title)
            .setMessage(advice.explanationRes)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                OemHelper.openSettings(activity, advice)
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }
}
