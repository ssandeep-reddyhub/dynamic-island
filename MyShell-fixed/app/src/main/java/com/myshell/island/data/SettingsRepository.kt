package com.myshell.island.data

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsStore by preferencesDataStore("settings")

data class ShellSettings(
    val enabled: Boolean = true,
    val startOnBoot: Boolean = true,
    val hidePreviews: Boolean = false,
    val dnd: Boolean = false,
    val animSpeed: Float = 1f,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val widthDp: Int = 300,
    val notifSound: Boolean = false,
    val gestureSensitivity: Float = 1f,
    val theme: String = "system",
    val alertSeconds: Int = 5,
    val disabledPackages: Set<String> = emptySet(),
    // Per-state customisation
    val chargeOn: Boolean = true,
    val chargeSeconds: Int = 3,
    val ringerOn: Boolean = true,
    val ringerSeconds: Int = 2,
    val musicPill: Boolean = true,
    val timerPill: Boolean = true,
    val idleW: Int = 0,
    val idleH: Int = 0,
    val pillH: Int = 0,
    val panelScale: Float = 1f,
    val pillScale: Float = 1f,
    val lowBatteryOn: Boolean = true,
    val lowBatteryPercent: Int = 20,
    val recordingOn: Boolean = true,
    val navOn: Boolean = true,
    val dndOn: Boolean = true,
    val btOn: Boolean = true,
    val hotspotOn: Boolean = true,
    // Smart Event Management
    val smartOn: Boolean = true,
    /** Comma separated [com.myshell.island.ui.island.ActivityKind] names, highest priority first. */
    val priority: String = "Music,Timer,Stopwatch,Notifications,Charging,Hotspot,Recording,Navigation",
    /** "auto" = highest priority, otherwise an ActivityKind name that is shown first when it is active. */
    val defaultKind: String = "auto",
    val secondaryDots: Boolean = true,
    val autoSwitch: Boolean = false,
    val switchSeconds: Int = 6,
    // Display mode: "island" (pill) or "notch" (iPhone-style notch). Each has its own look and size settings.
    val mode: String = "island",
    // Dynamic Island look (size / position / speed use the settings above)
    val iColor: Int = 0xFF000000.toInt(),
    val iAlpha: Float = 1f,
    /** 0 = automatic (full pill); otherwise corner radius in dp for the resting and status pills. */
    val iRadius: Int = 0,
    // Notch: its own size, position, speed and look
    val nIdleW: Int = 0,
    val nIdleH: Int = 6,
    val nPillH: Int = 2,
    val nOffX: Int = 0,
    val nOffY: Int = 0,
    val nRadius: Int = 40,
    val nColor: Int = 0xFF000000.toInt(),
    val nAlpha: Float = 1f,
    val nSpeed: Float = 1f,
    val nExpandW: Int = 340,
) {
    val isNotch: Boolean get() = mode == "notch"

    /** The settings the island UI really uses: in Notch mode the notch's own size, position and speed replace the island's. */
    fun effective(): ShellSettings =
        if (isNotch) copy(widthDp = nExpandW, idleW = nIdleW, idleH = nIdleH, pillH = nPillH, offsetX = nOffX, offsetY = nOffY, animSpeed = nSpeed)
        else this

    val bgColor: Int get() = if (isNotch) nColor else iColor
    val bgAlpha: Float get() = if (isNotch) nAlpha else iAlpha
}

