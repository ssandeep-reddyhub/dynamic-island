package com.myshell.island.ui.island

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myshell.island.R
import com.myshell.island.data.AppInfo
import com.myshell.island.data.BatteryInfo
import com.myshell.island.data.NotifAction
import com.myshell.island.data.ShellNotification
import com.myshell.island.di.AppContainer
import com.myshell.island.service.NotificationActions
import com.myshell.island.ui.ClipboardCaptureActivity
import com.myshell.island.ui.theme.Ink
import com.myshell.island.util.Permissions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

enum class IslandTab(val color: Color, @DrawableRes val icon: Int, val label: String, val contentHeight: Dp) {
    Notifications(Ink.Blue, R.drawable.ic_bell, "Notifications", 250.dp),
    Music(Ink.Pink, R.drawable.ic_music, "Music", 172.dp),
    Apps(Ink.Purple, R.drawable.ic_apps, "Apps", 176.dp),
    Clipboard(Ink.Cyan, R.drawable.ic_clipboard, "Clipboard", 220.dp),
    /** Only exists while two or more things are active: a list of them (Smart Event Management). */
    Live(Ink.Orange, R.drawable.ic_island, "Active", 196.dp),
}

/** Short-lived "live activity" events that briefly widen the island, like iPhone. */
enum class IslandEvent { Charging, Silent, Vibrate, Ring, LowBattery, Hotspot, Bluetooth, Dnd }

/** Size of the camera hole / notch in dp, so the idle island can wrap exactly around it. */
data class CutoutDp(val widthDp: Float, val heightDp: Float, val centerYDp: Float)

/** UI state + intents for the floating island. Business logic lives in the repositories. */
class IslandViewModel(private val c: AppContainer, private val appContext: Context) : ViewModel() {

