package com.wisp.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Several OEMs kill background sockets no matter what the platform APIs say.
 * Each entry here is the settings screen where the user can exempt Wisp on that
 * manufacturer; if the deep link is missing on a particular firmware we fall
 * back to the app info page, which always exists.
 */
object OemHelper {

    private const val TAG = "WispOem"
    private const val KEY_PROMPTED = "oem_prompted"

    private val samsung = listOf(
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity"
    )

    private val huawei = listOf(
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
    )

    private val xiaomi = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"
    )

    /** Null when this device needs no special treatment. */
    fun advice(): Advice? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("samsung") -> Advice(R.string.oem_samsung, samsung)
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                Advice(R.string.oem_huawei, huawei)
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> Advice(R.string.oem_xiaomi, xiaomi)
            else -> null
        }
    }

    data class Advice(val explanationRes: Int, val targets: List<Pair<String, String>>)

    fun openSettings(context: Context, advice: Advice) {
        // Package visibility rules on Android 11+ make resolveActivity() useless
        // for other apps' components, so we just try each one and keep going
        // until a launch sticks.
        for ((pkg, cls) in advice.targets) {
            val intent = Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        Log.i(TAG, "no OEM deep link resolved; falling back to app info")
        openAppInfo(context)
    }

    fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "app info screen unavailable: ${it.message}") }
    }

    fun alreadyPrompted(context: Context): Boolean =
        Prefs.of(context).getBoolean(KEY_PROMPTED, false)

    fun markPrompted(context: Context) {
        Prefs.of(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }
}
