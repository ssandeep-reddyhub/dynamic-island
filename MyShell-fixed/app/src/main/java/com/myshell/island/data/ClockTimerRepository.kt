package com.myshell.island.data

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A timer or stopwatch that is running in the phone's own Clock app. */
data class ClockTimer(
    val key: String,
    val pkg: String,
    val isStopwatch: Boolean,
    /** Countdown: the moment it ends. Stopwatch: the moment it started. 0 when only [staticMs] is known. */
    val anchorMs: Long,
    /** Fallback when the Clock app gives no chronometer: the time shown in its notification. */
    val staticMs: Long = 0L,
    /** The user pressed Stop / Pause in the Clock app: the time is frozen at [staticMs]. */
    val paused: Boolean = false,
) {
    /** True while the display has to keep ticking (a frozen / paused value needs no update loop). */
    val running: Boolean get() = !paused && anchorMs != 0L

    fun displayMs(now: Long): Long = when {
        paused || anchorMs == 0L -> staticMs
        isStopwatch -> (now - anchorMs).coerceAtLeast(0L)
        else -> (anchorMs - now).coerceAtLeast(0L)
    }
}

/**
 * Watches the Clock app's ongoing notification (via notification access) so a running timer or
 * stopwatch can be shown in the island without opening the Clock app. Nothing is stored.
 */
class ClockTimerRepository {
    private val items = MutableStateFlow<Map<String, ClockTimer>>(emptyMap())
    private val _current = MutableStateFlow<ClockTimer?>(null)
    val current: StateFlow<ClockTimer?> = _current.asStateFlow()

    private val _all = MutableStateFlow<List<ClockTimer>>(emptyList())
    /** Every timer / stopwatch the Clock app is showing (a timer and a stopwatch can run together). */
    val all: StateFlow<List<ClockTimer>> = _all.asStateFlow()

    private fun publish() {
        val list = items.value.values.sortedByDescending { it.running }
        _all.value = list
        _current.value = list.firstOrNull()
    }

    fun onPosted(sbn: StatusBarNotification) {
        if (!isClockPackage(sbn.packageName)) return
        val n = sbn.notification ?: return
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) { onRemoved(sbn.key); return }

        val ex = n.extras
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE), ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).joinToString(" ").lowercase()
        val mentionsTimer = "timer" in text || "countdown" in text
        val mentionsStopwatch = "stopwatch" in text
        // An upcoming-alarm notice is not a timer.
        if ("alarm" in text && !mentionsTimer && !mentionsStopwatch && !ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            onRemoved(sbn.key); return
        }

        // The Clock app's own buttons tell the real state: "Pause/Stop" while it runs, "Resume/Start" once stopped.
        val actionTitles = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }
        val hasStop = actionTitles.any { "pause" in it || "stop" in it }
        val hasResume = actionTitles.any { "resume" in it || "start" in it || "continue" in it }
        val pausedByAction = hasResume && !hasStop

        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val down = Build.VERSION.SDK_INT >= 24 && ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val parsed = TIME.find(text)?.let { m ->
            val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val c = m.groupValues[3]
            if (c.isEmpty()) (a * 60 + b) * 1000 else ((a * 60 + b) * 60 + c.toLong()) * 1000
        }

        val timer = when {
            // Stopped in the Clock app: freeze the shown time (never restart it from the notification's clock).
            pausedByAction && parsed != null ->
                ClockTimer(sbn.key, sbn.packageName, isStopwatch = !down && !mentionsTimer, anchorMs = 0L, staticMs = parsed, paused = true)
            chrono && n.`when` > 0 && !pausedByAction -> ClockTimer(sbn.key, sbn.packageName, isStopwatch = !down, anchorMs = n.`when`)
            parsed != null && (mentionsTimer || mentionsStopwatch) -> {
                // No chronometer (some OEM clocks): anchor the time shown at the moment it was posted so the
                // island keeps counting by itself between the Clock app's updates. "Paused" stays frozen.
                val stopwatch = mentionsStopwatch && !mentionsTimer
                val paused = pausedByAction || "pause" in text
                val anchor = when {
                    paused -> 0L
                    stopwatch -> sbn.postTime - parsed
                    else -> sbn.postTime + parsed
                }
                ClockTimer(sbn.key, sbn.packageName, isStopwatch = stopwatch, anchorMs = anchor, staticMs = parsed, paused = paused)
            }
            else -> null
        }
        if (timer == null) onRemoved(sbn.key) else { items.value = items.value + (sbn.key to timer); publish() }
    }

    fun onRemoved(key: String) {
        if (key in items.value) { items.value = items.value - key; publish() }
    }

    fun clear() { items.value = emptyMap(); publish() }

    fun find(stopwatch: Boolean): ClockTimer? = _all.value.firstOrNull { it.isStopwatch == stopwatch }

    private companion object {
        val TIME = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
        val CLOCK_PACKAGES = setOf(
            "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
            "com.oneplus.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock", "com.miui.clock",
            "com.android.alarmclock", "com.huawei.deskclock", "com.motorola.timeweatherwidget",
        )
        fun isClockPackage(p: String) = p in CLOCK_PACKAGES || p.endsWith(".deskclock") || p.contains("clockpackage")
    }
}
