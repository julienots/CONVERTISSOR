package com.superessence.app.transcribe

import android.content.Context
import android.net.Uri
import com.superessence.app.convert.FF
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class Language(val code: String, val label: String, val model: String, val sizeMb: Int)

/** Transcription hors-ligne (Vosk) d'une vidéo ou d'un audio en TXT + SRT. */
object Transcriber {
    val languages = listOf(
        Language("fr", "Français", "vosk-model-small-fr-0.22", 41),
        Language("en", "English", "vosk-model-small-en-us-0.15", 40),
        Language("es", "Español", "vosk-model-small-es-0.42", 39),
        Language("de", "Deutsch", "vosk-model-small-de-0.15", 45),
        Language("it", "Italiano", "vosk-model-small-it-0.22", 48),
        Language("pt", "Português", "vosk-model-small-pt-0.3", 31),
        Language("ar", "العربية", "vosk-model-ar-mgb2-0.4", 318),
    )

    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private fun modelRoot(ctx: Context, lang: Language) = File(ctx.filesDir, "vosk/${lang.model}")

    fun isInstalled(ctx: Context, lang: Language) = File(modelRoot(ctx, lang), ".ok").exists()

    private suspend fun ensureModel(ctx: Context, lang: Language, p: Progress): File {
        val root = modelRoot(ctx, lang)
        if (!isInstalled(ctx, lang)) {
            root.deleteRecursively()
            root.mkdirs()
            val url = "https://alphacephei.com/vosk/models/${lang.model}.zip"
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Téléchargement du modèle impossible (HTTP ${r.code})")
                val body = r.body ?: throw IOException("Modèle vide")
                val total = body.contentLength()
                var read = 0L
                val counting = object : java.io.FilterInputStream(body.byteStream()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            p.update(if (total > 0) 0.3f * read / total else -1f,
                                "Téléchargement du modèle ${lang.label} (une seule fois)… ${Storage.humanSize(read)}")
                        }
                        return n
                    }
                }
                ZipInputStream(counting.buffered(1 shl 16)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val e = zip.nextEntry ?: break
                        val f = File(root, e.name)
                        if (!f.canonicalPath.startsWith(root.canonicalPath)) continue
                        if (e.isDirectory) f.mkdirs() else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zip.copyTo(it, 1 shl 16) }
                        }
                    }
                }
            }
            File(root, ".ok").writeText("ok")
        }
        // Le zip contient un dossier racine : on cherche le dossier qui contient « am » ou « conf »
        return root.walkTopDown().maxDepth(2).firstOrNull { d ->
            d.isDirectory && (File(d, "am").exists() || File(d, "conf").exists())
        } ?: root
    }

    suspend fun run(ctx: Context, uri: Uri, lang: Language, p: Progress): JobState.Done {
        val model = ensureModel(ctx, lang, p)
        val name = Storage.displayName(ctx, uri)
        val base = Storage.baseName(name)
        val work = Storage.workDir(ctx, "stt")
        try {
            val pcm = File(work, "audio.pcm")
            p.update(0.3f, "Extraction de l'audio…")
            FF.convert(ctx, uri, pcm, listOf("-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-acodec", "pcm_s16le"), { f ->
                p.update(0.3f + 0.15f * f, "Extraction de l'audio…")
            })

            LibVosk.setLogLevel(LogLevel.WARNINGS)
            p.update(0.45f, "Chargement du modèle ${lang.label}…")
            val segments = ArrayList<Triple<Double, Double, String>>()
            fun collect(json: String) {
                val o = JSONObject(json)
                val text = o.optString("text").trim()
                if (text.isEmpty()) return
                val words = o.optJSONArray("result")
                val start = words?.optJSONObject(0)?.optDouble("start") ?: 0.0
                val end = words?.optJSONObject(words.length() - 1)?.optDouble("end") ?: start
                segments += Triple(start, end, text)
            }
            Model(model.absolutePath).use { m ->
                Recognizer(m, 16000f).use { rec ->
                    rec.setWords(true)
                    val total = pcm.length().coerceAtLeast(1)
                    val buf = ByteArray(16000 * 2) // 0,5 s d'audio
                    var done = 0L
                    pcm.inputStream().buffered(1 shl 16).use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            if (rec.acceptWaveForm(buf, n)) collect(rec.result)
                            done += n
                            val last = segments.lastOrNull()?.third?.takeLast(60) ?: ""
                            p.update(0.45f + 0.55f * done / total, "Transcription… $last")
                        }
                    }
                    collect(rec.finalResult)
                }
            }
            if (segments.isEmpty()) error("Aucune parole détectée")

            val text = segments.joinToString("\n") { it.third }
            val srt = buildString {
                segments.forEachIndexed { i, (s, e, t) ->
                    append(i + 1).append('\n')
                    append(ts(s)).append(" --> ").append(ts(e)).append('\n')
                    append(t).append("\n\n")
                }
            }
            val files = ArrayList<SavedFile>()
            for ((ext, content) in listOf("txt" to text, "srt" to srt)) {
                val out = Storage.openOutput(ctx, "${base}_transcription.$ext")
                out.stream.write(content.toByteArray(Charsets.UTF_8))
                files += out.finish()
            }
            return JobState.Done("Transcription terminée (${segments.size} phrases)", files, text)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun ts(sec: Double): String {
        val ms = (sec * 1000).toLong()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}
