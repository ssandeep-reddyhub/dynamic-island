package com.myshell.island.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.myshell.island.data.BatteryInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Real device readings. Battery and Wi-Fi are push-based (broadcast / network callback), so they cost
 * nothing while idle. RAM is sampled only while the System tab is visible.
 *
 * Android limit: the Wi-Fi network name (SSID) needs location permission, which this app deliberately does not request.
 */
class SystemMonitor(private val context: Context) {

    val battery: Flow<BatteryInfo> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(parse(i)) }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { trySend(parse(it)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged().conflate()

    private fun parse(i: Intent): BatteryInfo {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(if (level >= 0 && scale > 0) level * 100 / scale else 0, charging)
    }

    /** Ringer mode: AudioManager.RINGER_MODE_SILENT / VIBRATE / NORMAL. Emits the current value first. */
    val ringer: Flow<Int> = callbackFlow {
        val am = context.getSystemService(AudioManager::class.java)
        trySend(am.ringerMode)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(am.ringerMode) }
        }
        context.registerReceiver(r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
        awaitClose { context.unregisterReceiver(r) }
    }.distinctUntilChanged().conflate()

    /** Do Not Disturb on/off, from the public interruption-filter broadcast. */
    val dnd: Flow<Boolean> = callbackFlow {
        val nm = context.getSystemService(NotificationManager::class.java)
        fun on() = nm.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        trySend(on())
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(on()) }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(r) }
    }.distinctUntilChanged().conflate()

    /**
     * Mobile hotspot on/off. Android has no public API for this: the (hidden) system broadcast is used where the
     * phone still sends it. If it never arrives the island simply never shows a hotspot state.
     */
    val hotspot: Flow<Boolean> = callbackFlow {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val initial = runCatching {
            WifiManager::class.java.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean
        }.getOrNull() ?: false
        trySend(initial)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val state = i.getIntExtra("wifi_state", 11)
                trySend(state == 13 || state == 12) // WIFI_AP_STATE_ENABLED / ENABLING
            }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(r) }
    }.distinctUntilChanged().conflate()

    /**
     * A Bluetooth device connected. Emits its name, or "" when Android hides it (the name needs the optional
     * Bluetooth permission on Android 12+). Android only delivers this broadcast once that permission is granted.
     */
    val bluetooth: Flow<String> = callbackFlow {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val name = try { device?.name.orEmpty() } catch (e: SecurityException) { "" }
                trySend(name)
            }
        }
        ContextCompat.registerReceiver(
            context, r, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { context.unregisterReceiver(r) }
    }.conflate()
}
