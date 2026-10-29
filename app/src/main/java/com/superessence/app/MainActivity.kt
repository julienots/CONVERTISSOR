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
import com.superessence.app.ui.AboutScreen
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
                val nav = rememberNavController()
                val sharedText by Shared.sharedText.collectAsState()
                LaunchedEffect(sharedText) {
                    if (sharedText != null && nav.currentDestination?.route != "youtube") nav.navigate("youtube")
                }
                val back: () -> Unit = { nav.popBackStack() }
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
