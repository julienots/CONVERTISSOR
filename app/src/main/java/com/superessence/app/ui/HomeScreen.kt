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

data class Tool(val route: String, val title: String, val subtitle: String, val icon: ImageVector, val color: Color)

val tools = listOf(
    Tool("transfer", "Transfert", "Appareil → appareil, jusqu'à 2 Go et plus", Icons.Filled.SwapHoriz, SE.Cyan),
    Tool("convert", "Convertir", "Vidéo, audio, image, PDF, texte, Word, archives", Icons.Filled.Transform, SE.Purple),
    Tool("apk", "XAPK → APK", "XAPK, APKS, APKM, ZIP en un seul APK", Icons.Filled.Android, SE.Green),
    Tool("youtube", "Lien → MP3", "YouTube en MP3, M4A ou MP4", Icons.Filled.SmartDisplay, SE.Red),
    Tool("frames", "Vidéo → Images", "Toutes les images dans un ZIP", Icons.Filled.PhotoLibrary, SE.Orange),
    Tool("transcribe", "Transcription", "Vidéo / audio en texte TXT + SRT", Icons.Filled.Subtitles, SE.Blue),
    Tool("compress", "Compresseur", "Réduis la taille de tes fichiers", Icons.Filled.Compress, Color(0xFF14B8A6)),
    Tool("about", "À propos", "SuperEssence Studio", Icons.Filled.Info, SE.Muted),
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
        items(tools) { t -> ToolCard(t) { open(t.route) } }
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
        Text("Transfère. Convertis. Compresse.", color = SE.Cyan, fontSize = 14.sp)
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