    data class Ui(
        val expanded: Boolean = false,
        val tab: IslandTab = IslandTab.Apps,
        /** The notification currently announced in the compact "alert" state. */
        val alert: ShellNotification? = null,
        /** How many more are waiting behind the alert. */
        val queued: Int = 0,
        val event: IslandEvent? = null,
        /** Extra text for the event (e.g. the Bluetooth device name). */
        val eventText: String? = null,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val _cutout = MutableStateFlow<CutoutDp?>(null)
    val cutout: StateFlow<CutoutDp?> = _cutout.asStateFlow()

    val settings = c.settings.state
    val notifications = c.notifications.active
    val unread = c.notifications.unread
    val music = c.media.state
    val clockTimer = c.clock.current
    val clockAll = c.clock.all
    val mediaAccess = c.media.hasAccess
    val pinned = c.shortcuts.pinned
    val recents = c.shortcuts.recents
    val clips = c.clipboard.items
    /** One shared battery listener for the whole island (the UI and the event logic read this same flow). */
    val battery: StateFlow<BatteryInfo> = c.system.battery.stateIn(viewModelScope, SharingStarted.Eagerly, BatteryInfo())
    private val batteryState get() = battery
    val recording = c.live.recording
    val call = c.live.call
    val navigation = c.live.navigation
    private val hotspotBroadcast = c.system.hotspot.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val hotspotNotice = c.live.hotspot
    /** On when either the system broadcast or the phone's own hotspot notification says so. */
    val hotspot: StateFlow<Boolean> = combine(hotspotBroadcast, hotspotNotice) { b, n -> b || n != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    /** Connected devices, when Android exposes it (via the hotspot notification); null = unknown. */
    val hotspotClients: StateFlow<Int?> = hotspotNotice.map { it?.clients }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val icons = c.icons

    /** The Music tab only exists while a media session is active (something is playing or paused). */
    /** The Active tab only exists while two or more things are active. */
    val tabs: StateFlow<List<IslandTab>> by lazy {
        combine(music, activeKinds, settings) { m, active, st ->
            IslandTab.entries.filter {
                it != IslandTab.Notifications && (it != IslandTab.Music || m != null) && (it != IslandTab.Live || (st.smartOn && active.size >= 2))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, IslandTab.entries.filter { it != IslandTab.Notifications && it != IslandTab.Music && it != IslandTab.Live })
    }

    // --- Smart Event Management ---------------------------------------------------------------
    // Several things can be active at once. They are ranked by the user's priority list; only the best one is
    // shown in the pill. Nothing is ever stopped because it is not shown: it simply stays in the background.

    private data class Flags(val music: Boolean, val timer: Boolean, val stopwatch: Boolean, val notifications: Boolean, val charging: Boolean)

    private val flags = combine(music, clockAll, unread, batteryState, settings) { m, clocks, u, b, s ->
        Flags(
            music = s.musicPill && m?.playing == true,
            timer = s.timerPill && clocks.any { !it.isStopwatch },
            stopwatch = s.timerPill && clocks.any { it.isStopwatch },
            notifications = false, // notifications are announced briefly, then the island returns to normal
            charging = s.chargeOn && b.charging,
        )
    }

    /** Everything active right now, best first. */
    val activeKinds: StateFlow<List<ActivityKind>> = combine(flags, hotspot, c.live.recording, c.live.navigation, settings) { f, hot, rec, nav, s ->
        ActivityKind.parseOrder(s.priority).filter { k ->
            when (k) {
                ActivityKind.Music -> f.music
                ActivityKind.Timer -> f.timer
                ActivityKind.Stopwatch -> f.stopwatch
                ActivityKind.Notifications -> f.notifications
                ActivityKind.Charging -> f.charging
                ActivityKind.Hotspot -> s.hotspotOn && hot
                ActivityKind.Recording -> s.recordingOn && rec != null
                ActivityKind.Navigation -> s.navOn && nav != null
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val manualKind = MutableStateFlow<ActivityKind?>(null)
    private val autoKind = MutableStateFlow<ActivityKind?>(null)
    private var manualJob: kotlinx.coroutines.Job? = null

    /** The activity the pill shows right now (null = idle). */
    val shownKind: StateFlow<ActivityKind?> = combine(activeKinds, settings, manualKind, autoKind) { active, s, manual, auto ->
        when {
            active.isEmpty() -> null
            !s.smartOn -> active.first()
            manual != null && manual in active -> manual
            s.autoSwitch && auto != null && auto in active -> auto
            else -> ActivityKind.fromName(s.defaultKind)?.takeIf { it in active } ?: active.first()
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Small indicators for the activities that are running but not shown. */
    val secondaryKinds: StateFlow<List<ActivityKind>> = combine(activeKinds, shownKind, settings) { active, shown, s ->
        if (s.smartOn && s.secondaryDots) active.filter { it != shown }.take(2) else emptyList()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Swipe on the pill: show the next / previous active thing. Goes back to the default after a while. */
    fun cycleActivity(dir: Int) {
        val s = settings.value
        val active = activeKinds.value
        if (!s.smartOn || active.size < 2) return
        val i = active.indexOf(shownKind.value).coerceAtLeast(0)
        holdManual(active[(i + dir + active.size) % active.size])
    }

    private fun holdManual(k: ActivityKind) {
        manualKind.value = k
        manualJob?.cancel()
        manualJob = viewModelScope.launch {
            delay(MANUAL_HOLD_MS)
            manualKind.value = null
        }
    }

    /** Tap on the pill: open what the pill is showing (music controls, notifications, the Clock app, maps ...). */
    fun openShown() = openActivity(shownKind.value)

    fun openActivity(k: ActivityKind?) {
        when (k) {
            ActivityKind.Music -> expand(IslandTab.Music)
            ActivityKind.Notifications -> expand(IslandTab.Notifications)
            ActivityKind.Timer -> openClockApp(false)
            ActivityKind.Stopwatch -> openClockApp(true)
            ActivityKind.Navigation -> c.live.navigation.value?.pkg?.let { launch(it) } ?: expand()
            else -> expand()
        }
    }

    /** Opens the panel on the tab that matches what the pill shows. */
    fun expandShown() {
        when (shownKind.value) {
            ActivityKind.Music -> expand(IslandTab.Music)
            ActivityKind.Notifications -> expand(IslandTab.Notifications)
            else -> expand()
        }
    }

    /** In the Active list: show this one in the pill. */
    fun pinKind(k: ActivityKind) = holdManual(k)

    private val queue = Channel<ShellNotification>(Channel.UNLIMITED)
    private val waiting = AtomicInteger(0)
    private val dismissSignal = Channel<Unit>(Channel.CONFLATED)
    private var lastTab = IslandTab.Apps
    private var eventJob: kotlinx.coroutines.Job? = null

    init {
        // New notification -> queue it (unless the panel is open: then the tab badge is enough).
        viewModelScope.launch {
            c.notifications.events.collect { n -> enqueueAlert(n) }
        }
        // Show queued alerts one after another, then fall back to the compact state.
        // Music keeps playing underneath; the alert never touches playback.
        viewModelScope.launch {
            for (n in queue) {
                waiting.decrementAndGet()
                if (_ui.value.expanded) continue
                dismissSignal.tryReceive()
                _ui.update { it.copy(alert = n, queued = waiting.get().coerceAtLeast(0)) }
                if (settings.value.notifSound) c.sounds.playNotification()
                // Shown for the configured time, or until the user swipes it away.
                withTimeoutOrNull(ALERT_MS) { dismissSignal.receive() }
                _ui.update { if (it.alert?.key == n.key) it.copy(alert = null) else it }
                delay(350)
            }
        }
        // Plugging in the charger -> charging pill; battery dropping to the warning level -> low-battery pill.
        viewModelScope.launch {
            var prev: Boolean? = null
            var prevPct = -1
            battery.collect { b ->
                val s = settings.value
                if (prev == false && b.charging && s.chargeOn) showEvent(IslandEvent.Charging, s.chargeSeconds)
                val limit = s.lowBatteryPercent
                if (s.lowBatteryOn && !b.charging && b.percent in 1..limit && prevPct > limit) {
                    showEvent(IslandEvent.LowBattery, 4)
                }
                prev = b.charging
                prevPct = b.percent
            }
        }
        // Do Not Disturb switched on.
        viewModelScope.launch {
            var prev: Boolean? = null
            c.system.dnd.collect { on ->
                if (prev != null && prev != on && on && settings.value.dndOn) showEvent(IslandEvent.Dnd, 2)
                prev = on
            }
        }
        // Hotspot switched on.
        viewModelScope.launch {
            var prev: Boolean? = null
            hotspotBroadcast.collect { on ->
                if (prev != null && prev != on && on && settings.value.hotspotOn) showEvent(IslandEvent.Hotspot, 3)
                prev = on
            }
        }
        // A Bluetooth device connected.
        viewModelScope.launch {
            c.system.bluetooth.collect { name ->
                if (settings.value.btOn) showEvent(IslandEvent.Bluetooth, 3, name)
            }
        }
        // Ringer switched between sound / vibrate / silent -> brief pill.
        viewModelScope.launch {
            var prev: Int? = null
            c.system.ringer.collect { r ->
                val s = settings.value
                if (prev != null && prev != r && s.ringerOn) {
                    showEvent(
                        when (r) {
                            android.media.AudioManager.RINGER_MODE_SILENT -> IslandEvent.Silent
                            android.media.AudioManager.RINGER_MODE_VIBRATE -> IslandEvent.Vibrate
                            else -> IslandEvent.Ring
                        },
                        s.ringerSeconds,
                    )
                }
                prev = r
            }
        }
        // Preview buttons in the settings app.
        viewModelScope.launch {
            c.commands.collect { cmd ->
                when (cmd) {
                    com.myshell.island.data.IslandCommand.Open -> expand()
                    com.myshell.island.data.IslandCommand.Alert -> enqueueAlert(
                        ShellNotification(
                            key = "preview-" + System.nanoTime(), pkg = appContext.packageName, appName = "MyShell Island",
                            title = "Preview", sender = null, text = "This is how a notification looks.",
                            postedAt = System.currentTimeMillis(), hidden = false,
                        )
                    )
                    com.myshell.island.data.IslandCommand.Charging -> showEvent(IslandEvent.Charging, settings.value.chargeSeconds)
                    com.myshell.island.data.IslandCommand.Silent -> showEvent(IslandEvent.Silent, settings.value.ringerSeconds)
                    com.myshell.island.data.IslandCommand.LowBattery -> showEvent(IslandEvent.LowBattery, 4)
                    com.myshell.island.data.IslandCommand.Bluetooth -> showEvent(IslandEvent.Bluetooth, 3, "Headphones")
                    com.myshell.island.data.IslandCommand.Hotspot -> showEvent(IslandEvent.Hotspot, 3)
                    com.myshell.island.data.IslandCommand.Dnd -> showEvent(IslandEvent.Dnd, 2)
                }
            }
        }
        // Automatic switching: rotate through the active things every few seconds. One loop, only while it is needed.
        viewModelScope.launch {
            combine(activeKinds, settings) { a, st -> a to st }.collectLatest { (active, st) ->
                if (st.smartOn && st.autoSwitch && active.size > 1) {
                    var i = 0
                    autoKind.value = active[0]
                    while (true) {
                        delay(st.switchSeconds.coerceIn(2, 30) * 1000L)
                        i = (i + 1) % active.size
                        autoKind.value = active[i]
                    }
                } else {
                    autoKind.value = null
                }
            }
        }
        // A tab that no longer exists (Music ended, the Active list shrank) -> back to Notifications.
        viewModelScope.launch {
            tabs.collect { list -> if (_ui.value.tab !in list) selectTab(IslandTab.Apps) }
        }
        // Music ended while its tab was showing -> move to Notifications.
        viewModelScope.launch {
            music.collect { m ->
                if (m == null && _ui.value.tab == IslandTab.Music) selectTab(IslandTab.Apps)
                if (m == null && lastTab == IslandTab.Music) lastTab = IslandTab.Apps
            }
        }
    }

    private fun enqueueAlert(n: ShellNotification) {
        if (_ui.value.expanded) return
        // A burst of notifications never builds a long backlog: only the newest few wait their turn.
        while (waiting.get() >= 3 && queue.tryReceive().isSuccess) waiting.decrementAndGet()
        waiting.incrementAndGet()
        _ui.update { it.copy(queued = waiting.get() - if (it.alert != null) 0 else 1) }
        queue.trySend(n)
    }

    private fun showEvent(e: IslandEvent, seconds: Int, text: String? = null) {
        if (_ui.value.expanded) return
        eventJob?.cancel()
        _ui.update { it.copy(event = e, eventText = text) }
        eventJob = viewModelScope.launch {
            delay(seconds.coerceIn(1, 10) * 1000L)
            _ui.update { if (it.event == e) it.copy(event = null, eventText = null) else it }
        }
    }

    /** Swipe on the alert banner: hide it now (the next queued one follows). */
    fun dismissAlert() {
        if (_ui.value.alert != null) dismissSignal.trySend(Unit)
    }

    fun setCutout(value: CutoutDp?) {
        _cutout.value = value
    }

    // --- island state -------------------------------------------------------------------------

    fun expand(tab: IslandTab? = null) {
        drainQueue()
        val wanted = tab ?: lastTab
        val target = if (wanted in tabs.value) wanted else IslandTab.Apps
        _ui.update { it.copy(expanded = true, tab = target, alert = null, queued = 0, event = null, eventText = null) }
    }

    fun collapse() {
        if (!_ui.value.expanded) return
        lastTab = _ui.value.tab
        _ui.update { it.copy(expanded = false) }
    }

    fun selectTab(t: IslandTab) = _ui.update { it.copy(tab = t) }

    fun next() = step(1)
    fun previous() = step(-1)

    private fun step(d: Int) {
        val all = tabs.value
        val j = all.indexOf(_ui.value.tab) + d
        if (j in all.indices) selectTab(all[j])
    }

    private fun drainQueue() {
        while (queue.tryReceive().isSuccess) { /* discard */ }
        waiting.set(0)
    }

    // --- notifications ------------------------------------------------------------------------

    fun markRead() = c.notifications.markAllRead()
    fun dismiss(key: String) = c.notifications.dismiss(key)
    fun dismissPackage(pkg: String) = c.notifications.dismissPackage(pkg)
    fun clearAll() = c.notifications.clearAll()

    fun open(n: ShellNotification) {
        NotificationActions.open(appContext, n)
        collapse()
    }

    fun runAction(a: NotifAction) {
        NotificationActions.run(appContext, a)
    }

    fun reply(n: ShellNotification, text: String): Boolean = NotificationActions.reply(appContext, n, text)

    fun openNotificationAccess() = Permissions.openNotificationAccess(appContext)

    // --- music --------------------------------------------------------------------------------

    fun playPause() = c.media.playPause()
    fun nextTrack() = c.media.next()
    fun previousTrack() = c.media.previous()
    fun seek(ms: Long) = c.media.seekTo(ms)
    fun openPlayer() {
        c.media.openPlayer()
        collapse()
    }

    // --- shortcuts ----------------------------------------------------------------------------

    suspend fun allApps(): List<AppInfo> = c.shortcuts.installedApps()
    fun togglePin(pkg: String) = c.shortcuts.togglePin(pkg)
    fun unpin(pkg: String) = c.shortcuts.unpin(pkg)
    fun move(pkg: String, delta: Int) = c.shortcuts.move(pkg, delta)

    fun launch(pkg: String) {
        val intent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        c.shortcuts.recordLaunch(pkg)
        collapse()
    }

    /** Only offered when the recorder's own notification has a Stop action. */
    fun stopRecording() {
        c.live.recording.value?.stop?.let { runCatching { it.send() } }
    }

    /** Opens the Clock app for the timer ([stopwatch] = false) or the stopwatch ([stopwatch] = true). */
    fun openClockApp(stopwatch: Boolean? = null) {
        val item = stopwatch?.let { c.clock.find(it) } ?: clockTimer.value
        val pkg = item?.pkg ?: return
        val i = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching { appContext.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        collapse()
    }

    // --- clipboard ----------------------------------------------------------------------------

    fun copyAgain(text: String) {
        val cm = appContext.getSystemService(ClipboardManager::class.java)
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("MyShell", text)) }
    }

    fun togglePinClip(id: Long) = c.clipboard.togglePin(id)
    fun deleteClip(id: Long) = c.clipboard.delete(id)
    fun clearClips() = c.clipboard.clearUnpinned()

    /** Android only releases the clipboard to the focused app, so briefly open a transparent activity. */
    fun captureClipboard() {
        appContext.startActivity(
            Intent(appContext, ClipboardCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        collapse()
    }

    private companion object {
        const val MANUAL_HOLD_MS = 15_000L
        /** A notification is announced for exactly this long, then the island collapses again. */
        const val ALERT_MS = 2000L
    }
}
