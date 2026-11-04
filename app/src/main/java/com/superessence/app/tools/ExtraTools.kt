package com.superessence.app.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.superessence.app.convert.FF
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ============================================================== Sauvegarde des applis installées

data class AppEntry(
    val label: String,
    val pkg: String,
    val version: String,
    val system: Boolean,
    val apks: List<String>,
    val size: Long,
)

object Apps {
    fun list(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        return apps.mapNotNull { ai ->
            runCatching {
                val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                @Suppress("DEPRECATION")
                val version = pm.getPackageInfo(ai.packageName, 0).versionName ?: ""
                AppEntry(
                    pm.getApplicationLabel(ai).toString(),
                    ai.packageName,
                    version,
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    apks,
                    apks.sumOf { File(it).length() },
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Enregistre l'APK (ou un .apks si l'appli est en plusieurs morceaux). */
    fun export(ctx: Context, app: AppEntry, p: Progress): SavedFile {
        val base = Storage.sanitize("${app.label}_${app.version}".trimEnd('_'))
        if (app.apks.size == 1) {
            p.update(-1f, "Copie de ${app.label}…")
            return Storage.save(ctx, File(app.apks[0]), "$base.apk", "Applis")
        }
        val t = Storage.openOutput(ctx, "$base.apks", "Applis")
        try {
            ZipOutputStream(t.stream.buffered(1 shl 20)).use { zip ->
                app.apks.forEachIndexed { i, path ->
                    p.update(i.toFloat() / app.apks.size, "Copie de ${app.label} (${i + 1}/${app.apks.size})…")
                    val f = File(path)
                    zip.putNextEntry(ZipEntry(if (i == 0) "base.apk" else f.name))
                    f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            t.abort(); throw e
        }
        return t.finish()
    }
}

// ============================================================== Texte → voix

object Speech {
    val voices = listOf(
        Locale.FRENCH to "Français",
        Locale.US to "English",
        Locale("es", "ES") to "Español",
        Locale.GERMAN to "Deutsch",
        Locale.ITALIAN to "Italiano",
        Locale("pt", "PT") to "Português",
        Locale("ar") to "العربية",
    )

    private suspend fun init(ctx: Context): TextToSpeech = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val holder = arrayOfNulls<TextToSpeech>(1)
            holder[0] = TextToSpeech(ctx) { status ->
                Handler(Looper.getMainLooper()).post {
                    val t = holder[0]
                    if (status == TextToSpeech.SUCCESS && t != null) cont.resume(t)
                    else cont.resumeWithException(IOException("Synthèse vocale indisponible sur ce téléphone"))
                }
            }
        }
    }

    private suspend fun toFile(tts: TextToSpeech, text: String, file: File) = suspendCancellableCoroutine { cont ->
        val id = "fluxo-" + System.nanoTime()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && cont.isActive) cont.resumeWithException(IOException("Erreur de synthèse vocale"))
            }
        })
        val r = tts.synthesizeToFile(text, Bundle(), file, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resumeWithException(IOException("Synthèse refusée"))
    }

    private fun chunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        text.split(Regex("(?<=[.!?\\n])")).forEach { s ->
            if (sb.length + s.length > max && sb.isNotEmpty()) {
                out += sb.toString(); sb.clear()
            }
            if (s.length > max) s.chunked(max).forEach { out += it } else sb.append(s)
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.filter { it.isNotBlank() }
    }

    suspend fun toMp3(ctx: Context, text: String, locale: Locale, rate: Float, pitch: Float, p: Progress): SavedFile {
        if (text.isBlank()) error("Écris un texte d'abord")
        val tts = init(ctx)
        val dir = Storage.workDir(ctx, "tts")
        try {
            if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) error("Langue non installée sur le téléphone (Réglages → Synthèse vocale)")
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            val parts = chunks(text, (TextToSpeech.getMaxSpeechInputLength() - 200).coerceAtLeast(500))
            val wavs = parts.mapIndexed { i, c ->
                p.update(0.8f * i / parts.size, "Lecture du texte (${i + 1}/${parts.size})…")
                File(dir, "p$i.wav").also { toFile(tts, c, it) }
            }
            val out = File(dir, "voix.mp3")
            val inputs = wavs.flatMap { listOf("-i", it.absolutePath) }
            val filter = wavs.indices.joinToString("") { "[$it:a]" } + "concat=n=${wavs.size}:v=0:a=1[a]"
            FF.run(inputs + listOf("-filter_complex", filter, "-map", "[a]", "-c:a", "libmp3lame", "-q:a", "3", out.absolutePath), 0) {}
            p.update(0.95f, "Enregistrement…")
            val name = text.trim().take(30).replace(Regex("\\s+"), "_")
            return Storage.save(ctx, out, "Voix_$name.mp3")
        } finally {
            tts.shutdown()
            dir.deleteRecursively()
        }
    }
}

