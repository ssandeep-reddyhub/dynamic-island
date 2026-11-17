package com.myshell.island.data

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A screen recording that is running right now. [stop] is only set when the recorder offers a Stop action. */
data class RecordingInfo(val key: String, val startMs: Long, val stop: PendingIntent?)

/** Turn-by-turn guidance taken from a navigation app's ongoing notification. */
data class NavInfo(val key: String, val pkg: String, val primary: String, val secondary: String)

/** The phone's own "hotspot is on" notification. [clients] is the connected-device count when the text gives one. */
data class HotspotInfo(val key: String, val clients: Int?)

/**
 * Live activities that Android only exposes through other apps' *ongoing* notifications (screen recorder,
 * navigation). Nothing is invented: if no app posts such a notification, nothing is shown. Nothing is stored.
 */
class LiveActivityRepository {
    private val _recording = MutableStateFlow<RecordingInfo?>(null)
    val recording: StateFlow<RecordingInfo?> = _recording.asStateFlow()

    private val _navigation = MutableStateFlow<NavInfo?>(null)
    val navigation: StateFlow<NavInfo?> = _navigation.asStateFlow()

    private val _hotspot = MutableStateFlow<HotspotInfo?>(null)
    val hotspot: StateFlow<HotspotInfo?> = _hotspot.asStateFlow()

    /** True while a phone call is ringing or in progress (taken from the dialer's call notification). */
    private val _call = MutableStateFlow<String?>(null)
    val call: StateFlow<String?> = _call.asStateFlow()

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.category == Notification.CATEGORY_CALL) {
            _call.value = sbn.key
            return
        }
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val pkg = sbn.packageName

        // Android has no public "connected devices" API, but the system hotspot notification usually says it
        // ("2 devices connected"). Read that instead of guessing; if it is not there, only the icon is shown.
        val hay = "$title $text ${ex.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: ""}".lowercase()
        val systemish = pkg == "android" || pkg.startsWith("com.android.") || pkg.startsWith("com.samsung.android.") ||
            pkg.contains("tether") || pkg.contains("networkstack")
        if (systemish && ("hotspot" in hay || "tether" in hay)) {
            _hotspot.value = HotspotInfo(sbn.key, clientCount(hay))
            return
        }

        if (n.category == Notification.CATEGORY_NAVIGATION || pkg in NAV_PACKAGES) {
            if (title.isNotBlank() || text.isNotBlank()) {
                _navigation.value = NavInfo(sbn.key, pkg, title.ifBlank { text }, if (title.isBlank()) "" else text)
            }
            return
        }

        if (looksLikeRecorder(pkg, n, title, text)) {
            val prev = _recording.value
            if (prev?.key == sbn.key) return // already running: keep the original start time
            val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0
            val stop = n.actions.orEmpty().firstOrNull { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                "stop" in t || "end" in t
            }?.actionIntent
            _recording.value = RecordingInfo(sbn.key, if (chrono) n.`when` else sbn.postTime, stop)
        }
    }

    private fun clientCount(hay: String): Int? {
        CLIENTS_BEFORE.find(hay)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        CLIENTS_AFTER.find(hay)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return if ("no device" in hay || "no client" in hay || "0 device" in hay) 0 else null
    }

    fun onRemoved(key: String) {
        if (_call.value == key) _call.value = null
        if (_hotspot.value?.key == key) _hotspot.value = null
        if (_recording.value?.key == key) _recording.value = null
        if (_navigation.value?.key == key) _navigation.value = null
    }

    fun clear() {
        _call.value = null
        _hotspot.value = null
        _recording.value = null
        _navigation.value = null
    }

    private fun looksLikeRecorder(pkg: String, n: Notification, title: String, text: String): Boolean {
        val trusted = pkg == "com.android.systemui" || pkg.contains("screenrecord") ||
            pkg.contains("screenrec") || pkg.contains("smartcapture") || pkg.contains("screen_record")
        if (!trusted) return false
        val hay = "$title $text ${n.channelId.orEmpty()}".lowercase()
        return "record" in hay || "capture" in hay
    }

    private companion object {
        val CLIENTS_BEFORE = Regex("""(\d+)\s*(?:connected|(?:connected\s+)?(?:device|client|user|people))""")
        val CLIENTS_AFTER = Regex("""(?:connected|devices?|clients?)\D{0,14}(\d+)""")
        val NAV_PACKAGES = setOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "net.osmand", "app.organicmaps")
    }
}
