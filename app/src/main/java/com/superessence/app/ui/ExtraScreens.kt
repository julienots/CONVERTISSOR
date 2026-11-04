package com.superessence.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.superessence.app.convert.Docs
import com.superessence.app.core.JobState
import com.superessence.app.core.Jobs
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import com.superessence.app.tools.AppEntry
import com.superessence.app.tools.Apps
import com.superessence.app.tools.MediaTools
import com.superessence.app.tools.Speech
import com.superessence.app.tools.Units
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private fun copy(ctx: android.content.Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fluxo", text))
    Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show()
}

// ------------------------------------------------------------------ QR codes

@Composable
fun QrScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var type by rememberSaveable { mutableStateOf("text") }
    var a by rememberSaveable { mutableStateOf("") }
    var b by rememberSaveable { mutableStateOf("") }
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { scanned = it } }

    val payload = when (type) {
        "wifi" -> if (a.isBlank()) "" else "WIFI:T:WPA;S:$a;P:$b;;"
        "mail" -> if (a.isBlank()) "" else "mailto:$a"
        "tel" -> if (a.isBlank()) "" else "tel:$a"
        "sms" -> if (a.isBlank()) "" else "SMSTO:$a:$b"
        else -> a
    }

    ToolScaffold("QR codes", onBack) {
        Section("Scanner") {
            BigButton("Scanner un QR code / code-barres", Icons.Filled.QrCodeScanner) {
                scan.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false).setPrompt("Vise le code"))
            }
            scanned?.let { s ->
                Text(s, fontFamily = FontFamily.Monospace)
                Row {
                    TextButton(onClick = { copy(ctx, s) }) { Text("Copier") }
                    if (s.startsWith("http")) {
                        TextButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s))) }
                        }) { Text("Ouvrir le lien") }
                    }
                }
            }
            Hint("Pour lire un QR code dans une photo : outil « Photo → texte ».")
        }
        Section("Créer un QR code") {
            val opts = listOf("text" to "Texte / lien", "wifi" to "Wi‑Fi", "mail" to "E-mail", "tel" to "Téléphone", "sms" to "SMS")
            ChoiceChips(opts, opts.firstOrNull { it.first == type }, { it.second }) { type = it.first }
            OutlinedTextField(
                a, { a = it },
                label = {
                    Text(when (type) { "wifi" -> "Nom du réseau"; "mail" -> "Adresse e-mail"; "tel", "sms" -> "Numéro"; else -> "Texte ou lien" })
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == "wifi" || type == "sms") {
                OutlinedTextField(b, { b = it }, label = { Text(if (type == "wifi") "Mot de passe" else "Message") }, modifier = Modifier.fillMaxWidth())
            }
            if (payload.isNotBlank()) {
                QrImage(payload)
                OutlinedButton(onClick = {
                    val t = Storage.openOutput(ctx, "QR_${MediaTools.stamp()}.png")
                    Docs.writeBitmap(qrBitmap(payload, 1024), "png", 100, t.stream)
                    val f = t.finish()
                    Toast.makeText(ctx, "Enregistré : ${f.name}", Toast.LENGTH_SHORT).show()
                    Storage.shareFiles(ctx, listOf(f))
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Save, null); Text("  Enregistrer / partager l'image")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Texte → voix

@Composable
fun TtsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var voice by remember { mutableStateOf(Speech.voices.first()) }
    var rate by rememberSaveable { mutableStateOf(1f) }
    var pitch by rememberSaveable { mutableStateOf(1f) }
    ToolScaffold("Texte → voix", onBack) {
        Section("Texte") {
            OutlinedTextField(text, { text = it }, label = { Text("Écris ou colle ton texte") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            Hint("${text.length} caractères · aucune limite, les longs textes sont découpés automatiquement.")
        }
        Section("Voix") {
            ChoiceChips(Speech.voices, voice, { it.second }) { voice = it }
            Text("Vitesse : ×%.1f".format(rate))
            Slider(rate, { rate = it }, valueRange = 0.5f..2f)
            Text("Hauteur : ×%.1f".format(pitch))
            Slider(pitch, { pitch = it }, valueRange = 0.5f..2f)
        }
        BigButton("Créer le MP3", Icons.Filled.RecordVoiceOver, enabled = text.isNotBlank()) {
            val t = text; val v = voice.first; val r = rate; val p = pitch
            Jobs.startNew("tts", "Texte → voix") {
                val f = Speech.toMp3(ctx.applicationContext, t, v, r, p, this)
                JobState.Done("Fichier audio créé", listOf(f))
            }
        }
        JobGroup("tts")
        Hint("Utilise les voix installées sur ton téléphone (Réglages → Synthèse vocale pour en ajouter).")
    }
}

// ------------------------------------------------------------------ Dictaphone

@Composable
fun RecorderScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var last by remember { mutableStateOf<SavedFile?>(null) }
    var mp3 by rememberSaveable { mutableStateOf(false) }

    fun start() {
        val f = File(Storage.workDir(ctx, "rec"), "enregistrement.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(192_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(f.absolutePath)
        r.prepare()
        r.start()
        recorder = r; file = f; started = System.currentTimeMillis(); last = null
    }

    fun stop() {
        val r = recorder ?: return
        runCatching { r.stop() }
        r.release()
        recorder = null
        val f = file ?: return
        val name = "Enregistrement_${MediaTools.stamp()}"
        if (mp3) {
            Jobs.startNew("rec", "Dictaphone") {
                val out = File(f.parentFile, "rec.mp3")
                com.superessence.app.convert.FF.run(listOf("-i", f.absolutePath, "-c:a", "libmp3lame", "-q:a", "2", out.absolutePath), elapsed) {
                    update(it, "Conversion en MP3…")
                }
                JobState.Done("Enregistrement sauvegardé", listOf(Storage.save(ctx.applicationContext, out, "$name.mp3")))
            }
        } else {
            last = Storage.save(ctx, f, "$name.m4a")
        }
    }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) runCatching { start() }.onFailure { Toast.makeText(ctx, "Micro indisponible : ${it.message}", Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(recorder) {
        while (recorder != null) {
            elapsed = System.currentTimeMillis() - started
            delay(200)
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { recorder?.stop() }; recorder?.release() } }

    ToolScaffold("Dictaphone", onBack) {
        Section {
            val s = elapsed / 1000
            Text("%02d:%02d".format(s / 60, s % 60), fontSize = 56.sp, fontWeight = FontWeight.Black,
                color = if (recorder != null) SE.Red else SE.Text, modifier = Modifier.align(Alignment.CenterHorizontally))
            if (recorder == null) {
                BigButton("Enregistrer", Icons.Filled.Mic, color = SE.Red) {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { start() }.onFailure { Toast.makeText(ctx, "Micro indisponible : ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        perm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            } else {
                BigButton("Arrêter et enregistrer", Icons.Filled.Stop) { stop() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enregistrer en MP3", Modifier.weight(1f))
                Switch(checked = mp3, onCheckedChange = { mp3 = it }, enabled = recorder == null)
            }
        }
        last?.let { f ->
            Section("Dernier enregistrement") {
                Text(f.name)
                Row {
                    TextButton(onClick = { Storage.openFile(ctx, f) }) { Text("Écouter") }
                    TextButton(onClick = { Storage.shareFiles(ctx, listOf(f)) }) { Text("Partager") }
                }
            }
        }
        JobGroup("rec")
        Hint("Garde l'écran ouvert pendant l'enregistrement. Les fichiers vont dans Téléchargements/Fluxo.")
    }
}

// ------------------------------------------------------------------ Unités et devises

@Composable
fun UnitsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cat by rememberSaveable { mutableStateOf(Units.categories.keys.first()) }
    var input by rememberSaveable { mutableStateOf("1") }
    val units = Units.categories[cat] ?: emptyList()
    var from by remember(cat) { mutableStateOf(units.first()) }
    var to by remember(cat) { mutableStateOf(units.getOrElse(2) { units.last() }) }

    var money by rememberSaveable { mutableStateOf("100") }
    var cFrom by rememberSaveable { mutableStateOf("EUR") }
    var cTo by rememberSaveable { mutableStateOf("USD") }
    var rates by remember { mutableStateOf<Map<String, Double>?>(null) }
    var ratesBase by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cFrom) {
        err = null
        runCatching { withContext(Dispatchers.IO) { Units.rates(cFrom) } }
            .onSuccess { rates = it; ratesBase = cFrom }
            .onFailure { err = "Taux indisponibles (connexion internet nécessaire)" }
    }

    fun fmt(d: Double) = if (d != 0.0 && (kotlin.math.abs(d) >= 1e9 || kotlin.math.abs(d) < 1e-4)) "%.4e".format(d) else "%,.6f".format(Locale.FRANCE, d).trimEnd('0').trimEnd(',')

    ToolScaffold("Unités & devises", onBack) {
        Section("Unités") {
            ChoiceChips(Units.categories.keys.toList(), cat, { it }) { cat = it }
            OutlinedTextField(input, { input = it }, label = { Text("Valeur") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("De")
            ChoiceChips(units, from, { it.name }) { from = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                IconButton(onClick = { val f = from; from = to; to = f }) { Icon(Icons.Filled.SwapVert, "Inverser", tint = SE.Cyan) }
            }
            Text("Vers")
            ChoiceChips(units, to, { it.name }) { to = it }
            val v = input.replace(',', '.').replace(" ", "").toDoubleOrNull()
            if (v != null) {
                Text("${input} ${from.name} = ${fmt(Units.convert(cat, v, from, to))} ${to.name}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SE.Cyan)
            }
        }
        Section("Devises (taux du jour)") {
            OutlinedTextField(money, { money = it }, label = { Text("Montant") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Text("De")
            ChoiceChips(Units.currencies, cFrom, { it }) { cFrom = it }
            Text("Vers")
            ChoiceChips(Units.currencies, cTo, { it }) { cTo = it }
            val m = money.replace(',', '.').replace(" ", "").toDoubleOrNull()
            val r = rates?.get(cTo)
            when {
                err != null -> Text(err!!, color = SE.Orange)
                m != null && r != null && ratesBase == cFrom ->
                    Text("$money $cFrom = ${"%,.2f".format(Locale.FRANCE, m * r)} $cTo", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = SE.Cyan)
                else -> Hint("Chargement des taux…")
            }
            TextButton(onClick = {
                scope.launch {
                    err = null
                    runCatching { withContext(Dispatchers.IO) { Units.rates(cFrom) } }
                        .onSuccess { rates = it; ratesBase = cFrom }
                        .onFailure { err = "Taux indisponibles (connexion internet nécessaire)" }
                }
            }) { Text("Actualiser") }
        }
    }
}

// ------------------------------------------------------------------ Outils texte

@Composable
fun TextToolsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("") }
    val actions: List<Pair<String, (String) -> String>> = listOf(
        "MAJUSCULES" to { s -> s.uppercase() },
        "minuscules" to { s -> s.lowercase() },
        "Première Lettre" to { s -> s.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } } },
        "Inverser" to { s -> s.reversed() },
        "Trier les lignes" to { s -> s.lines().sorted().joinToString("\n") },
        "Sans doublons" to { s -> s.lines().distinct().joinToString("\n") },
        "Espaces propres" to { s -> s.lines().joinToString("\n") { it.trim().replace(Regex("\\s+"), " ") }.replace(Regex("\n{3,}"), "\n\n") },
        "Base64 →" to { s -> Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP) },
        "← Base64" to { s -> runCatching { String(Base64.decode(s.trim(), Base64.DEFAULT)) }.getOrElse { "Base64 invalide" } },
        "URL encoder" to { s -> URLEncoder.encode(s, "UTF-8") },
        "URL décoder" to { s -> runCatching { URLDecoder.decode(s, "UTF-8") }.getOrElse { "Texte invalide" } },
        "Sans accents" to { s -> java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") },
        "Extraire les liens" to { s -> Regex("https?://\\S+").findAll(s).joinToString("\n") { it.value } },
        "Extraire les e-mails" to { s -> Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").findAll(s).joinToString("\n") { it.value } },
    )
    ToolScaffold("Outils texte", onBack) {
        Section("Texte") {
            OutlinedTextField(text, { text = it }, label = { Text("Écris ou colle ton texte") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            Hint("${text.length} caractères · $words mots · ${if (text.isEmpty()) 0 else text.lines().size} lignes · ≈ ${(words / 200.0).let { if (it < 1) "<1" else "%.0f".format(it) }} min de lecture")
        }
        Section("Transformer") {
            ChoiceChips(actions, null, { it.first }) { result = it.second(text) }
        }
        if (result.isNotEmpty()) {
            Section("Résultat") {
                Text(result, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Row {
                    TextButton(onClick = { copy(ctx, result) }) { Text("Copier") }
                    TextButton(onClick = { text = result }) { Text("Utiliser") }
                    TextButton(onClick = {
                        val t = Storage.openOutput(ctx, "Texte_${MediaTools.stamp()}.txt")
                        t.stream.write(result.toByteArray())
                        Storage.shareFiles(ctx, listOf(t.finish()))
                    }) { Text("TXT") }
                    TextButton(onClick = {
                        val t = Storage.openOutput(ctx, "Texte_${MediaTools.stamp()}.pdf")
                        Docs.textToPdf(result, t.stream)
                        Storage.shareFiles(ctx, listOf(t.finish()))
                    }) { Text("PDF") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Mes applis (APK)

@Composable
fun AppsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Apps.list(ctx) } }

    ToolScaffold("Mes applis → APK", onBack) {
        Hint("Sauvegarde l'APK de n'importe quelle appli installée (pour la partager ou la réinstaller). Les applis en plusieurs morceaux sont enregistrées en .apks, installable avec l'outil XAPK → APK.")
        JobGroup("apps")
        OutlinedTextField(query, { query = it }, label = { Text("Rechercher une appli") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Afficher les applis système", Modifier.weight(1f))
            Switch(checked = system, onCheckedChange = { system = it })
        }
        val list = apps
        if (list == null) {
            Hint("Chargement des applis…")
        } else {
            val shown = list.filter { (system || !it.system) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
            Hint("${shown.size} applis")
            shown.take(80).forEach { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SE.Surface, RoundedCornerShape(14.dp))
                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${app.version} · ${Storage.humanSize(app.size)}" + if (app.apks.size > 1) " · ${app.apks.size} morceaux" else "",
                            color = SE.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = {
                        Jobs.startNew("apps", "APK de ${app.label}") {
                            val f = Apps.export(ctx.applicationContext, app, this)
                            JobState.Done("${app.label} sauvegardé (Téléchargements/Fluxo/Applis)", listOf(f))
                        }
                    }) { Icon(Icons.Filled.Download, "Sauvegarder l'APK", tint = SE.Cyan) }
                }
            }
            if (shown.size > 80) Hint("Affine la recherche pour voir les autres applis.")
        }
    }
}
