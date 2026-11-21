package com.superessence.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.core.History
import com.superessence.app.core.HistoryItem
import com.superessence.app.core.Prefs
import com.superessence.app.core.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun typeOf(mime: String, name: String): String {
    val ext = Storage.extension(name)
    return when {
        mime.startsWith("image/") -> "Images"
        mime.startsWith("video/") -> "Vidéos"
        mime.startsWith("audio/") -> "Audio"
        ext == "pdf" -> "PDF"
        ext in setOf("zip", "7z", "rar", "apk", "apks", "xapk") -> "Archives & APK"
        else -> "Documents"
    }
}

private fun iconFor(type: String) = when (type) {
    "Images" -> Icons.Filled.Image
    "Vidéos" -> Icons.Filled.Movie
    "Audio" -> Icons.Filled.MusicNote
    "PDF" -> Icons.Filled.PictureAsPdf
    "Archives & APK" -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}

private fun dayLabel(t: Long): String {
    val day = 86_400_000L
    val today = System.currentTimeMillis() / day
    return when (t / day) {
        today -> "Aujourd'hui"
        today - 1 -> "Hier"
        else -> SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRANCE).format(Date(t)).replaceFirstChar { it.uppercase() }
    }
}

// ------------------------------------------------------------------ Mes fichiers

@Composable
fun FilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val items by History.all().collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var askClear by remember { mutableStateOf(false) }

    val shown = items.filter {
        (type == null || typeOf(it.mime, it.name) == type) && (query.isBlank() || it.name.contains(query.trim(), true))
    }

    ToolScaffold("Mes fichiers", onBack) {
        if (items.isEmpty()) {
            Section {
                Text("Aucun fichier pour l'instant.")
                Hint("Chaque fichier créé par Fluxo (conversion, téléchargement, montage…) apparaîtra ici. Ils sont rangés dans Téléchargements/Fluxo.")
            }
            return@ToolScaffold
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Rechercher un fichier") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) }, modifier = Modifier.fillMaxWidth())
        val types = listOf("Images", "Vidéos", "Audio", "PDF", "Documents", "Archives & APK").filter { t -> items.any { typeOf(it.mime, it.name) == t } }
        ChoiceChips(listOf<String?>(null) + types, type, { it ?: "Tout" }) { type = it }
        Hint("${shown.size} fichier(s) · ${Storage.humanSize(shown.sumOf { it.size.coerceAtLeast(0) })}")
        var lastDay = ""
        shown.take(200).forEach { item ->
            val d = dayLabel(item.time)
            if (d != lastDay) {
                lastDay = d
                Text(d, color = SE.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            }
            val t = typeOf(item.mime, item.name)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SE.Surface, RoundedCornerShape(12.dp))
                    .clickable { Storage.openFile(ctx, item.toSaved()) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(iconFor(t), null, tint = SE.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(
                        Storage.humanSize(item.size) + " · " + SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(item.time)),
                        color = SE.Muted, fontSize = 12.sp,
                    )
                }
                IconButton(onClick = { Storage.shareFiles(ctx, listOf(item.toSaved())) }) { Icon(Icons.Filled.Share, "Partager", tint = SE.Muted) }
                IconButton(onClick = { if (Prefs.confirmDelete) toDelete = item else History.delete(ctx, item) }) {
                    Icon(Icons.Filled.DeleteOutline, "Supprimer", tint = SE.Muted)
                }
            }
        }
        if (shown.size > 200) Hint("Seuls les 200 premiers sont affichés. Affine la recherche.")
        Row {
            TextButton(onClick = { Storage.shareFiles(ctx, shown.take(100).map { it.toSaved() }) }) { Text("Tout partager") }
            TextButton(onClick = { askClear = true }) { Text("Vider la liste") }
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer ce fichier ?") },
            text = { Text(item.name) },
            confirmButton = {
                TextButton(onClick = {
                    if (!History.delete(ctx, item)) toast(ctx, "Retiré de la liste (le fichier n'existait plus)")
                    toDelete = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { History.remove(item); toDelete = null }) { Text("Retirer de la liste") }
                    TextButton(onClick = { toDelete = null }) { Text("Annuler") }
                }
            },
        )
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Vider la liste ?") },
            text = { Text("Les fichiers restent dans Téléchargements/Fluxo, seule la liste est effacée.") },
            confirmButton = { TextButton(onClick = { History.clear(); askClear = false }) { Text("Vider") } },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("Annuler") } },
        )
    }
}

// ------------------------------------------------------------------ Réglages

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var cacheSize by remember { mutableStateOf(-1L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        cacheSize = withContext(Dispatchers.IO) { ctx.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
    }
    ToolScaffold("Réglages", onBack) {
        Section("Apparence") {
            Text("Thème")
            val themes = listOf("system" to "Comme le téléphone", "light" to "Clair", "dark" to "Sombre")
            ChoiceChips(themes, themes.firstOrNull { it.first == Prefs.theme }, { it.second }) { Prefs.setTheme(it.first) }
            Text("Couleur")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Accents.forEachIndexed { i, a ->
                    val c = if (SE.Dark) a.third else a.second
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(c)
                            .clickable { Prefs.setAccent(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (Prefs.accent == i) Icon(Icons.Filled.Check, a.first, tint = SE.OnAccent, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Section("Accueil") {
            SwitchRow("Affichage en liste", Prefs.listLayout, "Sinon : grille de cartes") { Prefs.setListLayout(it) }
            SwitchRow("Afficher les descriptions", Prefs.showDescriptions) { Prefs.setShowDescriptions(it) }
            TextButton(onClick = { resetHome(ctx); toast(ctx, "Favoris et récents effacés") }) { Text("Effacer favoris et récents") }
        }
        Section("Comportement") {
            SwitchRow("Garder l'écran allumé", Prefs.keepScreenOn, "Pendant que l'appli est ouverte") { Prefs.setKeepScreenOn(it) }
            SwitchRow("Vibrations", Prefs.vibrate, "Minuteur, compteur, dés…") { Prefs.setVibrate(it) }
            SwitchRow("Demander avant de supprimer", Prefs.confirmDelete) { Prefs.setConfirmDelete(it) }
        }
        Section("Stockage") {
            ResultRow("Fichiers temporaires", if (cacheSize < 0) "…" else Storage.humanSize(cacheSize))
            Row {
                TextButton(onClick = {
                    ctx.cacheDir.listFiles()?.forEach { f -> runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() } }
                    refresh++
                    toast(ctx, "Fichiers temporaires supprimés")
                }) { Text("Vider") }
                TextButton(onClick = { History.clear(); toast(ctx, "Historique effacé") }) { Text("Effacer l'historique") }
            }
            Hint("Tes fichiers sont dans Téléchargements/${Storage.FOLDER}.")
        }
        Section("Autorisations") {
            TextButton(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Ouvrir les réglages Android de Fluxo") }
        }
    }
}

/** Taille d'un dossier (utilisé par l'écran « Mon téléphone »). */
fun dirSize(f: File): Long = runCatching { f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
