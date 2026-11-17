package com.myshell.island.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.clipStore by preferencesDataStore("clipboard")
private val CLIPS = stringPreferencesKey("items")

/** Clipboard history, stored locally. Pinned items survive "Clear". */
class ClipboardRepository(private val context: Context, private val scope: CoroutineScope) {

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    init {
        scope.launch { _items.value = decode(context.clipStore.data.first()[CLIPS]) }
    }

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return
        _items.update { list ->
            val old = list.firstOrNull { it.text == t }
            val item = ClipItem(old?.id ?: System.nanoTime(), t, old?.pinned ?: false, System.currentTimeMillis())
            val rest = list.filter { it.text != t }
            val unpinned = (listOf(item) + rest).filter { !it.pinned }.take(MAX_ITEMS)
            val pinned = (listOf(item) + rest).filter { it.pinned }
            (pinned + unpinned).sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })
        }
        persist()
    }

    fun togglePin(id: Long) {
        _items.update { list ->
            list.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
                .sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time })
        }
        persist()
    }

    fun delete(id: Long) {
        _items.update { list -> list.filter { it.id != id } }
        persist()
    }

    fun clearUnpinned() {
        _items.update { list -> list.filter { it.pinned } }
        persist()
    }

    private fun persist() {
        val snapshot = _items.value
        scope.launch {
            context.clipStore.edit { p ->
                val arr = JSONArray()
                snapshot.forEach {
                    arr.put(JSONObject().put("i", it.id).put("t", it.text).put("p", it.pinned).put("w", it.time))
                }
                p[CLIPS] = arr.toString()
            }
        }
    }

    private fun decode(raw: String?): List<ClipItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ClipItem(o.getLong("i"), o.getString("t"), o.optBoolean("p"), o.optLong("w"))
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_ITEMS = 50
        const val MAX_LEN = 20_000
    }
}
