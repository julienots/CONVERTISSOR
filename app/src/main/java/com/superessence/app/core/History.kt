package com.superessence.app.core

import android.content.Context
import android.net.Uri
import com.superessence.app.SuperEssenceApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Un fichier créé par l'appli, gardé dans l'historique « Mes fichiers ». */
data class HistoryItem(val name: String, val uri: Uri, val size: Long, val mime: String, val time: Long) {
    fun toSaved() = SavedFile(name, uri, size, mime)
}

/** Historique des fichiers créés (300 derniers), pour les retrouver facilement. */
object History {
    private const val MAX = 300
    private val sp by lazy { SuperEssenceApp.instance.getSharedPreferences("history", Context.MODE_PRIVATE) }
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _items.value = runCatching {
                val arr = JSONArray(sp.getString("items", "[]"))
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    HistoryItem(o.getString("n"), Uri.parse(o.getString("u")), o.optLong("s", -1), o.optString("m", "*/*"), o.optLong("t"))
                }
            }.getOrDefault(emptyList())
            loaded = true
        }
    }

    fun all(): StateFlow<List<HistoryItem>> { ensure(); return items }

    private fun persist(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.uri.toString()).put("s", it.size).put("m", it.mime).put("t", it.time)) }
        sp.edit().putString("items", arr.toString()).apply()
    }

    fun add(f: SavedFile) {
        runCatching {
            ensure()
            _items.update { old -> (listOf(HistoryItem(f.name, f.uri, f.size, f.mime, System.currentTimeMillis())) + old.filter { it.uri != f.uri }).take(MAX) }
            persist(_items.value)
        }
    }

    fun remove(item: HistoryItem) {
        ensure()
        _items.update { it - item }
        persist(_items.value)
    }

    /** Supprime aussi le fichier du téléphone. */
    fun delete(ctx: Context, item: HistoryItem): Boolean {
        val ok = runCatching { ctx.contentResolver.delete(item.uri, null, null) > 0 }.getOrDefault(false)
        remove(item)
        return ok
    }

    fun clear() {
        ensure()
        _items.value = emptyList()
        persist(emptyList())
    }
}
