package com.superessence.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.BuildConfig
import com.superessence.app.R
import com.superessence.app.apk.AppPackage
import com.superessence.app.apk.ApkTools
import com.superessence.app.convert.Compressor
import com.superessence.app.convert.Converter
import com.superessence.app.convert.Formats
import com.superessence.app.convert.Kind
import com.superessence.app.convert.Level
import com.superessence.app.convert.Media
import com.superessence.app.convert.Target
import com.superessence.app.core.JobState
import com.superessence.app.core.Jobs
import com.superessence.app.core.Storage
import com.superessence.app.transcribe.Transcriber
import com.superessence.app.youtube.VideoInfo
import com.superessence.app.youtube.YouTube
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

private fun kindOf(ctx: Context, uri: Uri): Kind {
    val name = Storage.displayName(ctx, uri)
    return Formats.kind(name, Storage.mimeOf(ctx, uri, name))
}

// ------------------------------------------------------------------ Convertir

@Composable
fun ConvertScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val files = remember { mutableStateListOf<Uri>().apply { addAll(Shared.takePending()) } }
    var target by remember { mutableStateOf<Target?>(null) }
    val pick = rememberPicker(true) { picked ->
        files.addAll(picked.filter { it !in files })
        target = null
    }
    val kind = files.firstOrNull()?.let { kindOf(ctx, it) }
    val busy = isBusy("convert")

    ToolScaffold("Convertir", onBack) {
        Section("Fichiers") {
            PickBox(if (files.isEmpty()) "Choisir un ou plusieurs fichiers" else "Ajouter des fichiers", pick)
            if (files.isNotEmpty()) FileList(files) { files.remove(it); target = null }
        }
        if (kind != null) {
            Section("Convertir en…") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("Type détecté : ${kind.label}")
                }
                ChoiceChips(Formats.targets(kind), target, { it.label }) { target = it }
                val t = target
                if (t != null) {
                    BigButton("Convertir en ${t.label}", Icons.Filled.Transform, enabled = !busy) {
                        when (t.id) {
                            "transcribe" -> { Shared.handOff(listOf(files.first())); open("transcribe") }
                            "apk" -> { Shared.handOff(listOf(files.first())); open("apk") }
                            else -> {
                                val list = files.toList()
                                Jobs.start("convert") { Converter.run(ctx.applicationContext, list, kind, t, this) }
                            }
                        }
                    }
                }
            }
        }
        JobPanel("convert")
        Section("Conversions possibles") {
            Hint("• Vidéo → MP3, M4A, WAV, OGG, FLAC, OPUS, MP4, MKV, WEBM, AVI, MOV, GIF, images, texte")
            Hint("• Audio → MP3, M4A, WAV, OGG, FLAC, OPUS, texte")
            Hint("• Image (JPG, PNG, WEBP, HEIC…) → JPG, PNG, WEBP, PDF")
            Hint("• Texte / HTML / Markdown → PDF · Word (DOCX) → TXT, PDF")
            Hint("• PDF → TXT, images PNG/JPG")
            Hint("• ZIP, 7Z, RAR, TAR, GZ → extraction · tout fichier → ZIP")
            Hint("• XAPK / APKS / APKM → APK")
        }
    }
}

// ------------------------------------------------------------------ XAPK → APK

private object ApkUi {
    val pkg = MutableStateFlow<AppPackage?>(null)
    val single = MutableStateFlow<File?>(null)
}

