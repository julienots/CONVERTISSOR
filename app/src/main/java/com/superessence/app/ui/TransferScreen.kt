package com.superessence.app.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.superessence.app.core.Storage
import com.superessence.app.transfer.Peer
import com.superessence.app.transfer.Transfer
import kotlinx.coroutines.launch

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun QrImage(text: String) {
    val bmp = remember(text) { qrBitmap(text) }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bmp.asImageBitmap(), "QR code",
            Modifier
                .size(220.dp)
                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(16.dp))
                .padding(10.dp),
        )
    }
}

@Composable
fun TransferScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    ToolScaffold("Transfert", onBack) {
        TabRow(selectedTabIndex = tab, containerColor = SE.Surface) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Envoyer") }, icon = { Icon(Icons.Filled.Send, null) })
            Tab(tab == 1, { tab = 1 }, text = { Text("Recevoir") }, icon = { Icon(Icons.Filled.Download, null) })
        }
        Hint("Installe SuperEssence sur les deux appareils et connecte-les au même Wi‑Fi (ou au point d'accès créé par le récepteur). Aucune limite de taille : 2 Go et plus.")
        CurrentTransfer()
        if (tab == 0) SendTab() else ReceiveTab()
        Events()
    }
}

@Composable
private fun CurrentTransfer() {
    val ui by Transfer.ui.collectAsState()
    val c = ui.current ?: return
    Section(if (c.sending) "Envoi à ${c.peer}" else "Réception depuis ${c.peer}") {
        Text("Fichier ${c.fileIndex}/${c.fileCount} : ${c.fileName}", maxLines = 1)
        val p = if (c.total > 0) c.bytes.toFloat() / c.total else 0f
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = SE.Cyan)
        val left = if (c.speedBps > 0) (c.total - c.bytes) / c.speedBps else -1
        Text(
            "${(p * 100).toInt()} % · ${Storage.humanSize(c.bytes)} / ${Storage.humanSize(c.total)} · ${Storage.humanSize(c.speedBps)}/s" +
                (if (left >= 0) " · reste ${left}s" else ""),
            color = SE.Muted, fontSize = 12.sp,
        )
    }
}

@Composable
private fun SendTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<Uri>().apply { addAll(Shared.takePending()) } }
    val peers = remember { mutableStateListOf<Peer>() }
    var searching by remember { mutableStateOf(false) }
    var manualIp by rememberSaveable { mutableStateOf("") }
    val pick = rememberPicker(true) { files.addAll(it.filter { u -> u !in files }) }

    fun sendTo(peer: Peer) {
        if (files.isEmpty()) {
            Toast.makeText(ctx, "Choisis d'abord des fichiers", Toast.LENGTH_SHORT).show()
            return
        }
        Transfer.send(ctx, peer, files.toList())
        Toast.makeText(ctx, "Envoi vers ${peer.name}…", Toast.LENGTH_SHORT).show()
    }

    fun search() {
        if (searching) return
        searching = true
        peers.clear()
        scope.launch {
            runCatching { Transfer.discover { p -> if (peers.none { it.host == p.host }) peers += p } }
            searching = false
        }
    }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@rememberLauncherForActivityResult
        val peer = Transfer.parseQr(text)
        if (peer == null) Toast.makeText(ctx, "QR code non reconnu", Toast.LENGTH_SHORT).show() else sendTo(peer)
    }

    Section("1. Fichiers à envoyer") {
        PickBox(if (files.isEmpty()) "Choisir des fichiers" else "Ajouter des fichiers", pick)
        if (files.isNotEmpty()) {
            val total = files.sumOf { Storage.size(ctx, it).coerceAtLeast(0) }
            Text("${files.size} fichier(s) · ${Storage.humanSize(total)}", color = SE.Cyan, fontWeight = FontWeight.SemiBold)
            FileList(files) { files.remove(it) }
        }
    }
    Section("2. Choisir le destinataire") {
        Hint("Sur l'autre appareil : SuperEssence → Transfert → Recevoir.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { search() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text("Rechercher")
            }
            OutlinedButton(onClick = {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scanne le QR code affiché sur l'appareil qui reçoit")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scanner QR")
            }
        }
        LaunchedEffect(Unit) { search() }
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Hint("Recherche des appareils à proximité…")
            }
        }
        if (!searching && peers.isEmpty()) Hint("Aucun appareil trouvé. Vérifie que l'autre appareil est en mode « Recevoir » sur le même réseau, ou scanne son QR code.")
        peers.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SE.SurfaceHi, RoundedCornerShape(14.dp))
                    .clickable { sendTo(p) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = SE.Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Text(p.host, color = SE.Muted, fontSize = 12.sp)
                }
                Pill("Envoyer")
            }
        }
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it.trim() },
            label = { Text("Ou adresse IP du destinataire") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (manualIp.isNotBlank()) {
            BigButton("Envoyer à $manualIp", Icons.Filled.Send) {
                val host = manualIp.substringBefore(':')
                val port = manualIp.substringAfter(':', Transfer.PORT.toString()).toIntOrNull() ?: Transfer.PORT
                sendTo(Peer(host, host, port))
            }
        }
    }
}

