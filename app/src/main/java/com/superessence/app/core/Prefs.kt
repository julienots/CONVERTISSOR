package com.superessence.app.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superessence.app.SuperEssenceApp

/** Réglages de l'appli, observables par Compose (les écrans se mettent à jour tout seuls). */
object Prefs {
    private val sp by lazy { SuperEssenceApp.instance.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    /** "system", "light" ou "dark". */
    var theme by mutableStateOf("system")
        private set
    /** Couleur principale (index dans [com.superessence.app.ui.Accents]). */
    var accent by mutableStateOf(0)
        private set
    /** Accueil en liste (true) ou en grille (false). */
    var listLayout by mutableStateOf(true)
        private set
    var showDescriptions by mutableStateOf(true)
        private set
    var keepScreenOn by mutableStateOf(false)
        private set
    var vibrate by mutableStateOf(true)
        private set
    var confirmDelete by mutableStateOf(true)
        private set

    fun load() {
        theme = sp.getString("theme", "system") ?: "system"
        accent = sp.getInt("accent", 0)
        listLayout = sp.getBoolean("list", true)
        showDescriptions = sp.getBoolean("desc", true)
        keepScreenOn = sp.getBoolean("screen_on", false)
        vibrate = sp.getBoolean("vibrate", true)
        confirmDelete = sp.getBoolean("confirm_delete", true)
    }

    fun changeTheme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun changeAccent(v: Int) { accent = v; sp.edit().putInt("accent", v).apply() }
    fun changeListLayout(v: Boolean) { listLayout = v; sp.edit().putBoolean("list", v).apply() }
    fun changeShowDescriptions(v: Boolean) { showDescriptions = v; sp.edit().putBoolean("desc", v).apply() }
    fun changeKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("screen_on", v).apply() }
    fun changeVibrate(v: Boolean) { vibrate = v; sp.edit().putBoolean("vibrate", v).apply() }
    fun changeConfirmDelete(v: Boolean) { confirmDelete = v; sp.edit().putBoolean("confirm_delete", v).apply() }

    /** Petit stockage texte libre pour les outils (notes, listes, compteurs…). */
    fun getString(key: String, def: String = ""): String = sp.getString("data_$key", def) ?: def
    fun putString(key: String, value: String) = sp.edit().putString("data_$key", value).apply()
}
