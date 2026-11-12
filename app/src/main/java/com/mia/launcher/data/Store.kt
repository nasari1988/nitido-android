package com.mia.launcher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Tab(val id: String, val name: String, val color: Long, val apps: List<String>)

data class Group(val id: String, val name: String, val apps: List<String>)

data class Settings(
    val iconStyle: String = "vidrio",
    val glass: Float = 0.45f,
    val columns: Int = 4,
    val labels: Boolean = true,
    val voiceReplies: Boolean = true,
    val wakeWord: Boolean = false,
    val groqKey: String = "",
    val groqModel: String = "llama-3.3-70b-versatile",
    val userName: String = "",
)

val TabColors = listOf(0xFF2459C7, 0xFFB4246E, 0xFF0E7A4F, 0xFF5B3FD9, 0xFFB45309, 0xFF0A5F85)

/** Datos guardados del launcher. Todo es estado observable por Compose. */
object Store {
    private lateinit var prefs: SharedPreferences

    var tabs by mutableStateOf(listOf<Tab>())
        private set
    var groups by mutableStateOf(listOf<Group>())
        private set
    var favorites by mutableStateOf(listOf<String>())
        private set
    var dock by mutableStateOf(listOf<String>())
        private set
    var hidden by mutableStateOf(setOf<String>())
        private set
    var widgetIds by mutableStateOf(listOf<Int>())
        private set
    var settings by mutableStateOf(Settings())
        private set
    var defaultsDone by mutableStateOf(false)
        private set

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("mia_store", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        tabs = readArray("tabs").map { o ->
            Tab(o.getString("id"), o.getString("name"), o.getLong("color"), o.getJSONArray("apps").strings())
        }.ifEmpty {
            if (prefs.contains("tabs")) emptyList() else listOf(
                Tab(newId(), "Trabajo", TabColors[0], emptyList()),
                Tab(newId(), "Social", TabColors[1], emptyList()),
                Tab(newId(), "Juegos", TabColors[2], emptyList()),
            )
        }
        groups = readArray("groups").map { o ->
            Group(o.getString("id"), o.getString("name"), o.getJSONArray("apps").strings())
        }
        favorites = JSONArray(prefs.getString("favorites", "[]")).strings()
        dock = JSONArray(prefs.getString("dock", "[]")).strings()
        hidden = JSONArray(prefs.getString("hidden", "[]")).strings().toSet()
        val w = JSONArray(prefs.getString("widgets", "[]"))
        widgetIds = (0 until w.length()).map { w.getInt(it) }
        defaultsDone = prefs.getBoolean("defaultsDone", false)
        val s = JSONObject(prefs.getString("settings", "{}") ?: "{}")
        val d = Settings()
        settings = Settings(
            iconStyle = s.optString("iconStyle", d.iconStyle),
            glass = s.optDouble("glass", d.glass.toDouble()).toFloat(),
            columns = s.optInt("columns", d.columns),
            labels = s.optBoolean("labels", d.labels),
            voiceReplies = s.optBoolean("voiceReplies", d.voiceReplies),
            wakeWord = s.optBoolean("wakeWord", d.wakeWord),
            groqKey = s.optString("groqKey", d.groqKey),
            groqModel = s.optString("groqModel", d.groqModel),
            userName = s.optString("userName", d.userName),
        )
    }

    private fun readArray(key: String): List<JSONObject> {
        val arr = JSONArray(prefs.getString(key, "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun List<String>.toJson(): String = JSONArray(this).toString()

    fun newId(): String = UUID.randomUUID().toString().take(8)

    // ---------- Guardado
    private fun saveTabs() = prefs.edit().putString("tabs", JSONArray(tabs.map {
        JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("apps", JSONArray(it.apps))
    }).toString()).apply()

    private fun saveGroups() = prefs.edit().putString("groups", JSONArray(groups.map {
        JSONObject().put("id", it.id).put("name", it.name).put("apps", JSONArray(it.apps))
    }).toString()).apply()

    // ---------- Pestañas
    fun addTab(name: String) {
        tabs = tabs + Tab(newId(), name, TabColors[tabs.size % TabColors.size], emptyList()); saveTabs()
    }

    fun renameTab(id: String, name: String) {
        tabs = tabs.map { if (it.id == id) it.copy(name = name) else it }; saveTabs()
    }

    fun deleteTab(id: String) {
        tabs = tabs.filterNot { it.id == id }; saveTabs()
    }

    fun toggleAppInTab(tabId: String, key: String) {
        tabs = tabs.map {
            if (it.id != tabId) it else it.copy(apps = if (key in it.apps) it.apps - key else it.apps + key)
        }; saveTabs()
    }

    // ---------- Grupos
    fun addGroup(name: String, firstApp: String?): String {
        val id = newId()
        groups = groups + Group(id, name, listOfNotNull(firstApp)); saveGroups(); return id
    }

    fun renameGroup(id: String, name: String) {
        groups = groups.map { if (it.id == id) it.copy(name = name) else it }; saveGroups()
    }

    fun deleteGroup(id: String) {
        groups = groups.filterNot { it.id == id }; saveGroups()
    }

    fun toggleAppInGroup(groupId: String, key: String) {
        groups = groups.map {
            if (it.id != groupId) it else it.copy(apps = if (key in it.apps) it.apps - key else it.apps + key)
        }; saveGroups()
    }

    // ---------- Inicio, dock, ocultas
    fun toggleFavorite(key: String) {
        favorites = if (key in favorites) favorites - key else favorites + key
        prefs.edit().putString("favorites", favorites.toJson()).apply()
    }

    fun toggleDock(key: String) {
        dock = if (key in dock) dock - key else (dock + key).takeLast(5)
        prefs.edit().putString("dock", dock.toJson()).apply()
    }

    fun setDefaults(dockKeys: List<String>, favKeys: List<String>) {
        dock = dockKeys.take(5); favorites = favKeys
        prefs.edit().putString("dock", dock.toJson()).putString("favorites", favorites.toJson())
            .putBoolean("defaultsDone", true).apply()
        defaultsDone = true
    }

    fun toggleHidden(key: String) {
        hidden = if (key in hidden) hidden - key else hidden + key
        prefs.edit().putString("hidden", hidden.toList().toJson()).apply()
    }

    // ---------- Widgets de apps
    fun addWidget(id: Int) {
        widgetIds = widgetIds + id
        prefs.edit().putString("widgets", JSONArray(widgetIds).toString()).apply()
    }

    fun removeWidget(id: Int) {
        widgetIds = widgetIds - id
        prefs.edit().putString("widgets", JSONArray(widgetIds).toString()).apply()
    }

    // ---------- Ajustes
    fun updateSettings(block: (Settings) -> Settings) {
        settings = block(settings)
        val s = settings
        prefs.edit().putString(
            "settings", JSONObject()
                .put("iconStyle", s.iconStyle).put("glass", s.glass.toDouble()).put("columns", s.columns)
                .put("labels", s.labels).put("voiceReplies", s.voiceReplies).put("wakeWord", s.wakeWord)
                .put("groqKey", s.groqKey).put("groqModel", s.groqModel).put("userName", s.userName)
                .toString()
        ).apply()
    }
}
