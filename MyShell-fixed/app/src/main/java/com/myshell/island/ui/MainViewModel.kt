package com.myshell.island.ui

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.myshell.island.data.AppInfo
import com.myshell.island.di.AppContainer
import com.myshell.island.ui.island.IslandViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.myshell.island.data.IslandCommand
import com.myshell.island.data.SettingKeys
import com.myshell.island.service.OverlayService

/** Creates ViewModels with the manually-built [AppContainer]. */
class VMFactory(private val container: AppContainer, private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(IslandViewModel::class.java) -> IslandViewModel(container, context.applicationContext) as T
        modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(container) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}

/** State for the settings app (permissions, history, settings, per-app controls). */
class MainViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.state
    val history = c.notifications.history

    fun <T> set(key: Preferences.Key<T>, value: T) {
        viewModelScope.launch { c.settings.set(key, value) }
    }

    fun setPackageEnabled(pkg: String, enabled: Boolean) {
        viewModelScope.launch { c.settings.setPackageEnabled(pkg, enabled) }
    }

    /** Starts the island if needed, then asks it to show [cmd] so the user can see their size / position settings. */
    fun preview(cmd: IslandCommand) {
        viewModelScope.launch {
            OverlayService.startIfAllowed(c.app)
            delay(700)
            c.commands.emit(cmd)
        }
    }

    fun applyPreset(p: com.myshell.island.ui.island.LayoutPreset) {
        viewModelScope.launch {
            c.settings.set(SettingKeys.WIDTH_DP, p.width)
            c.settings.set(SettingKeys.PANEL_SCALE, p.panel)
            c.settings.set(SettingKeys.PILL_SCALE, p.pill)
            c.settings.set(SettingKeys.IDLE_W, 0)
            c.settings.set(SettingKeys.IDLE_H, 0)
            c.settings.set(SettingKeys.PILL_H, 0)
        }
    }

    fun resetLayout() {
        viewModelScope.launch {
            c.settings.set(SettingKeys.WIDTH_DP, 300)
            c.settings.set(SettingKeys.OFFSET_X, 0)
            c.settings.set(SettingKeys.OFFSET_Y, 0)
            c.settings.set(SettingKeys.IDLE_W, 0)
            c.settings.set(SettingKeys.IDLE_H, 0)
            c.settings.set(SettingKeys.PILL_H, 0)
            c.settings.set(SettingKeys.PILL_SCALE, 1f)
            c.settings.set(SettingKeys.PANEL_SCALE, 1f)
        }
    }

    fun movePriority(kind: com.myshell.island.ui.island.ActivityKind, delta: Int) {
        val order = com.myshell.island.ui.island.ActivityKind.parseOrder(settings.value.priority).toMutableList()
        val i = order.indexOf(kind)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order.add(j, order.removeAt(i))
        set(SettingKeys.PRIORITY, com.myshell.island.ui.island.ActivityKind.serialize(order))
    }

    fun resetNotch() {
        val d = com.myshell.island.data.ShellSettings()
        set(SettingKeys.N_IDLE_W, d.nIdleW); set(SettingKeys.N_IDLE_H, d.nIdleH); set(SettingKeys.N_PILL_H, d.nPillH)
        set(SettingKeys.N_OFF_X, d.nOffX); set(SettingKeys.N_OFF_Y, d.nOffY); set(SettingKeys.N_RADIUS, d.nRadius)
        set(SettingKeys.N_COLOR, d.nColor); set(SettingKeys.N_ALPHA, d.nAlpha); set(SettingKeys.N_SPEED, d.nSpeed)
        set(SettingKeys.N_EXPAND_W, d.nExpandW)
    }

    fun resetPriority() = set(SettingKeys.PRIORITY, com.myshell.island.ui.island.ActivityKind.serialize(com.myshell.island.ui.island.ActivityKind.DEFAULT_ORDER))

    fun clearHistory() = c.notifications.clearHistory()

    suspend fun apps(): List<AppInfo> = c.shortcuts.installedApps()

    fun label(pkg: String) = c.icons.label(pkg)
}
