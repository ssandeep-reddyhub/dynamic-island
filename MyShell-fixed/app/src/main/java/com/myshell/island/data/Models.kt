package com.myshell.island.data

import android.app.PendingIntent
import android.app.RemoteInput

/** One action offered by the source app's notification (e.g. "Mark as read", or an inline reply). */
data class NotifAction(
    val title: String,
    val intent: PendingIntent?,
    val remoteInput: RemoteInput? = null,
)

/** Sent from the settings app to the running island (preview buttons). */
enum class IslandCommand { Open, Alert, Charging, Silent, LowBattery, Bluetooth, Hotspot, Dnd }

/** A live notification captured from another app. PendingIntents only exist in memory. */
data class ShellNotification(
    val key: String,
    val pkg: String,
    val appName: String,
    val title: String,
    val sender: String?,
    val text: String,
    val postedAt: Long,
    /** True when the source marked it secret / private-on-lockscreen: content was dropped. */
    val hidden: Boolean,
    val unread: Boolean = true,
    val contentIntent: PendingIntent? = null,
    val actions: List<NotifAction> = emptyList(),
    val reply: NotifAction? = null,
)

/** Persisted (text-only) record used for notification history. */
data class HistoryItem(
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

data class AppInfo(val pkg: String, val label: String)

data class ClipItem(val id: Long, val text: String, val pinned: Boolean, val time: Long)

data class MusicState(
    val pkg: String,
    val appName: String,
    val title: String,
    val artist: String,
    val art: android.graphics.Bitmap?,
    val playing: Boolean,
    val positionMs: Long,
    val updateTime: Long,
    val speed: Float,
    val durationMs: Long,
    val canPrev: Boolean,
    val canNext: Boolean,
)

data class BatteryInfo(val percent: Int = 0, val charging: Boolean = false)

enum class WifiState { OFF, DISCONNECTED, CONNECTED }

data class RamInfo(val usedMb: Long, val totalMb: Long) {
    val fraction: Float get() = if (totalMb > 0) usedMb.toFloat() / totalMb else 0f
}