@Composable
fun ApkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pkg by ApkUi.pkg.collectAsState()
    val single by ApkUi.single.collectAsState()
    val installMsg by ApkTools.installResult.collectAsState()
    val busy = isBusy("apk")

    fun analyze(uri: Uri) {
        ApkUi.pkg.value = null
        ApkUi.single.value = null
        ApkTools.installResult.value = null
        Jobs.start("apk") {
            val p = ApkTools.analyze(ctx.applicationContext, uri, this)
            ApkUi.pkg.value = p
            JobState.Done(
                if (p.isPlainApk) "C'est déjà un APK : tu peux l'installer directement."
                else "Analyse terminée : ${p.apkEntries.size} APK trouvé(s)"
            )
        }
    }
    val pick = rememberPicker(false) { analyze(it.first()) }
    LaunchedEffect(Unit) { Shared.takePending().firstOrNull()?.let { analyze(it) } }

    fun install(block: () -> Unit) {
        if (!ApkTools.canInstall(ctx)) {
            Toast.makeText(ctx, "Autorise SuperEssence à installer des applications, puis réessaie", Toast.LENGTH_LONG).show()
            ApkTools.openInstallPermission(ctx)
        } else {
            block()
        }
    }

    ToolScaffold("XAPK → APK", onBack) {
        Section {
            PickBox("Choisir un XAPK, APKS, APKM, ZIP ou APK", pick)
            Hint("Les morceaux (splits) sont fusionnés en un seul APK installable, puis signé sur ton appareil.")
        }
        val p = pkg
        if (p != null) {
            Section(p.label ?: p.sourceName) {
                p.packageName?.let { Text(it, color = SE.Muted, fontSize = 13.sp) }
                if (!p.isPlainApk) {
                    Text("${p.apkEntries.size} APK · ${p.obbEntries.size} OBB", color = SE.Cyan)
                    p.apkEntries.take(12).forEach { Hint("• " + it.substringAfterLast('/')) }
                }
                if (!p.isPlainApk) {
                    BigButton("Convertir en un seul APK", Icons.Filled.Android, enabled = !busy) {
                        Jobs.start("apk") {
                            val (saved, file) = ApkTools.toSingleApk(ctx.applicationContext, p, this)
                            ApkUi.single.value = file
                            JobState.Done("APK créé : ${saved.name}", listOf(saved))
                        }
                    }
                }
                OutlinedButton(onClick = {
                    install {
                        Jobs.start("apk") {
                            ApkTools.install(ctx.applicationContext, if (p.isPlainApk) null else p, if (p.isPlainApk) p.file else single, this)
                            JobState.Done("Installation lancée : confirme sur l'écran Android")
                        }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.InstallMobile, null)
                    Text(if (single != null) "  Installer l'APK créé" else "  Installer directement")
                }
                if (!p.isPlainApk) {
                    OutlinedButton(onClick = {
                        Jobs.start("apk") {
                            val out = ApkTools.extractAll(ctx.applicationContext, p)
                            JobState.Done("${out.size} fichier(s) extraits", out)
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Unarchive, null)
                        Text("  Extraire tous les APK / OBB")
                    }
                }
                if (p.obbEntries.isNotEmpty()) {
                    Hint("Les fichiers OBB doivent être copiés dans Android/obb/${p.packageName ?: "<paquet>"}/ avec un gestionnaire de fichiers.")
                }
            }
        }
        installMsg?.let { Text(it, color = if (it.startsWith("Échec")) SE.Red else SE.Green) }
        JobPanel("apk")
        Hint("Un APK fusionné est re-signé : désinstalle d'abord la version du Play Store si l'installation est refusée.")
    }
}

// ------------------------------------------------------------------ Lien → MP3

private object YtUi {
    val video = MutableStateFlow<VideoInfo?>(null)
}

@Composable
fun YoutubeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val shared by Shared.sharedText.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("mp3") }
    val video by YtUi.video.collectAsState()
    val busy = isBusy("yt")

    fun analyze() {
        val u = url.trim()
        if (!u.startsWith("http")) {
            Toast.makeText(ctx, "Colle un lien valide", Toast.LENGTH_SHORT).show(); return
        }
        YtUi.video.value = null
        Jobs.start("yt") {
            update(-1f, "Analyse du lien…")
            val v = YouTube.fetch(u)
            YtUi.video.value = v
            JobState.Done("Prêt : « ${v.title} »")
        }
    }
    LaunchedEffect(shared) {
        shared?.let { t ->
            Regex("https?://\\S+").find(t)?.value?.let { url = it; analyze() }
            Shared.sharedText.value = null
        }
    }

    ToolScaffold("Lien → MP3", onBack) {
        Section("Lien YouTube") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("https://youtube.com/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.primaryClip?.getItemAt(0)?.text?.toString()?.let { url = it.trim() }
                    }) { Icon(Icons.Filled.ContentPaste, "Coller") }
                },
            )
            BigButton("Analyser le lien", Icons.Filled.Search, enabled = !busy) { analyze() }
            Hint("Astuce : dans YouTube, « Partager » → SuperEssence.")
        }
        val v = video
        if (v != null) {
            Section(v.title) {
                Text(v.uploader, color = SE.Muted)
                if (v.durationSec > 0) Text("Durée : %d:%02d".format(v.durationSec / 60, v.durationSec % 60), color = SE.Muted)
                ChoiceChips(listOf("mp3", "m4a", "mp4"), format, { it.uppercase() }) { format = it }
                BigButton("Télécharger en ${format.uppercase()}", Icons.Filled.Download, enabled = !busy) {
                    val f = format
                    Jobs.start("yt") { YouTube.run(ctx.applicationContext, v, f, this) }
                }
            }
        }
        JobPanel("yt")
        Hint("À utiliser uniquement pour des contenus dont tu as les droits (tes vidéos, contenus libres…).")
    }
}

