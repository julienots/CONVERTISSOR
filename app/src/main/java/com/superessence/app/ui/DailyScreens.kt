package com.superessence.app.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.core.Prefs
import com.superessence.app.core.Storage
import com.superessence.app.tools.MediaTools
import kotlinx.coroutines.delay
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Petite vibration (si activée dans les réglages). */
fun buzz(ctx: Context, ms: Long = 30) {
    if (!Prefs.vibrate) return
    runCatching {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

fun beep(times: Int = 3) {
    runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400 * times)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tg.release() }, 400L * times + 200)
    }
}

private fun clock(ms: Long, tenths: Boolean = false): String {
    val s = ms / 1000
    val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    return if (tenths) base + ",%d".format(ms % 1000 / 100) else base
}

// ------------------------------------------------------------------ Chrono, minuteur, Pomodoro

@Composable
fun TimerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }

    // Chronomètre
    var swRunning by remember { mutableStateOf(false) }
    var swBase by remember { mutableLongStateOf(0L) }
    var swAcc by remember { mutableLongStateOf(0L) }
    var swNow by remember { mutableLongStateOf(0L) }
    val laps = remember { mutableStateListOf<Long>() }
    LaunchedEffect(swRunning) { while (swRunning) { swNow = System.currentTimeMillis(); delay(50) } }
    val swTime = swAcc + if (swRunning) swNow - swBase else 0

    // Minuteur
    var tMin by rememberSaveable { mutableStateOf("5") }
    var tSec by rememberSaveable { mutableStateOf("0") }
    var tEnd by remember { mutableLongStateOf(0L) }
    var tLeft by remember { mutableLongStateOf(0L) }
    var tRunning by remember { mutableStateOf(false) }
    LaunchedEffect(tRunning) {
        while (tRunning) {
            tLeft = (tEnd - System.currentTimeMillis()).coerceAtLeast(0)
            if (tLeft == 0L) { tRunning = false; beep(); buzz(ctx, 800) }
            delay(100)
        }
    }

    // Pomodoro
    var pWork by rememberSaveable { mutableStateOf("25") }
    var pBreak by rememberSaveable { mutableStateOf("5") }
    var pRunning by remember { mutableStateOf(false) }
    var pWorkPhase by remember { mutableStateOf(true) }
    var pEnd by remember { mutableLongStateOf(0L) }
    var pLeft by remember { mutableLongStateOf(0L) }
    var pCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(pRunning) {
        while (pRunning) {
            pLeft = (pEnd - System.currentTimeMillis()).coerceAtLeast(0)
            if (pLeft == 0L) {
                beep(2); buzz(ctx, 600)
                if (pWorkPhase) pCount++
                pWorkPhase = !pWorkPhase
                val m = num(if (pWorkPhase) pWork else pBreak) ?: 5.0
                pEnd = System.currentTimeMillis() + (m * 60_000).toLong()
            }
            delay(200)
        }
    }

    ToolScaffold("Chrono & minuteur", onBack) {
        ChoiceChips(listOf(0, 1, 2), tab, { listOf("Chronomètre", "Minuteur", "Pomodoro")[it] }) { tab = it }
        when (tab) {
            0 -> Section {
                Text(clock(swTime, true), fontSize = 56.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Row2 {
                    OutlinedButton(onClick = {
                        if (swRunning) { swAcc += System.currentTimeMillis() - swBase; swRunning = false }
                        else { swBase = System.currentTimeMillis(); swNow = swBase; swRunning = true }
                        buzz(ctx)
                    }, modifier = Modifier.weight(1f)) { Text(if (swRunning) "Pause" else if (swTime > 0) "Reprendre" else "Démarrer") }
                    OutlinedButton(onClick = {
                        if (swRunning) laps.add(0, swTime) else { swAcc = 0; laps.clear() }
                    }, modifier = Modifier.weight(1f)) { Text(if (swRunning) "Tour" else "Remettre à zéro") }
                }
                laps.forEachIndexed { i, l ->
                    val prev = laps.getOrNull(i + 1) ?: 0L
                    ResultRow("Tour ${laps.size - i}", clock(l, true) + "  (+" + clock(l - prev, true) + ")")
                }
            }
            1 -> Section {
                if (!tRunning && tLeft == 0L) {
                    Row2 {
                        NumField(tMin, { tMin = it }, "Minutes", Modifier.weight(1f), decimal = false)
                        NumField(tSec, { tSec = it }, "Secondes", Modifier.weight(1f), decimal = false)
                    }
                    ChoiceChips(listOf(1, 3, 5, 10, 15, 30, 60), null, { "$it min" }) { tMin = it.toString(); tSec = "0" }
                }
                Text(clock(if (tRunning || tLeft > 0) tLeft else ((num(tMin) ?: 0.0) * 60_000 + (num(tSec) ?: 0.0) * 1000).toLong()),
                    fontSize = 56.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row2 {
                    OutlinedButton(onClick = {
                        if (tRunning) { tRunning = false }
                        else {
                            val dur = if (tLeft > 0) tLeft else ((num(tMin) ?: 0.0) * 60_000 + (num(tSec) ?: 0.0) * 1000).toLong()
                            if (dur > 0) { tEnd = System.currentTimeMillis() + dur; tLeft = dur; tRunning = true }
                        }
                    }, modifier = Modifier.weight(1f)) { Text(if (tRunning) "Pause" else if (tLeft > 0) "Reprendre" else "Démarrer") }
                    OutlinedButton(onClick = { tRunning = false; tLeft = 0 }, modifier = Modifier.weight(1f)) { Text("Annuler") }
                }
                Hint("Une alarme sonne à la fin. Garde l'appli ouverte.")
            }
            else -> Section {
                Row2 {
                    NumField(pWork, { pWork = it }, "Travail (min)", Modifier.weight(1f))
                    NumField(pBreak, { pBreak = it }, "Pause (min)", Modifier.weight(1f))
                }
                Text(if (!pRunning) "Prêt" else if (pWorkPhase) "Concentration" else "Pause",
                    color = if (pWorkPhase) SE.Red else SE.Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(clock(if (pRunning) pLeft else ((num(pWork) ?: 25.0) * 60_000).toLong()), fontSize = 56.sp, fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedButton(onClick = {
                    if (pRunning) pRunning = false
                    else { pWorkPhase = true; pEnd = System.currentTimeMillis() + ((num(pWork) ?: 25.0) * 60_000).toLong(); pRunning = true }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (pRunning) "Arrêter" else "Commencer") }
                ResultRow("Sessions terminées", pCount.toString())
            }
        }
    }
}

// ------------------------------------------------------------------ Bloc-notes

@Composable
fun NotesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val notes = remember { mutableStateListOf<String>().apply { addAll(Prefs.getString("notes").split("\u001E").filter { it.isNotEmpty() }) } }
    var editing by remember { mutableStateOf(-1) }
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    fun persist() = Prefs.putString("notes", notes.joinToString("\u001E"))

    ToolScaffold(if (editing >= 0 || editing == -2) "Note" else "Bloc-notes", { if (editing != -1) { editing = -1 } else onBack() }) {
        if (editing != -1) {
            TextIn(draft, { draft = it }, "Écris ta note", minLines = 10)
            Hint("${draft.length} caractères · ${draft.split(Regex("\\s+")).count { it.isNotBlank() }} mots")
            Row2 {
                OutlinedButton(onClick = {
                    if (draft.isNotBlank()) { if (editing >= 0) notes[editing] = draft else notes.add(0, draft) }
                    else if (editing >= 0) notes.removeAt(editing)
                    persist(); editing = -1
                }, modifier = Modifier.weight(1f)) { Text("Enregistrer") }
                OutlinedButton(onClick = { editing = -1 }, modifier = Modifier.weight(1f)) { Text("Annuler") }
            }
            Row {
                TextButton(onClick = { copyText(ctx, draft) }) { Text("Copier") }
                TextButton(onClick = {
                    val t = Storage.openOutput(ctx, "Note_${MediaTools.stamp()}.txt")
                    t.stream.write(draft.toByteArray())
                    Storage.shareFiles(ctx, listOf(t.finish()))
                }) { Text("Exporter en TXT") }
                if (editing >= 0) TextButton(onClick = { notes.removeAt(editing); persist(); editing = -1 }) { Text("Supprimer", color = SE.Red) }
            }
        } else {
            OutlinedButton(onClick = { draft = ""; editing = -2 }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Nouvelle note")
            }
            if (notes.size > 3) TextIn(query, { query = it }, "Rechercher")
            if (notes.isEmpty()) Hint("Aucune note. Elles restent sur ton téléphone.")
            notes.forEachIndexed { i, n ->
                if (query.isBlank() || n.contains(query, true)) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(SE.Surface, RoundedCornerShape(12.dp))
                            .clickable { draft = n; editing = i }
                            .padding(14.dp),
                    ) {
                        Text(n.lines().first().take(60), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        val rest = n.lines().drop(1).joinToString(" ").trim()
                        if (rest.isNotEmpty()) Text(rest.take(120), color = SE.Muted, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Liste à cocher

@Composable
fun TodoScreen(onBack: () -> Unit) {
    val items = remember {
        mutableStateListOf<Pair<String, Boolean>>().apply {
            Prefs.getString("todo").split('\n').filter { it.length > 1 }.forEach { add(it.drop(1) to (it[0] == '1')) }
        }
    }
    var input by rememberSaveable { mutableStateOf("") }
    fun persist() = Prefs.putString("todo", items.joinToString("\n") { (if (it.second) "1" else "0") + it.first })
    fun addInput() {
        input.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { items.add(it to false) }
        input = ""; persist()
    }

    ToolScaffold("Liste", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextIn(input, { input = it }, "Ajouter (sépare par ; pour plusieurs)", modifier = Modifier.weight(1f))
            IconButton(onClick = { addInput() }) { Icon(Icons.Filled.Add, "Ajouter", tint = SE.Accent) }
        }
        val done = items.count { it.second }
        if (items.isNotEmpty()) Hint("$done / ${items.size} fait(s)")
        items.forEachIndexed { i, (t, d) ->
            Row(
                Modifier.fillMaxWidth().background(SE.Surface, RoundedCornerShape(10.dp)).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(d, { items[i] = t to it; persist() })
                Text(t, Modifier.weight(1f), color = if (d) SE.Muted else SE.Text, textDecoration = if (d) TextDecoration.LineThrough else null)
                IconButton(onClick = { if (i > 0) { val x = items.removeAt(i); items.add(i - 1, x); persist() } }) { Icon(Icons.Filled.KeyboardArrowUp, "Monter", tint = SE.Muted) }
                IconButton(onClick = { items.removeAt(i); persist() }) { Icon(Icons.Filled.Close, "Retirer", tint = SE.Muted) }
            }
        }
        if (items.isNotEmpty()) {
            Row {
                TextButton(onClick = { items.removeAll { it.second }; persist() }) { Text("Retirer les cochés") }
                TextButton(onClick = { for (i in items.indices) items[i] = items[i].first to false; persist() }) { Text("Tout décocher") }
                TextButton(onClick = { items.clear(); persist() }) { Text("Tout effacer") }
            }
        }
    }
}

// ------------------------------------------------------------------ Compteur

@Composable
fun CounterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val counters = remember {
        mutableStateListOf<Pair<String, Int>>().apply {
            val saved = Prefs.getString("counters").split('\n').filter { it.contains('\t') }
            if (saved.isEmpty()) add("Compteur" to 0) else saved.forEach { add(it.substringBefore('\t') to (it.substringAfter('\t').toIntOrNull() ?: 0)) }
        }
    }
    var step by rememberSaveable { mutableStateOf(1) }
    var newName by rememberSaveable { mutableStateOf("") }
    fun persist() = Prefs.putString("counters", counters.joinToString("\n") { it.first.replace('\t', ' ') + "\t" + it.second })

    ToolScaffold("Compteur", onBack) {
        ChoiceChips(listOf(1, 5, 10), step, { "Pas de $it" }) { step = it }
        counters.forEachIndexed { i, (name, v) ->
            Section(name) {
                Text(v.toString(), fontSize = 64.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row2 {
                    OutlinedButton(onClick = { counters[i] = name to v - step; persist(); buzz(ctx) }, modifier = Modifier.weight(1f).height(64.dp)) { Text("−", fontSize = 28.sp) }
                    androidx.compose.material3.Button(onClick = { counters[i] = name to v + step; persist(); buzz(ctx) }, modifier = Modifier.weight(2f).height(64.dp)) { Text("+", fontSize = 28.sp) }
                }
                Row {
                    TextButton(onClick = { counters[i] = name to 0; persist() }) { Text("Remettre à 0") }
                    if (counters.size > 1) TextButton(onClick = { counters.removeAt(i); persist() }) { Text("Supprimer") }
                }
            }
        }
        if (counters.size > 1) ResultRow("Total", counters.sumOf { it.second }.toString(), big = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextIn(newName, { newName = it }, "Nom d'un nouveau compteur", modifier = Modifier.weight(1f))
            IconButton(onClick = { counters.add(newName.ifBlank { "Compteur ${counters.size + 1}" } to 0); newName = ""; persist() }) {
                Icon(Icons.Filled.Add, "Ajouter", tint = SE.Accent)
            }
        }
    }
}

// ------------------------------------------------------------------ Hasard

@Composable
fun RandomScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rnd = remember { SecureRandom() }
    var dice by rememberSaveable { mutableStateOf(2) }
    var faces by rememberSaveable { mutableStateOf(6) }
    var rolled by rememberSaveable { mutableStateOf("") }
    var coin by rememberSaveable { mutableStateOf("") }
    var minV by rememberSaveable { mutableStateOf("1") }
    var maxV by rememberSaveable { mutableStateOf("100") }
    var count by rememberSaveable { mutableStateOf("1") }
    var unique by rememberSaveable { mutableStateOf(true) }
    var numbers by rememberSaveable { mutableStateOf("") }
    var list by rememberSaveable { mutableStateOf("Alice\nBob\nChloé\nDavid") }
    var picked by rememberSaveable { mutableStateOf("") }
    var teams by rememberSaveable { mutableStateOf("2") }

    ToolScaffold("Hasard", onBack) {
        Section("Dés") {
            ChoiceChips(listOf(1, 2, 3, 4, 5), dice, { "$it dé" + if (it > 1) "s" else "" }) { dice = it }
            ChoiceChips(listOf(4, 6, 8, 10, 12, 20, 100), faces, { "D$it" }) { faces = it }
            OutlinedButton(onClick = {
                val r = List(dice) { rnd.nextInt(faces) + 1 }
                rolled = r.joinToString("  ") + if (dice > 1) "   (total ${r.sum()})" else ""
                buzz(ctx, 60)
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Casino, null); Spacer(Modifier.width(6.dp)); Text("Lancer") }
            if (rolled.isNotEmpty()) Text(rolled, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Section("Pile ou face") {
            OutlinedButton(onClick = { coin = if (rnd.nextBoolean()) "Pile" else "Face"; buzz(ctx, 60) }, modifier = Modifier.fillMaxWidth()) { Text("Lancer la pièce") }
            if (coin.isNotEmpty()) Text(coin, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        Section("Nombre au hasard") {
            Row2 {
                NumField(minV, { minV = it }, "Min", Modifier.weight(1f), decimal = false)
                NumField(maxV, { maxV = it }, "Max", Modifier.weight(1f), decimal = false)
                NumField(count, { count = it }, "Combien", Modifier.weight(1f), decimal = false)
            }
            SwitchRow("Sans répétition", unique) { unique = it }
            OutlinedButton(onClick = {
                val a = minV.trim().toLongOrNull() ?: 1; val b = maxV.trim().toLongOrNull() ?: 100
                val lo = min(a, b); val hi = max(a, b)
                val n = (count.trim().toIntOrNull() ?: 1).coerceIn(1, 1000)
                numbers = if (unique && hi - lo + 1 <= 100_000) {
                    (lo..hi).shuffled(rnd).take(n).joinToString(", ")
                } else {
                    List(n) { lo + (rnd.nextDouble() * (hi - lo + 1)).toLong() }.joinToString(", ")
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Tirer") }
            if (numbers.isNotEmpty()) Text(numbers, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Section("Tirage au sort & équipes") {
            TextIn(list, { list = it }, "Un nom par ligne", minLines = 4)
            val names = list.lines().map { it.trim() }.filter { it.isNotEmpty() }
            Row2 {
                OutlinedButton(onClick = { if (names.isNotEmpty()) picked = names[rnd.nextInt(names.size)] }, modifier = Modifier.weight(1f)) { Text("Tirer un nom") }
                OutlinedButton(onClick = { picked = names.shuffled(rnd).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") }, modifier = Modifier.weight(1f)) { Text("Mélanger") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumField(teams, { teams = it }, "Équipes", Modifier.weight(1f), decimal = false)
                TextButton(onClick = {
                    val k = (teams.trim().toIntOrNull() ?: 2).coerceIn(2, 20)
                    val sh = names.shuffled(rnd)
                    picked = (0 until k).joinToString("\n\n") { t -> "Équipe ${t + 1} : " + sh.filterIndexed { i, _ -> i % k == t }.joinToString(", ") }
                }) { Text("Faire les équipes") }
            }
            if (picked.isNotEmpty()) Text(picked, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ------------------------------------------------------------------ Mots de passe

@Composable
fun PasswordScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rnd = remember { SecureRandom() }
    var length by rememberSaveable { mutableStateOf(16f) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var noAmbiguous by rememberSaveable { mutableStateOf(true) }
    var words by rememberSaveable { mutableStateOf(false) }
    var check by rememberSaveable { mutableStateOf("") }
    var seed by remember { mutableIntStateOf(0) }

    val wordList = remember {
        listOf("pomme", "nuage", "tigre", "violon", "plage", "fusée", "jardin", "lune", "cactus", "orange", "piano", "renard", "tomate", "glace", "bateau",
            "soleil", "forêt", "camion", "étoile", "girafe", "citron", "dragon", "hibou", "miel", "neige", "olive", "panda", "radis", "sable", "tulipe",
            "vague", "zèbre", "ancre", "brume", "canard", "dune", "flèche", "grotte", "hamac", "igloo", "jungle", "koala", "lapin", "marbre", "noyau")
    }
    fun gen(): String {
        if (words) {
            val n = (length / 4).roundToInt().coerceIn(3, 8)
            return List(n) { wordList[rnd.nextInt(wordList.size)] }.joinToString("-") + "-" + rnd.nextInt(100)
        }
        val lower = "abcdefghijkmnopqrstuvwxyz" + if (noAmbiguous) "" else "l"
        val up = "ABCDEFGHJKLMNPQRSTUVWXYZ" + if (noAmbiguous) "" else "IO"
        val dg = "23456789" + if (noAmbiguous) "" else "01"
        val sy = "!@#$%&*+-=?_.:;"
        val pool = lower + (if (upper) up else "") + (if (digits) dg else "") + (if (symbols) sy else "")
        val n = length.roundToInt()
        val req = listOfNotNull(lower, up.takeIf { upper }, dg.takeIf { digits }, sy.takeIf { symbols })
        val chars = MutableList(n) { pool[rnd.nextInt(pool.length)] }
        req.forEachIndexed { i, set -> if (i < n) chars[rnd.nextInt(n)] = set[rnd.nextInt(set.length)] }
        return chars.joinToString("")
    }
    val passwords = remember(seed, length, upper, digits, symbols, noAmbiguous, words) { List(5) { gen() } }

    ToolScaffold("Mots de passe", onBack) {
        Section("Générer") {
            Text("Longueur : ${length.roundToInt()}")
            Slider(length, { length = it }, valueRange = 6f..64f)
            SwitchRow("Majuscules", upper, enabled = !words) { upper = it }
            SwitchRow("Chiffres", digits, enabled = !words) { digits = it }
            SwitchRow("Symboles", symbols, enabled = !words) { symbols = it }
            SwitchRow("Éviter 0/O, 1/l/I", noAmbiguous, enabled = !words) { noAmbiguous = it }
            SwitchRow("Phrase de passe (mots)", words, "Plus facile à retenir") { words = it }
        }
        Section("Propositions (appuie pour copier)") {
            passwords.forEach { p ->
                Text(p, fontFamily = FontFamily.Monospace, fontSize = 17.sp,
                    modifier = Modifier.fillMaxWidth().background(SE.SurfaceHi, RoundedCornerShape(8.dp)).clickable { copyText(ctx, p, "Mot de passe copié") }.padding(10.dp))
            }
            TextButton(onClick = { seed++ }) { Text("Autres propositions") }
            val pin = remember(seed) { List(6) { rnd.nextInt(10) }.joinToString("") }
            ResultRow("Code PIN", pin)
        }
        Section("Tester un mot de passe") {
            TextIn(check, { check = it }, "Mot de passe")
            if (check.isNotEmpty()) {
                var pool = 0
                if (check.any { it.isLowerCase() }) pool += 26
                if (check.any { it.isUpperCase() }) pool += 26
                if (check.any { it.isDigit() }) pool += 10
                if (check.any { !it.isLetterOrDigit() }) pool += 32
                val bits = check.length * log2(max(pool, 2).toDouble())
                val (label, color) = when {
                    bits < 40 -> "Faible" to SE.Red
                    bits < 60 -> "Moyen" to SE.Orange
                    bits < 80 -> "Bon" to SE.Green
                    else -> "Excellent" to SE.Green
                }
                ResultRow("Solidité", label, big = true, color = color)
                val years = 2.0.pow(bits) / 1e10 / 3.15e7
                ResultRow("Temps pour le deviner (10 milliards d'essais/s)", when {
                    years < 1.0 / 365 / 24 -> "moins d'une heure"
                    years < 1 -> "${fmt(years * 365, 0)} jours"
                    years < 1e6 -> "${fmt(years, 0)} ans"
                    else -> "des millions d'années"
                })
            }
        }
    }
}

// ------------------------------------------------------------------ Couleurs

private fun parseHex(s: String): Int? {
    val h = s.trim().removePrefix("#")
    val full = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6 -> h; else -> return null }
    return full.toIntOrNull(16)
}

private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.RGBToHSL(r, g, b, hsl)
    return Triple(hsl[0], hsl[1], hsl[2])
}

private fun luminance(c: Int): Double = androidx.core.graphics.ColorUtils.calculateLuminance(c or (0xFF shl 24))

@Composable
fun ColorsScreen(onBack: () -> Unit) {
    var hex by rememberSaveable { mutableStateOf("#2563EB") }
    var r by rememberSaveable { mutableStateOf(37f) }
    var g by rememberSaveable { mutableStateOf(99f) }
    var b by rememberSaveable { mutableStateOf(235f) }
    var hex2 by rememberSaveable { mutableStateOf("#FFFFFF") }
    fun fromRgb() { hex = "#%02X%02X%02X".format(r.toInt(), g.toInt(), b.toInt()) }
    val rgb = (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    val color = Color(0xFF000000 or rgb.toLong())

    ToolScaffold("Couleurs", onBack) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(color, RoundedCornerShape(14.dp)).border(1.dp, SE.SurfaceHi, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Text(hex.uppercase(), color = if (luminance(rgb) > 0.5) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Section("Choisir") {
            TextIn(hex, {
                hex = it
                parseHex(it)?.let { c -> r = (c shr 16 and 255).toFloat(); g = (c shr 8 and 255).toFloat(); b = (c and 255).toFloat() }
            }, "Code HEX")
            Text("Rouge ${r.toInt()}"); Slider(r, { r = it; fromRgb() }, valueRange = 0f..255f)
            Text("Vert ${g.toInt()}"); Slider(g, { g = it; fromRgb() }, valueRange = 0f..255f)
            Text("Bleu ${b.toInt()}"); Slider(b, { b = it; fromRgb() }, valueRange = 0f..255f)
        }
        Section("Codes") {
            val (h, s, l) = rgbToHsl(r.toInt(), g.toInt(), b.toInt())
            ResultRow("HEX", "#%06X".format(rgb))
            ResultRow("RGB", "rgb(${r.toInt()}, ${g.toInt()}, ${b.toInt()})")
            ResultRow("HSL", "hsl(${h.roundToInt()}, ${(s * 100).roundToInt()} %, ${(l * 100).roundToInt()} %)")
            val k = 1 - max(r, max(g, b)) / 255f
            if (k < 1f) {
                val c = (1 - r / 255f - k) / (1 - k); val m = (1 - g / 255f - k) / (1 - k); val y = (1 - b / 255f - k) / (1 - k)
                ResultRow("CMJN", "${(c * 100).roundToInt()}, ${(m * 100).roundToInt()}, ${(y * 100).roundToInt()}, ${(k * 100).roundToInt()}")
            }
            ResultRow("Android", "0xFF%06X".format(rgb))
        }
        Section("Nuances") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.85f, 0.7f, 0.55f, 0.4f, 0.25f, 0.1f).forEach { f ->
                    val (h, s, _) = rgbToHsl(r.toInt(), g.toInt(), b.toInt())
                    val c = androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, f))
                    Box(Modifier.weight(1f).height(48.dp).background(Color(c), RoundedCornerShape(6.dp)))
                }
            }
            Text("Complémentaire", color = SE.Muted, fontSize = 13.sp)
            val comp = 0xFFFFFF xor rgb
            Box(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF000000 or comp.toLong()), RoundedCornerShape(6.dp)))
            ResultRow("Code", "#%06X".format(comp))
        }
        Section("Contraste avec une autre couleur") {
            TextIn(hex2, { hex2 = it }, "Autre couleur (HEX)")
            parseHex(hex2)?.let { o ->
                val l1 = luminance(rgb); val l2 = luminance(o)
                val ratio = (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
                Box(Modifier.fillMaxWidth().background(Color(0xFF000000 or o.toLong()), RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text("Exemple de texte", color = color, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                ResultRow("Rapport de contraste", fmt(ratio, 2) + " : 1", big = true, color = if (ratio >= 4.5) SE.Green else SE.Red)
                Hint(if (ratio >= 7) "Parfait pour du texte." else if (ratio >= 4.5) "Bon pour du texte." else if (ratio >= 3) "Seulement pour du gros texte." else "Trop faible pour du texte.")
            }
        }
    }
}

// ------------------------------------------------------------------ Heure du monde

private val cities = listOf(
    "Paris" to "Europe/Paris", "Londres" to "Europe/London", "Lisbonne" to "Europe/Lisbon", "Moscou" to "Europe/Moscow",
    "Istanbul" to "Europe/Istanbul", "Dubaï" to "Asia/Dubai", "Casablanca" to "Africa/Casablanca", "Alger" to "Africa/Algiers",
    "Dakar" to "Africa/Dakar", "Abidjan" to "Africa/Abidjan", "Kinshasa" to "Africa/Kinshasa", "Le Caire" to "Africa/Cairo",
    "Johannesburg" to "Africa/Johannesburg", "New Delhi" to "Asia/Kolkata", "Bangkok" to "Asia/Bangkok", "Pékin" to "Asia/Shanghai",
    "Tokyo" to "Asia/Tokyo", "Séoul" to "Asia/Seoul", "Sydney" to "Australia/Sydney", "Nouméa" to "Pacific/Noumea",
    "Tahiti" to "Pacific/Tahiti", "Montréal" to "America/Toronto", "New York" to "America/New_York", "Chicago" to "America/Chicago",
    "Los Angeles" to "America/Los_Angeles", "Mexico" to "America/Mexico_City", "Pointe-à-Pitre" to "America/Guadeloupe",
    "Fort-de-France" to "America/Martinique", "Cayenne" to "America/Cayenne", "São Paulo" to "America/Sao_Paulo",
    "Buenos Aires" to "America/Argentina/Buenos_Aires", "La Réunion" to "Indian/Reunion",
)

@Composable
fun WorldClockScreen(onBack: () -> Unit) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { now = ZonedDateTime.now(); delay(1000) } }
    val fmtT = DateTimeFormatter.ofPattern("HH:mm")
    val fmtD = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRANCE)
    ToolScaffold("Heure du monde", onBack) {
        Section {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm:ss")), fontSize = 48.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)) + " · " + now.zone.id, color = SE.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        TextIn(query, { query = it }, "Rechercher une ville")
        cities.filter { query.isBlank() || it.first.contains(query, true) }.forEach { (name, zone) ->
            val t = now.withZoneSameInstant(ZoneId.of(zone))
            val diff = (t.offset.totalSeconds - now.offset.totalSeconds) / 3600.0
            Row(
                Modifier.fillMaxWidth().background(SE.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(t.format(fmtD) + " · " + (if (diff >= 0) "+" else "") + fmt(diff, 1) + " h", color = SE.Muted, fontSize = 12.sp)
                }
                val night = t.hour < 7 || t.hour >= 21
                Icon(if (night) Icons.Filled.DarkMode else Icons.Filled.LightMode, null, tint = SE.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t.format(fmtT), fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
