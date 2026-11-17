package com.myshell.island.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** mm:ss, h:mm:ss, optionally with centiseconds. */
fun formatClock(ms: Long, centis: Boolean = false): String {
    val t = ms.coerceAtLeast(0L)
    val h = t / 3_600_000
    val m = (t / 60_000) % 60
    val s = (t / 1000) % 60
    val c = (t % 1000) / 10
    val base = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
    return if (centis) base + String.format(Locale.ROOT, ".%02d", c) else base
}

fun relativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    val d = (now - ts) / 1000
    return when {
        d < 10 -> "now"
        d < 60 -> "${d}s"
        d < 3600 -> "${d / 60}m"
        d < 86_400 -> "${d / 3600}h"
        else -> "${d / 86_400}d"
    }
}

object Channels {
    const val SERVICE = "island_service"

    fun create(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE, "Island service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the Dynamic Island running above other apps"
                setShowBadge(false)
            }
        )
    }
}

/** Short notification chime and a timer alarm that stops itself. */
class SoundPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var alarm: Ringtone? = null
    private val stopRunnable = Runnable { stopAlarm() }

    fun playNotification() {
        runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    fun startAlarm(maxMs: Long = 12_000L) {
        main.post {
            stopAlarm()
            runCatching {
                val r = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    ?: return@runCatching
                r.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
                alarm = r
                main.postDelayed(stopRunnable, maxMs)
            }
        }
    }

    fun stopAlarm() {
        main.post {
            main.removeCallbacks(stopRunnable)
            runCatching { alarm?.stop() }
            alarm = null
        }
    }
}
