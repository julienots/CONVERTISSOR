package com.superessence.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.superessence.app.tools.ImageTools
import com.superessence.app.tools.InfoTools
import com.superessence.app.tools.MediaTools
import com.superessence.app.tools.PdfTools
import com.superessence.app.ui.AboutScreen
import com.superessence.app.ui.AppsScreen
import com.superessence.app.ui.OpScreen
import com.superessence.app.ui.QrScreen
import com.superessence.app.ui.RecorderScreen
import com.superessence.app.ui.TextToolsScreen
import com.superessence.app.ui.TtsScreen
import com.superessence.app.ui.UnitsScreen
import com.superessence.app.ui.ApkScreen
import com.superessence.app.ui.CompressScreen
import com.superessence.app.ui.ConvertScreen
import com.superessence.app.ui.FramesScreen
import com.superessence.app.ui.HomeScreen
import com.superessence.app.ui.Shared
import com.superessence.app.ui.SuperEssenceTheme
import com.superessence.app.ui.TranscribeScreen
import com.superessence.app.ui.TransferScreen
import com.superessence.app.ui.YoutubeScreen
import com.superessence.app.ui.*
import com.superessence.app.tools.MoreMedia
import com.superessence.app.tools.MoreImages
import com.superessence.app.tools.MorePdf
import com.superessence.app.tools.MoreInfo
import com.superessence.app.core.Prefs
import android.graphics.Color as AColor
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        setContent {
            SuperEssenceTheme {
                val dark = when (Prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(AColor.TRANSPARENT) else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                LaunchedEffect(Prefs.keepScreenOn) {
                    if (Prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                val nav = rememberNavController()
                val sharedText by Shared.sharedText.collectAsState()
                LaunchedEffect(sharedText) {
                    if (sharedText != null && nav.currentDestination?.route != "youtube") nav.navigate("youtube")
                }
                val back: () -> Unit = { if (!nav.popBackStack()) finish() }
                NavHost(nav, startDestination = "home") {
                    composable("home") { HomeScreen { nav.navigate(it) } }
                    composable("transfer") { TransferScreen(back) }
                    composable("convert") { ConvertScreen(back) { r -> nav.navigate(r) } }
                    composable("apk") { ApkScreen(back) }
                    composable("youtube") { YoutubeScreen(back) }
                    composable("frames") { FramesScreen(back) }
                    composable("transcribe") { TranscribeScreen(back) }
                    composable("compress") { CompressScreen(back) }
                    composable("about") { AboutScreen(back) }
                    composable("video") { OpScreen("Vidéo", "video", "Choisis une ou plusieurs vidéos, puis une action.", MediaTools.videoOps + MoreMedia.video, back) }
                    composable("audio") { OpScreen("Audio", "audio", "Choisis un ou plusieurs sons, puis une action. Résultats en MP3.", MediaTools.audioOps + MoreMedia.audio, back) }
                    composable("image") { OpScreen("Photo", "image", "Choisis une ou plusieurs photos, puis une action.", ImageTools.ops + MoreImages.ops, back) }
                    composable("pdf") { OpScreen("PDF", "pdf", "Choisis un ou plusieurs PDF, puis une action.", PdfTools.ops + MorePdf.ops, back) }
                    composable("ocr") { OpScreen("Photo → texte", "ocr", "Choisis des photos ou captures d'écran contenant du texte.", InfoTools.ocrOps + MoreInfo.ocrOps, back) }
                    composable("info") { OpScreen("Infos d'un fichier", "info", "Choisis un ou plusieurs fichiers.", InfoTools.ops + MoreInfo.ops, back) }
                    composable("qr") { QrScreen(back) }
                    composable("tts") { TtsScreen(back) }
                    composable("rec") { RecorderScreen(back) }
                    composable("units") { UnitsScreen(back) }
                    composable("text") { TextToolsScreen(back) }
                    composable("apps") { AppsScreen(back) }
                    composable("files") { FilesScreen(back) }
                    composable("settings") { SettingsScreen(back) }
                    composable("calc") { CalcScreen(back) }
                    composable("percent") { PercentScreen(back) }
                    composable("money") { MoneyScreen(back) }
                    composable("dates") { DatesScreen(back) }
                    composable("health") { HealthScreen(back) }
                    composable("everyday") { EverydayScreen(back) }
                    composable("bases") { BasesScreen(back) }
                    composable("timer") { TimerScreen(back) }
                    composable("notes") { NotesScreen(back) }
                    composable("todo") { TodoScreen(back) }
                    composable("counter") { CounterScreen(back) }
                    composable("random") { RandomScreen(back) }
                    composable("password") { PasswordScreen(back) }
                    composable("colors") { ColorsScreen(back) }
                    composable("clock") { WorldClockScreen(back) }
                    composable("torch") { TorchScreen(back) }
                    composable("level") { LevelScreen(back) }
                    composable("sound") { SoundMeterScreen(back) }
                    composable("tone") { ToneScreen(back) }
                    composable("device") { DeviceScreen(back) }
                    composable("screentest") { ScreenTestScreen(back) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.superessence.app.core.Background.visible = true
    }

    override fun onStop() {
        com.superessence.app.core.Background.visible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            Shared.incoming.value = uris
        } else if (intent.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.contains("http") }?.let { Shared.sharedText.value = it }
        }
    }
}