// ------------------------------------------------------------------ Vidéo → Images

@Composable
fun FramesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var video by remember { mutableStateOf(Shared.takePending().firstOrNull()) }
    var fps by rememberSaveable { mutableStateOf(1.0) }
    var format by rememberSaveable { mutableStateOf("jpg") }
    val pick = rememberPicker(false) { video = it.first() }
    val busy = isBusy("frames")
    val rates = listOf(0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 0.0)

    ToolScaffold("Vidéo → Images", onBack) {
        Section("Vidéo") {
            PickBox(if (video == null) "Choisir une vidéo" else "Changer de vidéo", pick)
            video?.let { FileList(listOf(it)) }
        }
        Section("Réglages") {
            Text("Nombre d'images")
            ChoiceChips(rates, fps, { r ->
                when {
                    r == 0.0 -> "Toutes"
                    r < 1 -> "1 toutes les ${(1 / r).toInt()} s"
                    else -> "${r.toInt()} / s"
                }
            }) { fps = it }
            Text("Format")
            ChoiceChips(listOf("jpg", "png"), format, { it.uppercase() }) { format = it }
            if (fps == 0.0) Hint("« Toutes » extrait chaque image : une minute de vidéo ≈ 1 800 images.")
        }
        val v = video
        BigButton("Extraire dans un ZIP", Icons.Filled.PhotoLibrary, enabled = v != null && !busy) {
            if (v != null) {
                val r = fps; val f = format
                Jobs.start("frames") {
                    val saved = Media.framesToZip(ctx.applicationContext, v, r, f) { update(it, "Extraction des images…") }
                    JobState.Done("ZIP d'images créé", listOf(saved))
                }
            }
        }
        JobPanel("frames")
    }
}

// ------------------------------------------------------------------ Transcription

@Composable
fun TranscribeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var media by remember { mutableStateOf(Shared.takePending().firstOrNull()) }
    var lang by remember { mutableStateOf(Transcriber.languages.first()) }
    val pick = rememberPicker(false) { media = it.first() }
    val busy = isBusy("stt")
    val state by Jobs.state("stt").collectAsState()

    ToolScaffold("Transcription", onBack) {
        Section("Vidéo ou audio") {
            PickBox(if (media == null) "Choisir une vidéo ou un audio" else "Changer de fichier", pick)
            media?.let { FileList(listOf(it)) }
        }
        Section("Langue parlée") {
            ChoiceChips(Transcriber.languages, lang, { it.label }) { lang = it }
            if (Transcriber.isInstalled(ctx, lang)) {
                Pill("Modèle installé", SE.Green)
            } else {
                Hint("Le modèle ${lang.label} (~${lang.sizeMb} Mo) sera téléchargé une seule fois. Ensuite tout fonctionne hors connexion.")
            }
        }
        val m = media
        BigButton("Transcrire en texte", Icons.Filled.Subtitles, enabled = m != null && !busy) {
            if (m != null) {
                val l = lang
                Jobs.start("stt") { Transcriber.run(ctx.applicationContext, m, l, this) }
            }
        }
        JobPanel("stt")
        val s = state
        if (s is JobState.Done && s.text != null) {
            Section("Aperçu du texte") {
                Text(s.text.take(3000) + if (s.text.length > 3000) "…" else "", fontSize = 14.sp)
                TextButton(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcription", s.text))
                    Toast.makeText(ctx, "Texte copié", Toast.LENGTH_SHORT).show()
                }) { Text("Copier le texte") }
            }
        }
        Hint("Fichiers créés : .txt (texte) et .srt (sous-titres avec horodatage).")
    }
}