object SettingKeys {
    val ENABLED = booleanPreferencesKey("enabled")
    val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    val HIDE_PREVIEWS = booleanPreferencesKey("hide_previews")
    val DND = booleanPreferencesKey("dnd")
    val ANIM_SPEED = floatPreferencesKey("anim_speed")
    val OFFSET_X = intPreferencesKey("offset_x")
    val OFFSET_Y = intPreferencesKey("offset_y")
    val WIDTH_DP = intPreferencesKey("width_dp")
    val NOTIF_SOUND = booleanPreferencesKey("notif_sound")
    val GESTURE = floatPreferencesKey("gesture_sensitivity")
    val THEME = stringPreferencesKey("theme")
    val ALERT_SECONDS = intPreferencesKey("alert_seconds")
    val DISABLED_PKGS = stringSetPreferencesKey("disabled_pkgs")
    val CHARGE_ON = booleanPreferencesKey("charge_on")
    val CHARGE_SECONDS = intPreferencesKey("charge_seconds")
    val RINGER_ON = booleanPreferencesKey("ringer_on")
    val RINGER_SECONDS = intPreferencesKey("ringer_seconds")
    val MUSIC_PILL = booleanPreferencesKey("music_pill")
    val TIMER_PILL = booleanPreferencesKey("timer_pill")
    val IDLE_W = intPreferencesKey("idle_w")
    val IDLE_H = intPreferencesKey("idle_h")
    val PILL_H = intPreferencesKey("pill_h")
    val PANEL_SCALE = floatPreferencesKey("panel_scale")
    val PILL_SCALE = floatPreferencesKey("pill_scale")
    val LOW_BATTERY_ON = booleanPreferencesKey("low_battery_on")
    val LOW_BATTERY_PERCENT = intPreferencesKey("low_battery_percent")
    val RECORDING_ON = booleanPreferencesKey("recording_on")
    val NAV_ON = booleanPreferencesKey("nav_on")
    val DND_ON = booleanPreferencesKey("dnd_on")
    val BT_ON = booleanPreferencesKey("bt_on")
    val HOTSPOT_ON = booleanPreferencesKey("hotspot_on")
    val SMART_ON = booleanPreferencesKey("smart_on")
    val PRIORITY = stringPreferencesKey("priority")
    val DEFAULT_KIND = stringPreferencesKey("default_kind")
    val SECONDARY_DOTS = booleanPreferencesKey("secondary_dots")
    val AUTO_SWITCH = booleanPreferencesKey("auto_switch")
    val SWITCH_SECONDS = intPreferencesKey("switch_seconds")
    val LAYOUT_VERSION = intPreferencesKey("layout_version")
    val MODE = stringPreferencesKey("display_mode")
    val I_COLOR = intPreferencesKey("i_color")
    val I_ALPHA = floatPreferencesKey("i_alpha")
    val I_RADIUS = intPreferencesKey("i_radius")
    val N_IDLE_W = intPreferencesKey("n_idle_w")
    val N_IDLE_H = intPreferencesKey("n_idle_h")
    val N_PILL_H = intPreferencesKey("n_pill_h")
    val N_OFF_X = intPreferencesKey("n_off_x")
    val N_OFF_Y = intPreferencesKey("n_off_y")
    val N_RADIUS = intPreferencesKey("n_radius")
    val N_COLOR = intPreferencesKey("n_color")
    val N_ALPHA = floatPreferencesKey("n_alpha")
    val N_SPEED = floatPreferencesKey("n_speed")
    val N_EXPAND_W = intPreferencesKey("n_expand_w")
}

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    val flow: Flow<ShellSettings> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    /** Hot copy so non-UI code (the notification listener) can read the latest values synchronously. */
    val state: StateFlow<ShellSettings> = flow.stateIn(scope, SharingStarted.Eagerly, ShellSettings())

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    /**
     * The island got a smaller default size (v2). Sizes saved by older versions would hide that, so they
     * are reset once; the user's position is kept.
     */
    suspend fun migrate() {
        context.settingsStore.edit { p ->
            if ((p[SettingKeys.LAYOUT_VERSION] ?: 0) < 2) {
                p.remove(SettingKeys.WIDTH_DP)
                p.remove(SettingKeys.IDLE_W)
                p.remove(SettingKeys.IDLE_H)
                p.remove(SettingKeys.PILL_H)
                p.remove(SettingKeys.PILL_SCALE)
                p.remove(SettingKeys.PANEL_SCALE)
                p[SettingKeys.LAYOUT_VERSION] = 2
            }
            // v3: the notch now defaults to the small teardrop shape (narrow, big corner radius).
            if ((p[SettingKeys.LAYOUT_VERSION] ?: 0) < 3) {
                listOf(SettingKeys.N_IDLE_W, SettingKeys.N_IDLE_H, SettingKeys.N_PILL_H, SettingKeys.N_RADIUS).forEach { p.remove(it) }
                p[SettingKeys.LAYOUT_VERSION] = 3
            }
        }
    }

    suspend fun setPackageEnabled(pkg: String, enabled: Boolean) {
        context.settingsStore.edit { p ->
            val cur = p[SettingKeys.DISABLED_PKGS].orEmpty()
            p[SettingKeys.DISABLED_PKGS] = if (enabled) cur - pkg else cur + pkg
        }
    }

    private fun Preferences.toSettings(): ShellSettings {
        val d = ShellSettings()
        return ShellSettings(
            enabled = this[SettingKeys.ENABLED] ?: d.enabled,
            startOnBoot = this[SettingKeys.START_ON_BOOT] ?: d.startOnBoot,
            hidePreviews = this[SettingKeys.HIDE_PREVIEWS] ?: d.hidePreviews,
            dnd = this[SettingKeys.DND] ?: d.dnd,
            animSpeed = this[SettingKeys.ANIM_SPEED] ?: d.animSpeed,
            offsetX = this[SettingKeys.OFFSET_X] ?: d.offsetX,
            offsetY = this[SettingKeys.OFFSET_Y] ?: d.offsetY,
            widthDp = this[SettingKeys.WIDTH_DP] ?: d.widthDp,
            notifSound = this[SettingKeys.NOTIF_SOUND] ?: d.notifSound,
            gestureSensitivity = this[SettingKeys.GESTURE] ?: d.gestureSensitivity,
            theme = this[SettingKeys.THEME] ?: d.theme,
            alertSeconds = this[SettingKeys.ALERT_SECONDS] ?: d.alertSeconds,
            disabledPackages = this[SettingKeys.DISABLED_PKGS] ?: d.disabledPackages,
            chargeOn = this[SettingKeys.CHARGE_ON] ?: d.chargeOn,
            chargeSeconds = this[SettingKeys.CHARGE_SECONDS] ?: d.chargeSeconds,
            ringerOn = this[SettingKeys.RINGER_ON] ?: d.ringerOn,
            ringerSeconds = this[SettingKeys.RINGER_SECONDS] ?: d.ringerSeconds,
            musicPill = this[SettingKeys.MUSIC_PILL] ?: d.musicPill,
            timerPill = this[SettingKeys.TIMER_PILL] ?: d.timerPill,
            idleW = this[SettingKeys.IDLE_W] ?: d.idleW,
            idleH = this[SettingKeys.IDLE_H] ?: d.idleH,
            pillH = this[SettingKeys.PILL_H] ?: d.pillH,
            panelScale = this[SettingKeys.PANEL_SCALE] ?: d.panelScale,
            pillScale = this[SettingKeys.PILL_SCALE] ?: d.pillScale,
            lowBatteryOn = this[SettingKeys.LOW_BATTERY_ON] ?: d.lowBatteryOn,
            lowBatteryPercent = this[SettingKeys.LOW_BATTERY_PERCENT] ?: d.lowBatteryPercent,
            recordingOn = this[SettingKeys.RECORDING_ON] ?: d.recordingOn,
            navOn = this[SettingKeys.NAV_ON] ?: d.navOn,
            dndOn = this[SettingKeys.DND_ON] ?: d.dndOn,
            btOn = this[SettingKeys.BT_ON] ?: d.btOn,
            hotspotOn = this[SettingKeys.HOTSPOT_ON] ?: d.hotspotOn,
            smartOn = this[SettingKeys.SMART_ON] ?: d.smartOn,
            priority = this[SettingKeys.PRIORITY] ?: d.priority,
            defaultKind = this[SettingKeys.DEFAULT_KIND] ?: d.defaultKind,
            secondaryDots = this[SettingKeys.SECONDARY_DOTS] ?: d.secondaryDots,
            autoSwitch = this[SettingKeys.AUTO_SWITCH] ?: d.autoSwitch,
            switchSeconds = this[SettingKeys.SWITCH_SECONDS] ?: d.switchSeconds,
            mode = this[SettingKeys.MODE] ?: d.mode,
            iColor = this[SettingKeys.I_COLOR] ?: d.iColor,
            iAlpha = this[SettingKeys.I_ALPHA] ?: d.iAlpha,
            iRadius = this[SettingKeys.I_RADIUS] ?: d.iRadius,
            nIdleW = this[SettingKeys.N_IDLE_W] ?: d.nIdleW,
            nIdleH = this[SettingKeys.N_IDLE_H] ?: d.nIdleH,
            nPillH = this[SettingKeys.N_PILL_H] ?: d.nPillH,
            nOffX = this[SettingKeys.N_OFF_X] ?: d.nOffX,
            nOffY = this[SettingKeys.N_OFF_Y] ?: d.nOffY,
            nRadius = this[SettingKeys.N_RADIUS] ?: d.nRadius,
            nColor = this[SettingKeys.N_COLOR] ?: d.nColor,
            nAlpha = this[SettingKeys.N_ALPHA] ?: d.nAlpha,
            nSpeed = this[SettingKeys.N_SPEED] ?: d.nSpeed,
            nExpandW = this[SettingKeys.N_EXPAND_W] ?: d.nExpandW,
        )
    }
}