@Composable
private fun ReceiveTab() {
    val ctx = LocalContext.current
    val ui by Transfer.ui.collectAsState()
    val hotspotPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) Transfer.startHotspot(ctx) else Toast.makeText(ctx, "Autorisation nécessaire pour créer le point d'accès", Toast.LENGTH_LONG).show()
    }

    Section("Mode réception") {
        if (!ui.receiving) {
            Hint("Active la réception pour que l'autre appareil te trouve.")
            BigButton("Recevoir des fichiers", Icons.Filled.Wifi) { Transfer.startReceiving(ctx) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SE.Green)
                Spacer(Modifier.width(8.dp))
                Text("En attente… visible sous « ${Transfer.deviceName(ctx)} »", color = SE.Green)
            }
            val ip = ui.addresses.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: ui.addresses.firstOrNull()
            if (ip != null) {
                Hint("L'expéditeur peut scanner ce QR code :")
                QrImage(Transfer.qrPayload(ctx, ip))
                Text("Adresse : $ip", color = SE.Muted, fontSize = 13.sp)
            } else {
                Text("Aucun réseau détecté : connecte-toi à un Wi‑Fi ou crée un point d'accès ci-dessous.", color = SE.Orange)
            }
            OutlinedButton(onClick = { Transfer.stopReceiving(ctx) }) {
                Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("Arrêter")
            }
        }
    }

    Section("Pas de Wi‑Fi ?") {
        val h = ui.hotspot
        if (h == null) {
            Hint("Crée un point d'accès local : l'autre appareil s'y connecte (scan du QR code Wi‑Fi avec l'appareil photo), puis scanne le QR SuperEssence.")
            OutlinedButton(onClick = {
                val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
                hotspotPerm.launch(perm)
            }) {
                Icon(Icons.Filled.WifiTethering, null); Spacer(Modifier.width(6.dp)); Text("Créer un point d'accès")
            }
        } else {
            Text("Réseau : ${h.ssid}", fontWeight = FontWeight.SemiBold)
            Text("Mot de passe : ${h.password}", color = SE.Muted)
            Hint("1) Sur l'autre téléphone, scanne ce QR avec l'appareil photo pour rejoindre le réseau :")
            QrImage("WIFI:T:WPA;S:${h.ssid};P:${h.password};;")
            TextButton(onClick = { Transfer.stopHotspot(ctx) }) { Text("Arrêter le point d'accès") }
        }
    }

    if (ui.received.isNotEmpty()) {
        Section("Fichiers reçus (Téléchargements/SuperEssence/Reçus)") {
            ui.received.take(30).forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Storage.openFile(ctx, f) }
                        .padding(vertical = 4.dp),
                ) {
                    Text(f.name, Modifier.weight(1f), maxLines = 1)
                    Text(Storage.humanSize(f.size), color = SE.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Events() {
    val ui by Transfer.ui.collectAsState()
    if (ui.events.isEmpty()) return
    Section("Historique") {
        ui.events.take(10).forEach { Text(it, fontSize = 13.sp, color = SE.Muted) }
    }
}
