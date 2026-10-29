package com.superessence.app.convert

import android.content.Context
import android.net.Uri
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Point d'entrée de toutes les conversions de l'écran « Convertir ». */
object Converter {

    suspend fun run(ctx: Context, uris: List<Uri>, kind: Kind, target: Target, p: Progress): JobState.Done {
        val names = uris.map { Storage.displayName(ctx, it) }
        val saved = ArrayList<SavedFile>()

        // Cas « tous les fichiers en un seul résultat »
        when {
            target.id == "zip" -> {
                val zipName = if (uris.size == 1) Storage.baseName(names[0]) + ".zip" else "Fluxo_${stamp()}.zip"
                val total = uris.sumOf { Storage.size(ctx, it).coerceAtLeast(0) }.coerceAtLeast(1)
                saved += Archives.zip(ctx, uris, zipName) { i, bytes ->
                    p.update(bytes.toFloat() / total, "Compression de ${names[i]}…")
                }
                return done(saved)
            }
            kind == Kind.IMAGE && target.id == "pdf" -> {
                val name = if (uris.size == 1) Storage.baseName(names[0]) + ".pdf" else "Images_${stamp()}.pdf"
                val out = Storage.openOutput(ctx, name)
                try {
                    Docs.imagesToPdf(ctx, uris, out.stream) { n -> p.update(n.toFloat() / uris.size, "Page $n / ${uris.size}") }
                } catch (e: Throwable) {
                    out.abort(); throw e
                }
                saved += out.finish()
                return done(saved)
            }
        }

        uris.forEachIndexed { i, uri ->
            coroutineContext.ensureActive()
            val name = names[i]
            val base = Storage.baseName(name)
            val prefix = if (uris.size > 1) "(${i + 1}/${uris.size}) " else ""
            fun step(f: Float, msg: String) = p.update((i + f.coerceIn(0f, 1f)) / uris.size, prefix + msg)
            step(0f, "Conversion de $name…")

            when (kind) {
                Kind.VIDEO, Kind.AUDIO -> when (target.id) {
                    "frames" -> saved += Media.framesToZip(ctx, uri, 1.0, "jpg") { step(it, "Extraction des images…") }
                    "transcribe" -> error("Utilise l'outil « Transcription » pour cette option")
                    else -> {
                        val (ext, args) = Formats.ffmpegArgs(target.id)
                        val tmp = File(Storage.workDir(ctx, "conv"), "out.$ext")
                        FF.convert(ctx, uri, tmp, args, { step(it, "Conversion en ${target.label}…") })
                        saved += Storage.save(ctx, tmp, "$base.$ext")
                        tmp.parentFile?.deleteRecursively()
                    }
                }
                Kind.IMAGE -> {
                    val bmp = Docs.decodeBitmap(ctx, uri)
                    val out = Storage.openOutput(ctx, "$base.${target.id}")
                    try {
                        Docs.writeBitmap(bmp, target.id, 92, out.stream)
                    } catch (e: Throwable) {
                        out.abort(); throw e
                    }
                    bmp.recycle()
                    saved += out.finish()
                }
                Kind.TEXT, Kind.DOCX, Kind.PDF -> {
                    if (kind == Kind.PDF && (target.id == "png" || target.id == "jpg")) {
                        val out = Storage.openOutput(ctx, "${base}_pages.zip")
                        try {
                            Docs.pdfToImages(ctx, uri, target.id, out.stream) { n, t -> step(n.toFloat() / t, "Page $n / $t") }
                        } catch (e: Throwable) {
                            out.abort(); throw e
                        }
                        saved += out.finish()
                    } else {
                        val text = when (kind) {
                            Kind.DOCX -> Docs.docxToText(ctx, uri)
                            Kind.PDF -> Docs.pdfToText(ctx, uri)
                            else -> Docs.readText(ctx, uri, name)
                        }
                        val out = Storage.openOutput(ctx, "$base.${target.id}")
                        try {
                            if (target.id == "pdf") Docs.textToPdf(text, out.stream)
                            else out.stream.write(text.toByteArray(Charsets.UTF_8))
                        } catch (e: Throwable) {
                            out.abort(); throw e
                        }
                        saved += out.finish()
                    }
                }
                Kind.ARCHIVE, Kind.APP -> when (target.id) {
                    "extract" -> saved += Archives.extract(ctx, uri) { step(0.5f, "Extraction : $it") }
                    else -> error("Utilise l'outil « XAPK → APK » pour cette option")
                }
                Kind.OTHER -> error("Aucune conversion disponible pour $name")
            }
        }
        return done(saved)
    }

    private fun done(files: List<SavedFile>) =
        JobState.Done("Terminé ! ${files.size} fichier(s) dans Téléchargements/${Storage.FOLDER}", files)

    fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
}

/** Outils audio/vidéo partagés (images d'une vidéo, compression). */
object Media {

    suspend fun framesToZip(ctx: Context, uri: Uri, fps: Double, format: String, onProgress: (Float) -> Unit): SavedFile {
        val dir = Storage.workDir(ctx, "frames")
        val pattern = File(dir, "image_%05d.$format").absolutePath
        val args = buildList {
            add("-vf"); add(if (fps > 0) "fps=$fps" else "null")
            if (fps <= 0) { add("-vsync"); add("0") }
            if (format == "jpg") { add("-q:v"); add("2") }
        }
        FF.convert(ctx, uri, File(pattern), args, onProgress)
        val frames = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        if (frames.isEmpty()) error("Aucune image extraite")
        val name = Storage.baseName(Storage.displayName(ctx, uri)) + "_images.zip"
        val out = Storage.openOutput(ctx, name)
        try {
            ZipOutputStream(out.stream.buffered(1 shl 20)).use { zip ->
                // Les JPG/PNG sont déjà compressés : on les stocke tels quels (plus rapide)
                zip.setLevel(0)
                frames.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            out.abort(); throw e
        } finally {
            dir.deleteRecursively()
        }
        return out.finish()
    }
}