// ------------------------------------------------------------------ Compresseur

@Composable
fun CompressScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember { mutableStateListOf<Uri>().apply { addAll(Shared.takePending()) } }
    var level by rememberSaveable { mutableStateOf(Level.MEDIUM) }
    var zipAll by rememberSaveable { mutableStateOf(false) }
    val pick = rememberPicker(true) { p -> files.addAll(p.filter { it !in files }) }
    val busy = isBusy("compress")

    ToolScaffold("Compresseur", onBack) {
        Section("Fichiers") {
            PickBox(if (files.isEmpty()) "Choisir des fichiers" else "Ajouter des fichiers", pick)
            if (files.isNotEmpty()) {
                Text("Total : ${Storage.humanSize(files.sumOf { Storage.size(ctx, it).coerceAtLeast(0) })}", color = SE.Cyan)
                FileList(files) { files.remove(it) }
            }
        }
        Section("Mode") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tout regrouper dans un ZIP")
                    Hint(if (zipAll) "Un seul fichier .zip" else "Images → JPG optimisé, vidéos → MP4 plus léger, audio → MP3, autres → ZIP")
                }
                Switch(checked = zipAll, onCheckedChange = { zipAll = it })
            }
            if (!zipAll) {
                Text("Niveau de compression")
                ChoiceChips(Level.entries.toList(), level, { it.label }) { level = it }
            }
        }
        BigButton("Compresser", Icons.Filled.Compress, enabled = files.isNotEmpty() && !busy) {
            val list = files.toList(); val l = level; val z = zipAll
            Jobs.start("compress") { Compressor.run(ctx.applicationContext, list, l, z, this) }
        }
        JobPanel("compress")
    }
}

// ------------------------------------------------------------------ À propos

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ToolScaffold("À propos", onBack) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                painterResource(R.drawable.app_logo), "Logo",
                Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(36.dp)),
            )
        }
        Section {
            Text("SuperEssence", fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("Version ${BuildConfig.VERSION_NAME}", color = SE.Muted)
            Text("Créé par SuperEssence Studio", color = SE.Cyan, fontWeight = FontWeight.SemiBold)
        }
        Section("Ce que fait l'application") {
            Hint("• Transfert ultra-rapide en Wi‑Fi local entre appareils SuperEssence, sans limite de taille")
            Hint("• Conversion audio, vidéo, image, PDF, texte, Word, archives")
            Hint("• XAPK / APKS / APKM → APK unique")
            Hint("• Liens YouTube → MP3 / M4A / MP4")
            Hint("• Vidéo → toutes les images en ZIP")
            Hint("• Transcription vidéo → texte (hors connexion)")
            Hint("• Compresseur de fichiers")
        }
        Section("Où sont mes fichiers ?") {
            Hint("Tous les résultats sont dans Téléchargements/SuperEssence (et Téléchargements/SuperEssence/Reçus pour les transferts).")
        }
        Section("Licences") {
            Hint("FFmpeg (LGPL/GPL), Vosk (Apache 2.0), NewPipe Extractor (GPL v3), ARSCLib (Apache 2.0), ZXing (Apache 2.0), PdfBox-Android (Apache 2.0), Apache Commons Compress, junrar.")
        }
    }
}
