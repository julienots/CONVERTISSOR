package com.superessence.app.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.core.Prefs

/** Un outil de l'accueil. [color] est une fonction pour suivre le thème clair / sombre. */
data class Tool(
    val route: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val cat: String,
    val keywords: String = "",
    val tint: () -> Color = { SE.Accent },
) {
    val color: Color get() = tint()
}

const val C_FILES = "Fichiers"
const val C_CONVERT = "Convertir"
const val C_EDIT = "Modifier"
const val C_CALC = "Calculer"
const val C_DAILY = "Au quotidien"
const val C_PHONE = "Téléphone"

val categories = listOf(C_FILES, C_CONVERT, C_EDIT, C_CALC, C_DAILY, C_PHONE)

val tools = listOf(
    // Fichiers
    Tool("files", "Mes fichiers", "Tout ce que tu as créé avec Fluxo", Icons.Filled.FolderOpen, C_FILES, "historique dossier téléchargements récents"),
    Tool("transfer", "Envoyer à un appareil", "Wi‑Fi local, sans limite de taille", Icons.Filled.SwapHoriz, C_FILES, "transfert partager wifi envoyer recevoir", { SE.Blue }),
    Tool("youtube", "Liens & musique", "Lien YouTube ou autre → MP3, M4A, MP4", Icons.Filled.SmartDisplay, C_FILES, "youtube yt music musique mp3 m4a flac télécharger lien url soundcloud playlist", { SE.Red }),
    Tool("apps", "Sauvegarder une appli", "Récupère l'APK d'une appli installée", Icons.Filled.Apps, C_FILES, "apk applis android sauvegarde", { SE.Green }),
    Tool("compress", "Réduire la taille", "Images, vidéos, audio ou tout en ZIP", Icons.Filled.Compress, C_FILES, "compresser compression zip léger poids", { SE.Teal }),
    Tool("info", "Infos d'un fichier", "Format, durée, résolution, GPS, empreintes", Icons.Filled.Fingerprint, C_FILES, "métadonnées exif md5 sha hash comparer", { SE.Orange }),
    // Convertir
    Tool("convert", "Convertir un fichier", "Vidéo, audio, image, PDF, Word, archives…", Icons.Filled.Transform, C_CONVERT, "jpg png webp heic gif mp4 mkv avi mov webm mp3 wav flac ogg json csv xml markdown docx txt zip 7z rar", { SE.Purple }),
    Tool("apk", "XAPK → APK", "XAPK, APKS, APKM en un seul APK", Icons.Filled.Android, C_CONVERT, "xapk apks apkm installer", { SE.Green }),
    Tool("frames", "Vidéo → images", "Extraire les images d'une vidéo", Icons.Filled.PhotoLibrary, C_CONVERT, "photos captures zip", { SE.Orange }),
    Tool("transcribe", "Vidéo → texte", "Transcription TXT + sous-titres SRT", Icons.Filled.Subtitles, C_CONVERT, "transcription sous-titres srt parole", { SE.Blue }),
    Tool("ocr", "Photo → texte", "Lire le texte d'une photo (OCR)", Icons.Filled.DocumentScanner, C_CONVERT, "scanner reconnaissance texte document code-barres", { SE.Teal }),
    Tool("tts", "Texte → voix", "Lire un texte à voix haute en MP3", Icons.Filled.RecordVoiceOver, C_CONVERT, "synthèse vocale parler lecture", { SE.Purple }),
    Tool("units", "Unités & devises", "Longueur, poids, °C, €/$ et plus", Icons.Filled.Straighten, C_CONVERT, "conversion mesure euro dollar change", { SE.Orange }),
    Tool("bases", "Nombres", "Binaire, hexa, chiffres romains, en lettres", Icons.Filled.Numbers, C_CONVERT, "binaire hexadécimal octal romain lettres", { SE.Blue }),
    // Modifier
    Tool("video", "Vidéo", "Couper, fusionner, GIF, format TikTok…", Icons.Filled.Movie, C_EDIT, "éditeur montage couper fusion gif ralenti accéléré", { SE.Red }),
    Tool("audio", "Audio", "Couper, sonnerie, voix, basses, 8D…", Icons.Filled.GraphicEq, C_EDIT, "éditeur son musique sonnerie volume nightcore", { SE.Blue }),
    Tool("image", "Photo", "Taille, recadrage, filtres, cadre, mème…", Icons.Filled.Image, C_EDIT, "éditeur image photo recadrer filtre sticker", { SE.Green }),
    Tool("pdf", "PDF", "Fusionner, numéroter, filigrane, signer…", Icons.Filled.PictureAsPdf, C_EDIT, "pdf fusionner séparer mot de passe signature", { SE.Red }),
    Tool("text", "Texte", "Majuscules, tri, Base64, JSON, morse…", Icons.Filled.TextFields, C_EDIT, "texte casse base64 json morse compteur mots", { SE.Purple }),
    Tool("qr", "QR codes", "Scanner et créer", Icons.Filled.QrCode2, C_EDIT, "qrcode code-barres scanner wifi contact", { SE.Teal }),
    // Calculer
    Tool("calc", "Calculatrice", "Avec parenthèses, %, puissances, racines", Icons.Filled.Calculate, C_CALC, "calcul calculette"),
    Tool("percent", "Pourcentages", "x % de y, évolution, remise, TVA", Icons.Filled.Percent, C_CALC, "pourcentage tva ht ttc remise soldes promo", { SE.Green }),
    Tool("money", "Argent", "Pourboire, partage, crédit, épargne", Icons.Filled.Savings, C_CALC, "pourboire addition partager crédit prêt emprunt intérêts épargne salaire brut net", { SE.Orange }),
    Tool("dates", "Dates", "Écart entre dates, âge, jour de la semaine", Icons.Filled.Event, C_CALC, "date âge anniversaire jours semaines compte à rebours", { SE.Blue }),
    Tool("health", "Santé", "IMC, calories, eau, fréquence cardiaque", Icons.Filled.MonitorWeight, C_CALC, "imc poids calories eau sport santé", { SE.Red }),
    Tool("everyday", "Petits calculs", "Carburant, prix au kilo, moyenne, recette", Icons.Filled.LocalGasStation, C_CALC, "essence carburant consommation prix kilo moyenne notes recette portions", { SE.Teal }),
    // Au quotidien
    Tool("timer", "Chrono & minuteur", "Chronomètre, minuteur, Pomodoro", Icons.Filled.Timer, C_DAILY, "chronomètre minuteur pomodoro temps alarme", { SE.Orange }),
    Tool("notes", "Bloc-notes", "Notes rapides gardées sur le téléphone", Icons.Filled.EditNote, C_DAILY, "notes mémo écrire", { SE.Orange }),
    Tool("todo", "Liste", "Courses, tâches, à cocher", Icons.Filled.Checklist, C_DAILY, "liste courses tâches todo cocher", { SE.Green }),
    Tool("counter", "Compteur", "Compter des personnes, des points…", Icons.Filled.ExposurePlus1, C_DAILY, "compteur score points tally", { SE.Blue }),
    Tool("random", "Hasard", "Dés, pile ou face, tirage au sort", Icons.Filled.Casino, C_DAILY, "dé dés pile face tirage aléatoire nombre roulette équipes", { SE.Purple }),
    Tool("password", "Mots de passe", "Générer un mot de passe solide", Icons.Filled.Password, C_DAILY, "mot de passe sécurité générateur pin", { SE.Red }),
    Tool("colors", "Couleurs", "HEX, RGB, HSL, nuances, contraste", Icons.Filled.Palette, C_DAILY, "couleur hex rgb hsl palette contraste", { SE.Purple }),
    Tool("clock", "Heure du monde", "Heure dans les grandes villes", Icons.Filled.Public, C_DAILY, "fuseau horaire heure monde ville", { SE.Teal }),
    Tool("rec", "Dictaphone", "Enregistrer ta voix", Icons.Filled.Mic, C_DAILY, "enregistrer voix micro mémo vocal", { SE.Red }),
    // Téléphone
    Tool("torch", "Lampe torche", "Lampe, SOS, stroboscope, écran blanc", Icons.Filled.FlashlightOn, C_PHONE, "lampe torche flash sos lumière", { SE.Orange }),
    Tool("level", "Niveau & boussole", "Niveau à bulle et boussole", Icons.Filled.Explore, C_PHONE, "niveau bulle boussole nord inclinaison", { SE.Green }),
    Tool("sound", "Sonomètre", "Mesurer le bruit en décibels", Icons.Filled.Hearing, C_PHONE, "décibels bruit son db micro", { SE.Red }),
    Tool("tone", "Sons & métronome", "Métronome, générateur de fréquence", Icons.Filled.MusicNote, C_PHONE, "métronome fréquence hz tonalité accordeur bip", { SE.Purple }),
    Tool("device", "Mon téléphone", "Modèle, batterie, stockage, écran", Icons.Filled.PhoneAndroid, C_PHONE, "infos appareil batterie stockage mémoire ram android", { SE.Blue }),
    Tool("screentest", "Test d'écran & règle", "Pixels morts, couleurs, règle, tactile", Icons.Filled.Fullscreen, C_PHONE, "écran pixels morts règle tactile test", { SE.Teal }),
    Tool("settings", "Réglages", "Thème, couleur, affichage", Icons.Filled.Settings, C_PHONE, "paramètres thème sombre clair couleur cache", { SE.Muted }),
    Tool("about", "À propos", "Version et licences", Icons.Filled.Info, C_PHONE, "aide version", { SE.Muted }),
)

