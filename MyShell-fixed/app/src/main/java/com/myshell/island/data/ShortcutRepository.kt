package com.myshell.island.data

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.shortcutStore by preferencesDataStore("shortcuts")
private val PINNED = stringPreferencesKey("pinned")
private val RECENT = stringPreferencesKey("recent")

/** Pinned shortcuts (custom order) and recently launched apps. Package names never contain commas. */
class ShortcutRepository(private val context: Context, private val scope: CoroutineScope) {

    val pinned: StateFlow<List<String>> = context.shortcutStore.data
        .map { it[PINNED].csv() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recents: StateFlow<List<String>> = context.shortcutStore.data
        .map { it[RECENT].csv() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Volatile
    private var cachedApps: List<AppInfo>? = null

    /** Launchable apps (package-visibility-safe: uses the <queries> LAUNCHER intent). */
    suspend fun installedApps(force: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        cachedApps?.takeIf { !force } ?: run {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(intent, 0)
                .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            cachedApps = list
            list
        }
    }

    fun togglePin(pkg: String) = edit(PINNED) { if (pkg in it) it - pkg else it + pkg }

    fun unpin(pkg: String) = edit(PINNED) { it - pkg }

    /** Move a shortcut one step left (-1) or right (+1) to customise the order. */
    fun move(pkg: String, delta: Int) = edit(PINNED) { list ->
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) list
        else list.toMutableList().also { it.add(j, it.removeAt(i)) }
    }

    fun recordLaunch(pkg: String) = edit(RECENT) { (listOf(pkg) + (it - pkg)).take(8) }

    private fun edit(key: androidx.datastore.preferences.core.Preferences.Key<String>, f: (List<String>) -> List<String>) {
        scope.launch { context.shortcutStore.edit { p -> p[key] = f(p[key].csv()).joinToString(",") } }
    }

    private fun String?.csv(): List<String> = this?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
}