// ============================================================== Unités et devises

data class MeasureUnit(val name: String, val factor: Double)

object Units {
    val categories: Map<String, List<MeasureUnit>> = linkedMapOf(
        "Longueur" to listOf(
            MeasureUnit("mm", 0.001), MeasureUnit("cm", 0.01), MeasureUnit("m", 1.0), MeasureUnit("km", 1000.0),
            MeasureUnit("pouce", 0.0254), MeasureUnit("pied", 0.3048), MeasureUnit("yard", 0.9144), MeasureUnit("mile", 1609.344),
        ),
        "Poids" to listOf(
            MeasureUnit("mg", 0.000001), MeasureUnit("g", 0.001), MeasureUnit("kg", 1.0), MeasureUnit("tonne", 1000.0),
            MeasureUnit("once", 0.028349523), MeasureUnit("livre", 0.45359237),
        ),
        "Température" to listOf(MeasureUnit("°C", 0.0), MeasureUnit("°F", 0.0), MeasureUnit("K", 0.0)),
        "Volume" to listOf(
            MeasureUnit("ml", 0.001), MeasureUnit("cl", 0.01), MeasureUnit("l", 1.0), MeasureUnit("m³", 1000.0),
            MeasureUnit("cuillère à café", 0.005), MeasureUnit("tasse", 0.25), MeasureUnit("gallon US", 3.785411784),
        ),
        "Surface" to listOf(
            MeasureUnit("cm²", 0.0001), MeasureUnit("m²", 1.0), MeasureUnit("are", 100.0), MeasureUnit("hectare", 10000.0),
            MeasureUnit("km²", 1_000_000.0), MeasureUnit("pied²", 0.09290304), MeasureUnit("acre", 4046.8564224),
        ),
        "Vitesse" to listOf(MeasureUnit("m/s", 1.0), MeasureUnit("km/h", 1 / 3.6), MeasureUnit("mph", 0.44704), MeasureUnit("nœud", 0.514444)),
        "Temps" to listOf(
            MeasureUnit("ms", 0.001), MeasureUnit("s", 1.0), MeasureUnit("min", 60.0), MeasureUnit("h", 3600.0),
            MeasureUnit("jour", 86400.0), MeasureUnit("semaine", 604800.0), MeasureUnit("an", 31_557_600.0),
        ),
        "Données" to listOf(
            MeasureUnit("octet", 1.0), MeasureUnit("Ko", 1024.0), MeasureUnit("Mo", 1024.0 * 1024), MeasureUnit("Go", 1024.0 * 1024 * 1024),
            MeasureUnit("To", 1024.0 * 1024 * 1024 * 1024), MeasureUnit("bit", 0.125), MeasureUnit("Mbit", 131072.0),
        ),
        "Énergie" to listOf(MeasureUnit("J", 1.0), MeasureUnit("kJ", 1000.0), MeasureUnit("cal", 4.184), MeasureUnit("kcal", 4184.0), MeasureUnit("kWh", 3_600_000.0)),
        "Pression" to listOf(MeasureUnit("Pa", 1.0), MeasureUnit("hPa", 100.0), MeasureUnit("bar", 100_000.0), MeasureUnit("atm", 101_325.0), MeasureUnit("psi", 6894.757)),
    )

    fun convert(cat: String, value: Double, from: MeasureUnit, to: MeasureUnit): Double {
        if (cat == "Température") {
            val c = when (from.name) { "°F" -> (value - 32) * 5 / 9; "K" -> value - 273.15; else -> value }
            return when (to.name) { "°F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
        }
        return value * from.factor / to.factor
    }

    private val client = OkHttpClient()

    /** Taux de change du jour (Banque centrale européenne via frankfurter.app). */
    fun rates(base: String): Map<String, Double> {
        val req = Request.Builder().url("https://api.frankfurter.app/latest?from=$base").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string()).getJSONObject("rates")
            return o.keys().asSequence().associateWith { o.getDouble(it) } + (base to 1.0)
        }
    }

    val currencies = listOf("EUR", "USD", "GBP", "CHF", "CAD", "JPY", "CNY", "BRL", "INR", "TRY", "AUD", "SEK", "NOK", "PLN", "MXN", "ZAR")
}
