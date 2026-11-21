package com.superessence.app.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.superessence.app.core.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------------ Lampe torche

@Composable
fun TorchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val camId = remember {
        runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
    }
    var mode by remember { mutableStateOf("off") }
    var strobeHz by rememberSaveable { mutableFloatStateOf(5f) }
    var screen by remember { mutableStateOf(false) }
    var screenColor by remember { mutableStateOf(Color.White) }

    fun set(on: Boolean) { camId?.let { id -> runCatching { cm.setTorchMode(id, on) } } }

    LaunchedEffect(mode, strobeHz) {
        when (mode) {
            "on" -> set(true)
            "off" -> set(false)
            "sos" -> {
                val pattern = listOf(1, 1, 1, 3, 3, 3, 1, 1, 1)
                while (isActive) {
                    for (p in pattern) { set(true); delay(200L * p); set(false); delay(200) }
                    delay(1200)
                }
            }
            "strobe" -> {
                val half = (500 / strobeHz).toLong().coerceAtLeast(20)
                while (isActive) { set(true); delay(half); set(false); delay(half) }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { set(false) } }

    if (screen) {
        BackHandler { screen = false }
        Box(Modifier.fillMaxSize().background(screenColor).clickable { screen = false }, contentAlignment = Alignment.BottomCenter) {
            Text("Touche l'écran pour revenir", color = Color.Gray, fontSize = 12.sp)
        }
        return
    }

    ToolScaffold("Lampe torche", onBack) {
        if (camId == null) Section { Text("Pas de flash détecté sur ce téléphone. Utilise l'écran blanc ci-dessous.") }
        else {
            Box(
                Modifier
                    .size(180.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(if (mode == "on") SE.Orange else SE.SurfaceHi, RoundedCornerShape(90.dp))
                    .clickable { mode = if (mode == "on") "off" else "on"; buzz(ctx) },
                contentAlignment = Alignment.Center,
            ) { Text(if (mode == "on") "Éteindre" else "Allumer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (mode == "on") Color.Black else SE.Text) }
            Section("Modes") {
                ChoiceChips(listOf("off", "on", "sos", "strobe"), mode, {
                    when (it) { "on" -> "Allumée"; "sos" -> "SOS"; "strobe" -> "Stroboscope"; else -> "Éteinte" }
                }) { mode = it }
                if (mode == "strobe") {
                    Text("Vitesse : ${strobeHz.roundToInt()} éclairs / s")
                    Slider(strobeHz, { strobeHz = it }, valueRange = 1f..15f)
                }
            }
        }
        Section("Écran lumineux") {
            Hint("Met tout l'écran en couleur (pense à monter la luminosité).")
            ChoiceChips(listOf(Color.White, Color(0xFFFFE4B5), Color.Red, Color(0xFF00C853), Color(0xFF2962FF)), screenColor,
                { when (it) { Color.White -> "Blanc"; Color.Red -> "Rouge"; Color(0xFF00C853) -> "Vert"; Color(0xFF2962FF) -> "Bleu"; else -> "Chaud" } }) { screenColor = it }
            OutlinedButton(onClick = { screen = true }, modifier = Modifier.fillMaxWidth()) { Text("Afficher") }
        }
    }
}

// ------------------------------------------------------------------ Niveau à bulle et boussole

@Composable
fun LevelScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var roll by remember { mutableFloatStateOf(0f) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var hasCompass by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val grav = FloatArray(3); val mag = FloatArray(3)
        var gotMag = false
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        for (i in 0..2) grav[i] = grav[i] * 0.8f + e.values[i] * 0.2f
                        val g = sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2]).coerceAtLeast(0.1f)
                        roll = Math.toDegrees(kotlin.math.asin((grav[0] / g).coerceIn(-1f, 1f).toDouble())).toFloat()
                        pitch = Math.toDegrees(kotlin.math.asin((grav[1] / g).coerceIn(-1f, 1f).toDouble())).toFloat()
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> { for (i in 0..2) mag[i] = mag[i] * 0.8f + e.values[i] * 0.2f; gotMag = true }
                }
                if (gotMag) {
                    val r = FloatArray(9)
                    if (SensorManager.getRotationMatrix(r, null, grav, mag)) {
                        val o = FloatArray(3)
                        SensorManager.getOrientation(r, o)
                        azimuth = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        val m = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (m == null) hasCompass = false else sm.registerListener(l, m, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }

    ToolScaffold("Niveau & boussole", onBack) {
        Section("Niveau à bulle") {
            val flat = abs(pitch) < 1 && abs(roll) < 1
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(200.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(SE.SurfaceHi, r)
                    drawCircle(SE.Muted, r * 0.15f, style = Stroke(2f))
                    drawLine(SE.Muted, Offset(center.x - r, center.y), Offset(center.x + r, center.y), 1f)
                    drawLine(SE.Muted, Offset(center.x, center.y - r), Offset(center.x, center.y + r), 1f)
                    val bx = center.x - (roll / 45f).coerceIn(-1f, 1f) * r * 0.85f
                    val by = center.y + (pitch / 45f).coerceIn(-1f, 1f) * r * 0.85f
                    drawCircle(if (flat) Color(0xFF22C55E) else Color(0xFFF59E0B), r * 0.13f, Offset(bx, by))
                }
            }
            ResultRow("Inclinaison gauche / droite", "%.1f°".format(roll), color = if (abs(roll) < 1) SE.Green else SE.Text)
            ResultRow("Inclinaison avant / arrière", "%.1f°".format(pitch), color = if (abs(pitch) < 1) SE.Green else SE.Text)
            Hint("Pose le téléphone à plat. La bulle devient verte quand c'est droit.")
        }
        Section("Boussole") {
            if (!hasCompass) Text("Pas de capteur magnétique sur ce téléphone.")
            else {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(200.dp)) {
                        val r = size.minDimension / 2
                        drawCircle(SE.SurfaceHi, r)
                        val a = Math.toRadians(-azimuth.toDouble())
                        val tip = Offset(center.x + (r * 0.8f * sin(a)).toFloat(), center.y - (r * 0.8f * kotlin.math.cos(a)).toFloat())
                        val tail = Offset(center.x - (r * 0.8f * sin(a)).toFloat(), center.y + (r * 0.8f * kotlin.math.cos(a)).toFloat())
                        drawLine(Color(0xFFE53935), center, tip, 10f)
                        drawLine(SE.Muted, center, tail, 10f)
                        drawCircle(SE.Text, 8f)
                    }
                }
                val dirs = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
                ResultRow("Direction", "${azimuth.roundToInt()}° " + dirs[((azimuth + 22.5f) / 45f).toInt() % 8], big = true)
                Hint("Éloigne-toi des objets métalliques. Fais un « 8 » avec le téléphone pour calibrer.")
            }
        }
    }
}

