package com.myshell.island.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.myshell.island.service.ShellNotificationListener

/** Status checks and deep links into the Android settings screens the app depends on. */
object Permissions {

    fun overlayGranted(c: Context) = Settings.canDrawOverlays(c)

    fun notificationAccessGranted(c: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun postNotificationsGranted(c: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True when the user switched on the "MyShell Island" accessibility service (needed for the lock screen). */
    fun lockScreenServiceEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(c, com.myshell.island.service.IslandAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun openAccessibilitySettings(c: Context) {
        if (!start(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) openAppInfo(c)
    }

    fun bluetoothGranted(c: Context) =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun batteryUnrestricted(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun openOverlaySettings(c: Context) = start(
        c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
    )

    fun openNotificationAccess(c: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(c, ShellNotificationListener::class.java).flattenToString()
        )
        if (Build.VERSION.SDK_INT < 30 || !start(c, detail)) {
            start(c, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    fun openBatterySettings(c: Context) {
        if (!start(c, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppInfo(c)
    }

    /** Needed on Android 13+ for sideloaded APKs: "Allow restricted settings" lives here. */
    fun openAppInfo(c: Context) = start(
        c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
    )

    private fun start(c: Context, i: Intent): Boolean = try {
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