private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

private fun loadList(ctx: Context, key: String): List<String> =
    prefs(ctx).getString(key, "").orEmpty().split(',').filter { it.isNotBlank() }

private fun saveList(ctx: Context, key: String, list: List<String>) =
    prefs(ctx).edit().putString(key, list.joinToString(",")).apply()

fun resetHome(ctx: Context) = prefs(ctx).edit().clear().apply()

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val incoming by Shared.incoming.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val favs = remember { mutableStateListOf<String>().apply { addAll(loadList(ctx, "favs")) } }
    val recents = remember { mutableStateListOf<String>().apply { addAll(loadList(ctx, "recents")) } }
    fun launch(t: Tool) {
        recents.remove(t.route); recents.add(0, t.route)
        while (recents.size > 6) recents.removeAt(recents.size - 1)
        saveList(ctx, "recents", recents)
        open(t.route)
    }
    fun toggleFav(t: Tool) {
        if (!favs.remove(t.route)) favs.add(t.route)
        saveList(ctx, "favs", favs)
    }
    val q = query.trim().lowercase()
    val filtered = tools.filter { t ->
        (cat == null || t.cat == cat) &&
            (q.isEmpty() || (t.title + " " + t.subtitle + " " + t.cat + " " + t.keywords).lowercase().contains(q))
    }
    val list = Prefs.listLayout
    val cols = if (list) 1 else 2

    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        modifier = Modifier
            .fillMaxSize()
            .background(SE.Bg)
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(if (list) 6.dp else 10.dp),
    ) {
        item(span = { GridItemSpan(cols) }) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Fluxo", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = SE.Text, modifier = Modifier.weight(1f))
                IconButton(onClick = { Prefs.setListLayout(!list) }) {
                    Icon(if (list) Icons.Filled.GridView else Icons.Filled.ViewList, if (list) "Afficher en grille" else "Afficher en liste", tint = SE.Muted)
                }
                IconButton(onClick = { open("files") }) { Icon(Icons.Filled.FolderOpen, "Mes fichiers", tint = SE.Muted) }
                IconButton(onClick = { open("settings") }) { Icon(Icons.Filled.Settings, "Réglages", tint = SE.Muted) }
            }
        }
        item(span = { GridItemSpan(cols) }) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Rechercher (mp3, pdf, qr, minuteur…)") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Effacer") } },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(span = { GridItemSpan(cols) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(cat == null, { cat = null }, { Text("Tout") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SE.Accent, selectedLabelColor = SE.OnAccent))
                }
                items(categories) { c ->
                    FilterChip(cat == c, { cat = if (cat == c) null else c }, { Text(c) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SE.Accent, selectedLabelColor = SE.OnAccent))
                }
            }
        }
        if (incoming.isNotEmpty()) {
            item(span = { GridItemSpan(cols) }) {
                Section("${incoming.size} fichier(s) reçu(s)") {
                    Hint("Que veux-tu en faire ?")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("convert" to "Convertir", "transfer" to "Envoyer", "compress" to "Réduire", "info" to "Infos").forEach { (r, l) ->
                            TextButton(onClick = {
                                Shared.handOff(incoming)
                                Shared.incoming.value = emptyList()
                                open(r)
                            }) { Text(l) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("image" to "Photo", "video" to "Vidéo", "audio" to "Audio", "pdf" to "PDF").forEach { (r, l) ->
                            TextButton(onClick = {
                                Shared.handOff(incoming)
                                Shared.incoming.value = emptyList()
                                open(r)
                            }) { Text(l) }
                        }
                    }
                }
            }
        }
        if (q.isEmpty() && cat == null) {
            val favTools = favs.mapNotNull { r -> tools.firstOrNull { it.route == r } }
            if (favTools.isNotEmpty()) {
                item(span = { GridItemSpan(cols) }) { SectionTitle("Favoris") }
                items(favTools, key = { "fav_" + it.route }) { t -> ToolEntry(t, true, list, { launch(t) }) { toggleFav(t) } }
            }
            val recentTools = recents.mapNotNull { r -> tools.firstOrNull { it.route == r } }.filter { it.route !in favs }.take(4)
            if (recentTools.isNotEmpty()) {
                item(span = { GridItemSpan(cols) }) { SectionTitle("Récents") }
                items(recentTools, key = { "rec_" + it.route }) { t -> ToolEntry(t, false, list, { launch(t) }) { toggleFav(t) } }
            }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(cols) }) { Hint("Rien trouvé pour « $query ».") }
        }
        filtered.groupBy { it.cat }.forEach { (c, l) ->
            item(span = { GridItemSpan(cols) }) { SectionTitle(c) }
            items(l, key = { it.route }) { t -> ToolEntry(t, t.route in favs, list, { launch(t) }) { toggleFav(t) } }
        }
        item(span = { GridItemSpan(cols) }) {
            Hint("Appui long sur un outil : l'ajouter aux favoris.")
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = SE.Muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolEntry(t: Tool, fav: Boolean, list: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    if (list) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(SE.Surface, shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(t.icon, null, tint = t.color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = SE.Text)
                if (Prefs.showDescriptions) {
                    Text(t.subtitle, color = SE.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (fav) Icon(Icons.Filled.Star, "Favori", tint = SE.Orange, modifier = Modifier.size(18.dp))
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .height(if (Prefs.showDescriptions) 118.dp else 92.dp)
                .background(SE.Surface, shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(t.icon, null, tint = t.color, modifier = Modifier.size(26.dp))
                if (fav) Icon(Icons.Filled.Star, "Favori", tint = SE.Orange, modifier = Modifier.size(18.dp))
            }
            Box {
                Column {
                    Text(t.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = SE.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (Prefs.showDescriptions) {
                        Text(t.subtitle, color = SE.Muted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