// ------------------------------------------------------------------ Sonomètre

@Composable
fun SoundMeterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var running by remember { mutableStateOf(false) }
    var db by remember { mutableFloatStateOf(0f) }
    var maxDb by remember { mutableFloatStateOf(0f) }
    var sum by remember { mutableStateOf(0.0) }
    var n by remember { mutableIntStateOf(0) }
    val hist = remember { mutableStateListOf<Float>() }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rate = 44100
            val buf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            @Suppress("MissingPermission")
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf)
            val data = ShortArray(buf / 2)
            try {
                rec.startRecording()
                while (isActive && running) {
                    val r = rec.read(data, 0, data.size)
                    if (r > 0) {
                        var s = 0.0
                        for (i in 0 until r) s += data[i].toDouble() * data[i]
                        val rms = sqrt(s / r)
                        // Approximation : 0 dBFS ≈ 90 dB SPL sur la plupart des micros de téléphone.
                        val v = (20 * log10(rms.coerceAtLeast(1.0) / 32767.0) + 90).toFloat().coerceIn(0f, 120f)
                        withContext(Dispatchers.Main) {
                            db = db * 0.6f + v * 0.4f
                            if (db > maxDb) maxDb = db
                            sum += db; n++
                            hist.add(db); if (hist.size > 120) hist.removeAt(0)
                        }
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { running = false } }

    ToolScaffold("Sonomètre", onBack) {
        Section {
            Text("${db.roundToInt()} dB", fontSize = 64.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(noiseLabel(db), color = SE.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            LinearProgressIndicator(progress = { db / 120f }, modifier = Modifier.fillMaxWidth(), color = if (db > 85) SE.Red else if (db > 65) SE.Orange else SE.Green)
            Canvas(Modifier.fillMaxWidth().height(80.dp)) {
                if (hist.size > 1) {
                    val step = size.width / 119f
                    for (i in 1 until hist.size) {
                        drawLine(Color(0xFF7AA7FF), Offset((i - 1) * step, size.height * (1 - hist[i - 1] / 120f)), Offset(i * step, size.height * (1 - hist[i] / 120f)), 3f)
                    }
                }
            }
            Row2 {
                OutlinedButton(onClick = {
                    if (!granted) perm.launch(Manifest.permission.RECORD_AUDIO) else running = !running
                }, modifier = Modifier.weight(1f)) { Text(if (running) "Arrêter" else "Mesurer") }
                OutlinedButton(onClick = { maxDb = 0f; sum = 0.0; n = 0; hist.clear() }, modifier = Modifier.weight(1f)) { Text("Remettre à zéro") }
            }
            ResultRow("Maximum", "${maxDb.roundToInt()} dB")
            ResultRow("Moyenne", if (n > 0) "${(sum / n).roundToInt()} dB" else "—")
        }
        Section("Repères") {
            listOf("30 dB" to "Chuchotement", "50 dB" to "Bureau calme", "60 dB" to "Conversation", "70 dB" to "Aspirateur",
                "85 dB" to "Seuil de danger (exposition longue)", "100 dB" to "Concert, discothèque", "120 dB" to "Seuil de douleur").forEach { (a, b) -> ResultRow(b, a) }
            Hint("Valeurs approximatives : les micros de téléphone ne sont pas des appareils de mesure.")
        }
    }
}

private fun noiseLabel(db: Float) = when {
    db < 35 -> "Très calme"
    db < 55 -> "Calme"
    db < 70 -> "Normal"
    db < 85 -> "Bruyant"
    db < 100 -> "Très bruyant"
    else -> "Dangereux"
}

// ------------------------------------------------------------------ Métronome et générateur de son

private fun playTone(freq: Double, ms: Int, volume: Float = 0.8f, wave: String = "sine"): AudioTrack {
    val rate = 44100
    val n = rate * ms / 1000
    val pcm = ShortArray(n)
    for (i in 0 until n) {
        val t = i.toDouble() / rate
        val ph = (t * freq) % 1.0
        val v = when (wave) {
            "square" -> if (ph < 0.5) 1.0 else -1.0
            "saw" -> 2 * ph - 1
            "triangle" -> 1 - 4 * abs(ph - 0.5)
            else -> sin(2 * PI * freq * t)
        }
        val env = minOf(1.0, i / 200.0, (n - i) / 200.0)
        pcm[i] = (v * env * volume * Short.MAX_VALUE).toInt().toShort()
    }
    val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(n * 2)
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(pcm, 0, n)
    track.play()
    return track
}

@Composable
fun ToneScreen(onBack: () -> Unit) {
    var bpm by rememberSaveable { mutableFloatStateOf(100f) }
    var beats by rememberSaveable { mutableIntStateOf(4) }
    var metro by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }
    val taps = remember { mutableStateListOf<Long>() }

    var freq by rememberSaveable { mutableFloatStateOf(440f) }
    var wave by rememberSaveable { mutableStateOf("sine") }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(metro, bpm, beats) {
        beat = 0
        while (metro && isActive) {
            val accent = beat % beats == 0
            val t = withContext(Dispatchers.IO) { playTone(if (accent) 1760.0 else 880.0, 40, if (accent) 1f else 0.6f) }
            beat++
            delay((60_000 / bpm).toLong())
            t.release()
        }
    }
    LaunchedEffect(playing, freq, wave) {
        while (playing && isActive) {
            val t = withContext(Dispatchers.IO) { playTone(freq.toDouble(), 1000, 0.5f, wave) }
            delay(990)
            t.release()
        }
    }
    DisposableEffect(Unit) { onDispose { metro = false; playing = false } }

    ToolScaffold("Sons & métronome", onBack) {
        Section("Métronome") {
            Text("${bpm.roundToInt()} BPM", fontSize = 44.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(tempoName(bpm), color = SE.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            Slider(bpm, { bpm = it.roundToInt().toFloat() }, valueRange = 30f..240f)
            ChoiceChips(listOf(2, 3, 4, 6), beats, { "$it temps" }) { beats = it }
            if (metro) {
                androidx.compose.foundation.layout.Row(Modifier.align(Alignment.CenterHorizontally)) {
                    repeat(beats) { i ->
                        Box(Modifier.size(22.dp).background(if ((beat - 1).mod(beats) == i) SE.Accent else SE.SurfaceHi, RoundedCornerShape(11.dp)))
                        if (i < beats - 1) androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    }
                }
            }
            Row2 {
                OutlinedButton(onClick = { metro = !metro }, modifier = Modifier.weight(1f)) { Text(if (metro) "Arrêter" else "Démarrer") }
                OutlinedButton(onClick = {
                    val now = SystemClock.elapsedRealtime()
                    if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                    taps.add(now); if (taps.size > 6) taps.removeAt(0)
                    if (taps.size >= 2) bpm = (60_000.0 / ((taps.last() - taps.first()).toDouble() / (taps.size - 1))).toFloat().coerceIn(30f, 240f).roundToInt().toFloat()
                }, modifier = Modifier.weight(1f)) { Text("Taper le tempo") }
            }
        }
        Section("Générateur de fréquence") {
            Text("${freq.roundToInt()} Hz · ${noteName(freq.toDouble())}", fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Slider(freq, { freq = it }, valueRange = 20f..8000f)
            ChoiceChips(listOf(261.63f, 329.63f, 392f, 440f, 1000f), null, { "${it.roundToInt()} Hz" }) { freq = it }
            ChoiceChips(listOf("sine", "square", "triangle", "saw"), wave, {
                when (it) { "square" -> "Carré"; "triangle" -> "Triangle"; "saw" -> "Dent de scie"; else -> "Sinus" }
            }) { wave = it }
            OutlinedButton(onClick = { playing = !playing }, modifier = Modifier.fillMaxWidth()) { Text(if (playing) "Arrêter" else "Jouer") }
            Hint("Attention au volume, surtout avec des écouteurs.")
        }
    }
}

private fun tempoName(bpm: Float) = when {
    bpm < 60 -> "Largo"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante / Moderato"
    bpm < 120 -> "Allegretto"
    bpm < 168 -> "Allegro"
    else -> "Presto"
}

private fun noteName(f: Double): String {
    val names = listOf("Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si")
    val n = (12 * (kotlin.math.ln(f / 440.0) / kotlin.math.ln(2.0))).roundToInt() + 57
    return names[n.mod(12)] + (n / 12)
}

// ------------------------------------------------------------------ Mon téléphone

@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val config = LocalConfiguration.current
    val density = LocalDensity.current

    ToolScaffold("Mon téléphone", onBack) {
        val bat = remember(tick) { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        Section("Batterie") {
            val level = bat?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
            val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val volt = (bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
            val health = when (bat?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Bonne"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Surchauffe"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Usée"; BatteryManager.BATTERY_HEALTH_COLD -> "Froide"; else -> "Inconnue"
            }
            ResultRow("Niveau", "$level %", big = true, color = if (level < 20) SE.Red else SE.Green)
            LinearProgressIndicator(progress = { level / 100f }, modifier = Modifier.fillMaxWidth())
            ResultRow("État", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "En charge"; BatteryManager.BATTERY_STATUS_FULL -> "Pleine"
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Sur batterie"; else -> "Inconnu"
            })
            ResultRow("Santé", health)
            ResultRow("Température", fmt(temp, 1) + " °C", color = if (temp > 40) SE.Red else SE.Text)
            ResultRow("Tension", fmt(volt, 2) + " V")
            val cap = runCatching { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
            if (cap > 0) ResultRow("Charge restante", "${cap / 1000} mAh")
        }
        Section("Stockage") {
            val st = StatFs(Environment.getDataDirectory().path)
            val total = st.totalBytes; val free = st.availableBytes
            ResultRow("Utilisé", "${Storage.humanSize(total - free)} / ${Storage.humanSize(total)}")
            LinearProgressIndicator(progress = { ((total - free).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            ResultRow("Libre", Storage.humanSize(free), big = true)
            ResultRow("Cache de Fluxo", Storage.humanSize(dirSize(ctx.cacheDir)))
        }
        Section("Mémoire") {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            ResultRow("RAM totale", Storage.humanSize(mi.totalMem))
            ResultRow("RAM disponible", Storage.humanSize(mi.availMem))
            ResultRow("Processeurs", Runtime.getRuntime().availableProcessors().toString() + " cœurs")
            if (Build.VERSION.SDK_INT >= 31) ResultRow("Puce", "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        }
        Section("Appareil") {
            ResultRow("Modèle", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            ResultRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            ResultRow("Mise à jour de sécurité", Build.VERSION.SECURITY_PATCH)
            ResultRow("Architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            ResultRow("Allumé depuis", run {
                val s = SystemClock.elapsedRealtime() / 1000
                "${s / 86400} j ${s % 86400 / 3600} h ${s % 3600 / 60} min"
            })
        }
        Section("Écran") {
            val dm = ctx.resources.displayMetrics
            ResultRow("Résolution", "${dm.widthPixels} × ${dm.heightPixels} px")
            ResultRow("Densité", "${dm.densityDpi} dpi (×${fmt(density.density.toDouble(), 2)})")
            ResultRow("Taille utile", "${config.screenWidthDp} × ${config.screenHeightDp} dp")
            val inches = sqrt((dm.widthPixels / dm.xdpi).let { it * it } + (dm.heightPixels / dm.ydpi).let { it * it })
            ResultRow("Diagonale (env.)", fmt(inches.toDouble(), 1) + " pouces")
            if (Build.VERSION.SDK_INT >= 30) ctx.display?.let { ResultRow("Fréquence", "${it.refreshRate.roundToInt()} Hz") }
        }
        Section("Capteurs") {
            val sm = ctx.getSystemService(SensorManager::class.java)
            val list = sm.getSensorList(Sensor.TYPE_ALL)
            Hint("${list.size} capteurs")
            list.take(40).forEach { Text("• ${it.name}", fontSize = 13.sp, color = SE.Muted) }
        }
    }
}

// ------------------------------------------------------------------ Test d'écran, règle, tactile

@Composable
fun ScreenTestScreen(onBack: () -> Unit) {
    var full by remember { mutableStateOf<String?>(null) }
    var colorIdx by remember { mutableIntStateOf(0) }
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black, Color.Gray, Color.Cyan, Color.Magenta, Color.Yellow)
    val density = LocalDensity.current
    val ctx = LocalContext.current

    when (full) {
        "colors" -> {
            BackHandler { full = null }
            Box(Modifier.fillMaxSize().background(colors[colorIdx]).clickable {
                if (colorIdx < colors.size - 1) colorIdx++ else { colorIdx = 0; full = null }
            }, contentAlignment = Alignment.BottomCenter) {
                Text("Touche pour la couleur suivante (${colorIdx + 1}/${colors.size})", color = Color.Gray, fontSize = 12.sp)
            }
            return
        }
        "touch" -> {
            BackHandler { full = null }
            val points = remember { mutableStateListOf<Offset>() }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(onDragStart = { points.add(it) }) { change, _ -> points.add(change.position) }
                }) {
                    points.forEach { drawCircle(Color(0xFF7AA7FF), 14f, it) }
                }
                Text("Dessine partout : les zones sans trace ne réagissent pas. Retour pour quitter.",
                    color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter))
            }
            return
        }
        "ruler" -> {
            BackHandler { full = null }
            val dm = ctx.resources.displayMetrics
            Box(Modifier.fillMaxSize().background(Color(0xFFFFF8E1)).clickable { full = null }) {
                Canvas(Modifier.fillMaxSize()) {
                    val pxPerMm = dm.ydpi / 25.4f
                    var mm = 0
                    while (mm * pxPerMm < size.height) {
                        val y = mm * pxPerMm
                        val len = if (mm % 10 == 0) 90f else if (mm % 5 == 0) 60f else 35f
                        drawLine(Color.Black, Offset(0f, y), Offset(len, y), 2f)
                        mm++
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val pxPerMm = dm.ydpi / 25.4f
                    val paint = android.graphics.Paint().apply { textSize = 36f; color = android.graphics.Color.BLACK; isAntiAlias = true }
                    var cm = 0
                    while (cm * 10 * pxPerMm < size.height) {
                        drawContext.canvas.nativeCanvas.drawText("$cm", 100f, cm * 10 * pxPerMm + 12f, paint)
                        cm++
                    }
                }
                Text("Règle en cm (bord gauche). Touche pour quitter.", color = Color.DarkGray, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter))
            }
            return
        }
    }

    ToolScaffold("Test d'écran & règle", onBack) {
        Section("Pixels morts") {
            Hint("L'écran passe par plusieurs couleurs unies : cherche les points qui ne changent pas.")
            OutlinedButton(onClick = { colorIdx = 0; full = "colors" }, modifier = Modifier.fillMaxWidth()) { Text("Lancer le test des couleurs") }
        }
        Section("Tactile") {
            Hint("Dessine sur tout l'écran pour repérer une zone qui ne répond pas.")
            OutlinedButton(onClick = { full = "touch" }, modifier = Modifier.fillMaxWidth()) { Text("Tester le tactile") }
        }
        Section("Règle") {
            Hint("Une règle graduée en millimètres sur le bord de l'écran.")
            OutlinedButton(onClick = { full = "ruler" }, modifier = Modifier.fillMaxWidth()) { Text("Afficher la règle") }
            Hint("La précision dépend de ce que le téléphone annonce (${with(density) { 1.dp.toPx() }.let { fmt(it.toDouble(), 2) }} px/dp).")
        }
        Section("Dégradé") {
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val steps = 32
                val w = size.width / steps
                for (i in 0 until steps) {
                    val v = i / (steps - 1f)
                    drawRect(Color(v, v, v), Offset(i * w, 0f), androidx.compose.ui.geometry.Size(w + 1, size.height))
                }
            }
            Hint("Toutes les bandes doivent être distinctes, du noir au blanc.")
        }
    }
}
