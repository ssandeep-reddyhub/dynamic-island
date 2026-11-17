package com.myshell.island.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myshell.island.R
import com.myshell.island.appContainer
import com.myshell.island.data.AppInfo
import com.myshell.island.data.IslandCommand
import com.myshell.island.data.HistoryItem
import com.myshell.island.data.SettingKeys
import com.myshell.island.data.ShellSettings
import com.myshell.island.service.OverlayService
import com.myshell.island.ui.island.AppIcon
import com.myshell.island.ui.theme.Ink
import com.myshell.island.ui.theme.MyShellTheme
import com.myshell.island.util.Permissions
import com.myshell.island.util.relativeTime
import com.myshell.island.ui.island.ActivityKind
import com.myshell.island.ui.island.LayoutPreset
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm by viewModels<MainViewModel> { VMFactory(appContainer, this) }

    /** Bumped on resume so permission rows re-check after the user returns from Android settings. */
    private val resumes = mutableIntStateOf(0)

    private val postNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes.intValue++ }

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumes.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val st by vm.settings.collectAsState()
            val dark = when (st.theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            MyShellTheme(dark) {
                AppRoot(
                    vm = vm,
                    st = st,
                    resumes = resumes.intValue,
                    onRequestPostNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) postNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onRequestBluetooth = {
                        if (Build.VERSION.SDK_INT >= 31) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        if (vm.settings.value.enabled) OverlayService.startIfAllowed(this)
        appContainer.media.start()
    }
}

private enum class Page(val label: String, val icon: Int) {
    Home("Home", R.drawable.ic_island),
    History("History", R.drawable.ic_bell),
    Settings("Settings", R.drawable.ic_sliders),
}

@Composable
private fun AppRoot(vm: MainViewModel, st: ShellSettings, resumes: Int, onRequestPostNotifications: () -> Unit, onRequestBluetooth: () -> Unit) {
    var page by remember { mutableStateOf(Page.Home) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { p ->
                    NavigationBarItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = { Icon(painterResource(p.icon), null, Modifier.height(22.dp)) },
                        label = { Text(p.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
            when (page) {
                Page.Home -> HomeScreen(vm, st, resumes, onRequestPostNotifications, onRequestBluetooth)
                Page.History -> HistoryScreen(vm)
                Page.Settings -> SettingsScreen(vm, st)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home: master switch + permission checklist
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeScreen(vm: MainViewModel, st: ShellSettings, resumes: Int, onRequestPostNotifications: () -> Unit, onRequestBluetooth: () -> Unit) {
    val ctx = LocalContext.current
    val overlay = remember(resumes) { Permissions.overlayGranted(ctx) }
    val access = remember(resumes) { Permissions.notificationAccessGranted(ctx) }
    val post = remember(resumes) { Permissions.postNotificationsGranted(ctx) }
    val battery = remember(resumes) { Permissions.batteryUnrestricted(ctx) }
    val bluetooth = remember(resumes) { Permissions.bluetoothGranted(ctx) }
    val lockScreen = remember(resumes) { Permissions.lockScreenServiceEnabled(ctx) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("MyShell Island", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(
                "A floating Dynamic Island for Android: real notifications, music and shortcuts above every app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic Island", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !st.enabled -> "Off"
                                !overlay -> "Needs overlay permission"
                                else -> "Running"
                            },
                            color = if (st.enabled && overlay) Ink.Green else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = st.enabled, onCheckedChange = {
                        vm.set(SettingKeys.ENABLED, it)
                        if (it) OverlayService.startIfAllowed(ctx) else OverlayService.stop(ctx)
                    })
                }
            }
        }
        item { Text("Setup", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
        item {
            PermRow(
                "Display over other apps", "Lets the island float above every app.",
                overlay, "Grant", required = true,
            ) { Permissions.openOverlaySettings(ctx) }
        }
        item {
            PermRow(
                "Notification access", "Reads real notifications and your media sessions. Everything stays on this phone.",
                access, "Grant", required = true,
            ) { Permissions.openNotificationAccess(ctx) }
        }
        if (!access) {
            item {
                Text(
                    "Installed from an APK and the switch is greyed out? Android 13+ blocks it until you open App info, " +
                        "tap ⋮ and choose \"Allow restricted settings\".",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton({ Permissions.openAppInfo(ctx) }) { Text("Open App info") }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            item {
                PermRow(
                    "Notifications", "Lets the app show its \"island is running\" status.",
                    post, "Allow", required = false, onClick = onRequestPostNotifications,
                )
            }
        }
        item {
            PermRow(
                "Show on lock screen",
                "Optional. Turn on \"MyShell Island\" in Accessibility so the island (and music) stays visible when the phone is locked. " +
                    "If the switch is greyed out: App info → ⋮ → Allow restricted settings. MyShell never reads your screen.",
                lockScreen, "Turn on", required = false,
            ) { Permissions.openAccessibilitySettings(ctx) }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            item {
                PermRow(
                    "Bluetooth devices", "Optional. Lets the island show when headphones or a watch connect, with its name.",
                    bluetooth, "Allow", required = false, onClick = onRequestBluetooth,
                )
            }
        }
        item {
            PermRow(
                "Unrestricted battery", "Optional. On Samsung One UI also add MyShell to Battery → Never sleeping apps so it isn't stopped.",
                battery, "Open", required = false,
            ) { Permissions.openBatterySettings(ctx) }
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, action: String, required: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (granted) "Granted" else if (required) "Required" else "Optional",
                    fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (granted) Ink.Green else if (required) Ink.Red else Ink.Orange,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) OutlinedButton(onClick) { Text("Open") } else Button(onClick) { Text(action) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

@Composable
private fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notification history", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton({ vm.clearHistory() }, enabled = history.isNotEmpty()) { Text("Clear") }
        }
        if (history.isEmpty()) {
            Text(
                "Nothing yet. Notifications received by the island are listed here, stored only on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { h -> HistoryRow(h) }
            }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryItem) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            AppIcon(h.pkg, 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.appName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (h.title.isNotBlank()) Text(h.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (h.text.isNotBlank()) Text(h.text, maxLines = 3, fontSize = 13.sp)
                if (h.title.isBlank() && h.text.isBlank()) Text("Content hidden", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(relativeTime(h.postedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(vm: MainViewModel, st: ShellSettings) {
    var showApps by remember { mutableStateOf(false) }
    val apps by produceState(emptyList<AppInfo>(), showApps) { if (showApps) value = vm.apps() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) }

        // --- live preview ------------------------------------------------------------------------
        item {
            SettingsCard("Preview", "Everything below applies instantly. Tap a button to see the island in that state.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.preview(IslandCommand.Open) }, Modifier.weight(1f)) { Text("Open") }
                    OutlinedButton({ vm.preview(IslandCommand.Alert) }, Modifier.weight(1f)) { Text("Alert") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.preview(IslandCommand.Charging) }, Modifier.weight(1f)) { Text("Charging") }
                    OutlinedButton({ vm.preview(IslandCommand.Silent) }, Modifier.weight(1f)) { Text("Silent") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.preview(IslandCommand.LowBattery) }, Modifier.weight(1f)) { Text("Low battery") }
                    OutlinedButton({ vm.preview(IslandCommand.Bluetooth) }, Modifier.weight(1f)) { Text("Bluetooth") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.preview(IslandCommand.Hotspot) }, Modifier.weight(1f)) { Text("Hotspot") }
                    OutlinedButton({ vm.preview(IslandCommand.Dnd) }, Modifier.weight(1f)) { Text("Do Not Disturb") }
                }
            }
        }

        // --- display mode ------------------------------------------------------------------------
        item {
            SettingsCard("Display mode", "Only one is active at a time. Each mode keeps its own size, position, look and speed, and the switch is animated.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !st.isNotch, onClick = { vm.set(SettingKeys.MODE, "island") },
                        label = { Text("Dynamic Island") }, modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = st.isNotch, onClick = { vm.set(SettingKeys.MODE, "notch") },
                        label = { Text("Notch") }, modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    if (st.isNotch) "Editing the Notch. Switch to Dynamic Island to edit the pill."
                    else "Editing the Dynamic Island. Switch to Notch to edit the notch.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (st.isNotch) {
            item {
                SettingsCard("Notch size", "Width and height of the notch around your camera.") {
                    SliderRow("Width", signed(st.nIdleW), st.nIdleW.toFloat(), -10f..140f) { vm.set(SettingKeys.N_IDLE_W, it.toInt()) }
                    SliderRow("Height", signed(st.nIdleH), st.nIdleH.toFloat(), -4f..30f) { vm.set(SettingKeys.N_IDLE_H, it.toInt()) }
                    SliderRow("Active height", signed(st.nPillH), st.nPillH.toFloat(), -4f..20f) { vm.set(SettingKeys.N_PILL_H, it.toInt()) }
                    SliderRow("Expansion size", "${st.nExpandW} dp", st.nExpandW.toFloat(), 260f..400f) { vm.set(SettingKeys.N_EXPAND_W, it.toInt()) }
                    SliderRow("Corner radius", "${st.nRadius} dp", st.nRadius.toFloat(), 0f..60f) { vm.set(SettingKeys.N_RADIUS, it.toInt()) }
                }
            }
            item {
                SettingsCard("Notch position", "Move the notch if it does not sit exactly on your camera.") {
                    SliderRow("Horizontal", signed(st.nOffX), st.nOffX.toFloat(), -120f..120f) { vm.set(SettingKeys.N_OFF_X, it.toInt()) }
                    SliderRow("Vertical", signed(st.nOffY), st.nOffY.toFloat(), -10f..40f) { vm.set(SettingKeys.N_OFF_Y, it.toInt()) }
                }
            }
            item {
                SettingsCard("Notch look") {
                    ColorRow(st.nColor) { vm.set(SettingKeys.N_COLOR, it) }
                    SliderRow("Transparency", "${((1f - st.nAlpha) * 100).toInt()}%", 1f - st.nAlpha, 0f..0.8f) { vm.set(SettingKeys.N_ALPHA, 1f - it) }
                    SliderRow("Animation speed", String.format(Locale.ROOT, "%.1fx", st.nSpeed), st.nSpeed, 0.5f..2f) { vm.set(SettingKeys.N_SPEED, it) }
                    TextButton({ vm.resetNotch() }) { Text("Reset notch") }
                }
            }
        } else {
        // --- size --------------------------------------------------------------------------------
        item {
            SettingsCard("Size", "Pick a preset, then fine-tune below.") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayoutPreset.entries.forEach { p ->
                        FilterChip(
                            selected = p.matches(st),
                            onClick = { vm.applyPreset(p) },
                            label = { Text(p.label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                SliderRow("Open width", "${st.widthDp} dp", st.widthDp.toFloat(), 240f..360f) { vm.set(SettingKeys.WIDTH_DP, it.toInt()) }
                SliderRow("Open height", "${(st.panelScale * 100).toInt()}%", st.panelScale, 0.8f..1.3f) { vm.set(SettingKeys.PANEL_SCALE, it) }
                SliderRow("Idle width", signed(st.idleW), st.idleW.toFloat(), -12f..60f) { vm.set(SettingKeys.IDLE_W, it.toInt()) }
                SliderRow("Idle height", signed(st.idleH), st.idleH.toFloat(), -8f..24f) { vm.set(SettingKeys.IDLE_H, it.toInt()) }
                SliderRow("Active pill width", "${(st.pillScale * 100).toInt()}%", st.pillScale, 0.6f..1.5f) { vm.set(SettingKeys.PILL_SCALE, it) }
                SliderRow("Active pill height", signed(st.pillH), st.pillH.toFloat(), -8f..16f) { vm.set(SettingKeys.PILL_H, it.toInt()) }
            }
        }

        // --- position ----------------------------------------------------------------------------
        item {
            SettingsCard("Position", "Move the island if it does not sit exactly on your camera.") {
                SliderRow("Horizontal", signed(st.offsetX), st.offsetX.toFloat(), -120f..120f) { vm.set(SettingKeys.OFFSET_X, it.toInt()) }
                SliderRow("Vertical", signed(st.offsetY), st.offsetY.toFloat(), -30f..60f) { vm.set(SettingKeys.OFFSET_Y, it.toInt()) }
                TextButton({ vm.resetLayout() }) { Text("Reset size and position") }
            }
        }

        // --- animation & gestures ------------------------------------------------------------------
        item {
            SettingsCard("Animation and gestures") {
                SliderRow("Animation speed", String.format(Locale.ROOT, "%.1fx", st.animSpeed), st.animSpeed, 0.5f..2f) { vm.set(SettingKeys.ANIM_SPEED, it) }
                SliderRow("Gesture sensitivity", String.format(Locale.ROOT, "%.1fx", st.gestureSensitivity), st.gestureSensitivity, 0.5f..2f) { vm.set(SettingKeys.GESTURE, it) }
                Text(
                    "Tap or swipe down on the island: open\n" +
                        "Swipe left / right when open: next / previous tab\n" +
                        "Swipe up when open: close\n" +
                        "Swipe a notification banner away: dismiss it",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

            item {
                SettingsCard("Island look") {
                    SliderRow("Corner radius", if (st.iRadius == 0) "Auto" else "${st.iRadius} dp", st.iRadius.toFloat(), 0f..30f) { vm.set(SettingKeys.I_RADIUS, it.toInt()) }
                    ColorRow(st.iColor) { vm.set(SettingKeys.I_COLOR, it) }
                    SliderRow("Transparency", "${((1f - st.iAlpha) * 100).toInt()}%", 1f - st.iAlpha, 0f..0.8f) { vm.set(SettingKeys.I_ALPHA, 1f - it) }
                }
            }
        }

        // --- smart event management --------------------------------------------------------------
        item {
            SettingsCard(
                "Smart Event Management",
                "When several things are active (music, timer, notifications ...) the island shows one and keeps the others running behind it.",
            ) {
                SwitchRow("Smart Event Management", "Rank what is active and switch between it. Off: always the fixed order below.", st.smartOn) { vm.set(SettingKeys.SMART_ON, it) }
                val order = ActivityKind.parseOrder(st.priority)
                Text("Priority - the top one is shown first", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                order.forEachIndexed { i, k ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(k.icon), null, Modifier.size(18.dp), tint = k.color)
                        Spacer(Modifier.width(10.dp))
                        Text("${i + 1}. ${k.label}", Modifier.weight(1f))
                        TextButton({ vm.movePriority(k, -1) }, enabled = i > 0) { Text("▲") }
                        TextButton({ vm.movePriority(k, 1) }, enabled = i < order.lastIndex) { Text("▼") }
                    }
                }
                TextButton({ vm.resetPriority() }) { Text("Reset order") }
                if (st.smartOn) {
                    Text("Show first when active", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = st.defaultKind == "auto", onClick = { vm.set(SettingKeys.DEFAULT_KIND, "auto") }, label = { Text("Highest priority") })
                        ActivityKind.entries.forEach { k ->
                            FilterChip(selected = st.defaultKind == k.name, onClick = { vm.set(SettingKeys.DEFAULT_KIND, k.name) }, label = { Text(k.label) })
                        }
                    }
                    SwitchRow("Secondary indicators", "Tiny dots on the island for other active things.", st.secondaryDots) { vm.set(SettingKeys.SECONDARY_DOTS, it) }
                    Text("Switching between active things", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !st.autoSwitch, onClick = { vm.set(SettingKeys.AUTO_SWITCH, false) }, label = { Text("Manual") })
                        FilterChip(selected = st.autoSwitch, onClick = { vm.set(SettingKeys.AUTO_SWITCH, true) }, label = { Text("Automatic") })
                    }
                    if (st.autoSwitch) {
                        SliderRow("Show each for", "${st.switchSeconds} s", st.switchSeconds.toFloat(), 3f..20f) { vm.set(SettingKeys.SWITCH_SECONDS, it.toInt()) }
                    }
                    Text(
                        "Manual: swipe the island left or right (or tap an entry in the Active tab) to bring another active thing forward. " +
                            "It returns to your default after 15 seconds.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- island states -----------------------------------------------------------------------
        item {
            SettingsCard("Island states") {
                SwitchRow("Charging animation", "Show battery level when you plug in the charger.", st.chargeOn) { vm.set(SettingKeys.CHARGE_ON, it) }
                if (st.chargeOn) SliderRow("Charging shows for", "${st.chargeSeconds} s", st.chargeSeconds.toFloat(), 1f..8f) { vm.set(SettingKeys.CHARGE_SECONDS, it.toInt()) }
                SwitchRow("Silent / vibrate / ring", "Briefly show when the sound mode changes.", st.ringerOn) { vm.set(SettingKeys.RINGER_ON, it) }
                if (st.ringerOn) SliderRow("Sound mode shows for", "${st.ringerSeconds} s", st.ringerSeconds.toFloat(), 1f..6f) { vm.set(SettingKeys.RINGER_SECONDS, it.toInt()) }
                SwitchRow("Low battery warning", "Show a warning when the battery drops to the level below.", st.lowBatteryOn) { vm.set(SettingKeys.LOW_BATTERY_ON, it) }
                if (st.lowBatteryOn) SliderRow("Warn at", "${st.lowBatteryPercent}%", st.lowBatteryPercent.toFloat(), 5f..50f) { vm.set(SettingKeys.LOW_BATTERY_PERCENT, it.toInt()) }
                SwitchRow("Do Not Disturb status", "Briefly show when the phone's Do Not Disturb turns on.", st.dndOn) { vm.set(SettingKeys.DND_ON, it) }
                SwitchRow("Bluetooth connected", "Briefly show when a device connects (needs the Bluetooth permission on Android 12+).", st.btOn) { vm.set(SettingKeys.BT_ON, it) }
                SwitchRow("Hotspot", "Show hotspot status. Only works on phones that report it to apps.", st.hotspotOn) { vm.set(SettingKeys.HOTSPOT_ON, it) }
                SwitchRow("Screen recording", "Show a red recording timer while the screen recorder runs.", st.recordingOn) { vm.set(SettingKeys.RECORDING_ON, it) }
                SwitchRow("Navigation", "Show the next turn from Google Maps or other navigation apps.", st.navOn) { vm.set(SettingKeys.NAV_ON, it) }
                SwitchRow("Music pill", "Show album art and equaliser while music plays.", st.musicPill) { vm.set(SettingKeys.MUSIC_PILL, it) }
                SwitchRow("Clock timer pill", "Show a running Clock-app timer / stopwatch.", st.timerPill) { vm.set(SettingKeys.TIMER_PILL, it) }
            }
        }

        // --- notifications -----------------------------------------------------------------------
        item {
            SettingsCard("Notifications") {
                SwitchRow("Quiet mode", "Collect notifications silently, without animating the island.", st.dnd) { vm.set(SettingKeys.DND, it) }
                SwitchRow("Hide message previews", "Show only who it is from, never the message text.", st.hidePreviews) { vm.set(SettingKeys.HIDE_PREVIEWS, it) }
                SwitchRow("Island sound", "Play a short chime when the island announces a notification.", st.notifSound) { vm.set(SettingKeys.NOTIF_SOUND, it) }
                TextButton({ showApps = !showApps }) { Text(if (showApps) "Hide per-app controls" else "Per-app controls") }
                if (showApps) {
                    apps.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.pkg, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, Modifier.weight(1f), maxLines = 1)
                            Switch(checked = a.pkg !in st.disabledPackages, onCheckedChange = { vm.setPackageEnabled(a.pkg, it) })
                        }
                    }
                }
            }
        }

        // --- system ------------------------------------------------------------------------------
        item {
            SettingsCard("General") {
                SwitchRow("Start on boot", "Restart the island after the phone reboots.", st.startOnBoot) { vm.set(SettingKeys.START_ON_BOOT, it) }
                Text("App theme", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (k, label) ->
                        FilterChip(selected = st.theme == k, onClick = { vm.set(SettingKeys.THEME, k) }, label = { Text(label) })
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun signed(v: Int) = if (v > 0) "+$v dp" else "$v dp"

/** Background colour choices for the island / notch. */
@Composable
private fun ColorRow(current: Int, onPick: (Int) -> Unit) {
    val colors = listOf(0xFF000000, 0xFF1C1C1E, 0xFF0A1A3A, 0xFF2A0A12, 0xFF0A2216).map { it.toInt() }
    Column {
        Text("Background colour")
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colors.forEach { c ->
                androidx.compose.foundation.layout.Box(
                    Modifier.size(34.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Color(c))
                        .border(
                            if (c == current) 2.dp else 1.dp,
                            if (c == current) Ink.Blue else MaterialTheme.colorScheme.outline,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                        .clickable { onPick(c) }
                )
            }
        }
    }
}

/** A titled group of settings. */
@Composable
private fun SettingsCard(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Ink.Blue)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChecked)
    }
}

/** Slider that updates the island while you drag (saved shortly after each pause), not only on release. */
@Composable
private fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, onCommit: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging) v = value }
    LaunchedEffect(v, dragging) {
        if (dragging) {
            delay(60)
            onCommit(v)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(title, Modifier.weight(1f))
            Text(valueLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = v,
            onValueChange = { dragging = true; v = it },
            onValueChangeFinished = { dragging = false; onCommit(v) },
            valueRange = range,
        )
    }
}
