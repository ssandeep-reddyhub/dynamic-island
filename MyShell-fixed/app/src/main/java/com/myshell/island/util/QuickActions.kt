package com.myshell.island.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Android does not let normal apps flip Wi-Fi / hotspot / Bluetooth directly, so each control opens the
 * system's own panel or screen (where the switch is one tap away). Several fallbacks per OEM.
 */
object QuickActions {
    enum class Kind { WIFI, HOTSPOT, BLUETOOTH, QUICK_SHARE }

    fun open(ctx: Context, kind: Kind): Boolean {
        val pm = ctx.packageManager
        val intents: List<Intent> = when (kind) {
            Kind.WIFI -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else null,
                Intent(Settings.ACTION_WIFI_SETTINGS),
            )
            Kind.HOTSPOT -> listOf(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
            )
            Kind.BLUETOOTH -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            Kind.QUICK_SHARE -> listOfNotNull(
                pm.getLaunchIntentForPackage("com.samsung.android.app.sharelive"),
                Intent().setClassName("com.google.android.gms", "com.google.android.gms.nearby.sharing.ReceiveSurfaceActivity"),
                Intent(Settings.ACTION_SETTINGS),
            )
        }
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun bluetoothOn(ctx: Context): Boolean =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", 0) == 1 }.getOrDefault(false)
}
