package com.superessence.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.R

data class Tool(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val color: Color, val cat: String)

private const val C1 = "Transférer & télécharger"
private const val C2 = "Convertir"
private const val C3 = "Éditer"
private const val C4 = "Outils"

val tools = listOf(
    Tool("transfer", "Transfert", "Appareil → appareil, jusqu'à 2 Go et plus", Icons.Filled.SwapHoriz, SE.Cyan, C1),
    Tool("youtube", "Liens → MP3", "YouTube en MP3, M4A ou MP4, en lot", Icons.Filled.SmartDisplay, SE.Red, C1),
    Tool("apps", "Mes applis → APK", "Sauvegarde l'APK des applis installées", Icons.Filled.Apps, SE.Green, C1),
    Tool("convert", "Convertir", "Vidéo, audio, image, PDF, texte, Word, archives", Icons.Filled.Transform, SE.Purple, C2),
    Tool("apk", "XAPK → APK", "XAPK, APKS, APKM, ZIP en un seul APK", Icons.Filled.Android, SE.Green, C2),
    Tool("frames", "Vidéo → Images", "Toutes les images dans un ZIP", Icons.Filled.PhotoLibrary, SE.Orange, C2),
    Tool("transcribe", "Transcription", "Vidéo / audio en texte TXT + SRT", Icons.Filled.Subtitles, SE.Blue, C2),
    Tool("ocr", "Photo → texte", "Reconnaissance de texte (OCR)", Icons.Filled.DocumentScanner, SE.Cyan, C2),
    Tool("tts", "Texte → voix", "Transforme un texte en MP3", Icons.Filled.RecordVoiceOver, SE.Purple, C2),
    Tool("units", "Unités & devises", "Longueur, poids, °C, données, €/$…", Icons.Filled.Calculate, SE.Orange, C2),
    Tool("video", "Éditeur vidéo", "Couper, fusionner, vitesse, musique, diaporama", Icons.Filled.Movie, SE.Red, C3),
    Tool("audio", "Éditeur audio", "Couper, volume, basses, karaoké, fusion", Icons.Filled.GraphicEq, SE.Blue, C3),
    Tool("image", "Éditeur photo", "Taille, rotation, N&B, filigrane, collage", Icons.Filled.Image, SE.Green, C3),
    Tool("pdf", "Outils PDF", "Fusionner, séparer, pivoter, mot de passe", Icons.Filled.PictureAsPdf, SE.Red, C3),
    Tool("compress", "Compresseur", "Réduis la taille de tes fichiers", Icons.Filled.Compress, Color(0xFF14B8A6), C3),
    Tool("qr", "QR codes", "Scanner et créer (lien, Wi‑Fi, contact…)", Icons.Filled.QrCode2, SE.Cyan, C4),
    Tool("rec", "Dictaphone", "Enregistre ta voix en M4A ou MP3", Icons.Filled.Mic, SE.Red, C4),
    Tool("text", "Outils texte", "Majuscules, Base64, compteur, liens…", Icons.Filled.TextFields, SE.Purple, C4),
    Tool("info", "Infos fichier", "Détails, métadonnées, MD5 / SHA-256", Icons.Filled.Fingerprint, SE.Orange, C4),
    Tool("about", "À propos", "SuperEssence Studio", Icons.Filled.Info, SE.Muted, C4),
)

@Composable
fun HomeScreen(open: (String) -> Unit) {
    val incoming by Shared.incoming.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .background(SE.Bg)
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) { Header() }
        if (incoming.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Section("${incoming.size} fichier(s) partagé(s) avec Fluxo") {
                    Hint("Que veux-tu en faire ?")
                    Row {
                        listOf("transfer" to "Envoyer", "convert" to "Convertir", "compress" to "Compresser").forEach { (r, l) ->
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
        tools.groupBy { it.cat }.forEach { (cat, list) ->
            item(span = { GridItemSpan(2) }) {
                Text(cat, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SE.Text, modifier = Modifier.padding(top = 8.dp))
            }
            items(list) { t -> ToolCard(t) { open(t.route) } }
        }
        item(span = { GridItemSpan(2) }) {
            Text(
                "Créé par SuperEssence Studio",
                color = SE.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Header() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.drawable.app_logo),
            contentDescription = "Logo Fluxo",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(10.dp))
        Text("Fluxo", fontSize = 28.sp, fontWeight = FontWeight.Black, color = SE.Text)
        Text("${tools.size - 1} outils · Transfère. Convertis. Édite.", color = SE.Cyan, fontSize = 14.sp)
    }
}

@Composable
private fun ToolCard(t: Tool, onClick: () -> Unit) {
    Column(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(SE.SurfaceHi, SE.Surface)))
            .border(1.dp, t.color.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .background(t.color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(t.icon, null, tint = t.color, modifier = Modifier.size(28.dp)) }
        Column {
            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = SE.Text)
            Text(t.subtitle, color = SE.Muted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3)
        }
    }
}
