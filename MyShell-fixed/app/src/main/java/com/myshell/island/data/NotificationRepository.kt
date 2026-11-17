package com.myshell.island.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.historyStore by preferencesDataStore("notification_history")
private val HISTORY_KEY = stringPreferencesKey("items")

/**
 * Single source of truth for notifications captured by [com.myshell.island.service.ShellNotificationListener].
 * Everything stays on-device: live items in memory, text-only history in a local DataStore file.
 */
class NotificationRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    private val _active = MutableStateFlow<List<ShellNotification>>(emptyList())
    val active: StateFlow<List<ShellNotification>> = _active.asStateFlow()

    val unread: StateFlow<Int> = _active
        .map { list -> list.count { it.unread } }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** Fires once per *new* notification (duplicates are filtered out). */
    private val _events = MutableSharedFlow<ShellNotification>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ShellNotification> = _events.asSharedFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    /** Set by the listener service so we can also clear the original from the shade. */
    @Volatile
    var canceller: ((String) -> Unit)? = null

    private val recentSignatures = HashMap<String, Long>()

    init {
        scope.launch { _history.value = decode(context.historyStore.data.first()[HISTORY_KEY]) }
    }

    /** [initial] = already present when the listener connected: shown, but no alert and not unread. */
    fun onPosted(incoming: ShellNotification, initial: Boolean = false) {
        val s = settings.state.value
        if (incoming.pkg in s.disabledPackages) return

        val existing = _active.value.firstOrNull { it.key == incoming.key }
        val sameContent = existing != null && existing.title == incoming.title && existing.text == incoming.text
        val signature = "${incoming.pkg}|${incoming.title}|${incoming.text}"
        val now = System.currentTimeMillis()
        val recentDuplicate = synchronized(recentSignatures) {
            val prev = recentSignatures[signature]
            recentSignatures[signature] = now
            if (recentSignatures.size > 200) recentSignatures.entries.removeAll { now - it.value > 10_000 }
            prev != null && now - prev < 2_000
        }

        val unread = when {
            initial -> false
            sameContent -> existing?.unread ?: false
            else -> true
        }
        _active.update { list ->
            (listOf(incoming.copy(unread = unread)) + list.filter { it.key != incoming.key })
                .sortedByDescending { it.postedAt }
                .take(MAX_ACTIVE)
        }

        if (initial || sameContent || recentDuplicate) return

        appendHistory(incoming, s.hidePreviews)
        if (!s.dnd) _events.tryEmit(incoming)
    }

    /** The source app (or the user, from the shade) removed it. */
    fun onRemoved(key: String) {
        _active.update { list -> list.filter { it.key != key } }
    }

    fun dismiss(key: String) {
        // Only removes it from the island's list. The original Android notification is never touched, so it stays
        // in the notification panel.
        _active.update { list -> list.filter { it.key != key } }
    }

    fun dismissPackage(pkg: String) {
        _active.value.filter { it.pkg == pkg }.forEach { dismiss(it.key) }
    }

    fun clearAll() {
        _active.value = emptyList() // island list only; the system notifications stay in the panel
    }

    fun markAllRead() {
        if (_active.value.none { it.unread }) return
        _active.update { list -> list.map { it.copy(unread = false) } }
    }

    fun clearHistory() {
        _history.value = emptyList()
        persistHistory()
    }

    private fun appendHistory(n: ShellNotification, hideText: Boolean) {
        val blank = hideText || n.hidden
        val item = HistoryItem(
            pkg = n.pkg,
            appName = n.appName,
            title = if (blank) "" else n.title,
            text = if (blank) "" else n.text,
            postedAt = n.postedAt,
        )
        _history.update { (listOf(item) + it).take(MAX_HISTORY) }
        persistHistory()
    }

    private fun persistHistory() {
        val snapshot = _history.value
        scope.launch { context.historyStore.edit { it[HISTORY_KEY] = encode(snapshot) } }
    }

    private fun encode(items: List<HistoryItem>): String {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("p", it.pkg).put("a", it.appName).put("t", it.title)
                    .put("x", it.text).put("w", it.postedAt)
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<HistoryItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HistoryItem(o.getString("p"), o.getString("a"), o.optString("t"), o.optString("x"), o.getLong("w"))
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_ACTIVE = 60
        const val MAX_HISTORY = 300
    }
}
