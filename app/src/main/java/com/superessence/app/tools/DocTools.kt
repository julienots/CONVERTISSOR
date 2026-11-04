package com.superessence.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.arthenica.ffmpegkit.FFprobeKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.superessence.app.convert.Docs
import com.superessence.app.convert.FF
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import com.superessence.app.ui.Op
import com.superessence.app.ui.Param
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

private fun done(files: List<SavedFile>, text: String? = null) =
    JobState.Done("Terminé ! ${files.size} fichier(s) dans Téléchargements/${Storage.FOLDER}", files, text)

private fun open(ctx: Context, uri: Uri): InputStream =
    ctx.contentResolver.openInputStream(uri) ?: error("Fichier illisible")

/** Empêche PDFBox de fermer le ZIP après chaque page. */
private class NoClose(private val out: java.io.OutputStream) : java.io.OutputStream() {
    override fun write(b: Int) = out.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun flush() = out.flush()
    override fun close() = out.flush()
}

// ============================================================== Images

object ImageTools {

    private fun outExt(name: String) = Storage.extension(name).let { if (it in setOf("png", "webp", "jpg")) it else "jpg" }

    private suspend fun Progress.each(ctx: Context, files: List<Uri>, suffix: String, transform: (Bitmap) -> Bitmap): JobState.Done {
        val out = ArrayList<SavedFile>()
        files.forEachIndexed { i, uri ->
            update(i.toFloat() / files.size, "Image ${i + 1} / ${files.size}…")
            val name = Storage.displayName(ctx, uri)
            val ext = outExt(name)
            val src = Docs.decodeBitmap(ctx, uri)
            val res = transform(src)
            val t = Storage.openOutput(ctx, "${Storage.baseName(name)}_$suffix.$ext")
            try {
                Docs.writeBitmap(res, ext, 92, t.stream)
            } catch (e: Throwable) {
                t.abort(); throw e
            }
            out += t.finish()
            if (res !== src) res.recycle()
            src.recycle()
        }
        return done(out)
    }

    private fun scaled(b: Bitmap, maxDim: Int): Bitmap {
        val s = min(1f, maxDim.toFloat() / max(b.width, b.height))
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }

    val ops = listOf(
        Op("resize", "Redimensionner", listOf(Param.Choice("m", "Taille max", listOf("640" to "640 px", "1080" to "1080 px", "1600" to "1600 px", "2560" to "2560 px"), "1080"))) { ctx, files, v ->
            val m = v["m"]!!.toInt()
            each(ctx, files, "${m}px") { scaled(it, m) }
        },
        Op("rotate", "Pivoter", listOf(Param.Choice("r", "Rotation", listOf("90" to "90° →", "270" to "90° ←", "180" to "180°", "flip" to "Miroir"), "90"))) { ctx, files, v ->
            each(ctx, files, "pivot") { b ->
                val m = Matrix()
                if (v["r"] == "flip") m.preScale(-1f, 1f) else m.postRotate(v["r"]!!.toFloat())
                Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            }
        },
        Op("gray", "Noir et blanc") { ctx, files, _ ->
            each(ctx, files, "nb") { b ->
                val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                val p = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                Canvas(out).drawBitmap(b, 0f, 0f, p)
                out
            }
        },
        Op("square", "Recadrer en carré") { ctx, files, _ ->
            each(ctx, files, "carre") { b ->
                val s = min(b.width, b.height)
                Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
            }
        },
        Op("watermark", "Ajouter un texte (filigrane)", listOf(Param.Field("t", "Texte", "© SuperEssence Studio"))) { ctx, files, v ->
            each(ctx, files, "filigrane") { b ->
                val out = b.copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(out)
                val size = max(18f, out.width / 28f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    alpha = 210
                    textSize = size
                    setShadowLayer(size / 6, 2f, 2f, Color.BLACK)
                }
                val text = v["t"]!!
                c.drawText(text, out.width - p.measureText(text) - size, out.height - size, p)
                out
            }
        },
        Op("strip", "Supprimer les infos (EXIF, GPS)", note = "Réenregistre la photo sans métadonnées ni position GPS.") { ctx, files, _ ->
            each(ctx, files, "propre") { it }
        },
        Op("compress", "Réduire le poids", listOf(Param.Choice("q", "Qualité", listOf("80" to "Bonne", "60" to "Moyenne", "40" to "Faible"), "60"))) { ctx, files, v ->
            val out = ArrayList<SavedFile>()
            files.forEachIndexed { i, uri ->
                update(i.toFloat() / files.size, "Image ${i + 1} / ${files.size}…")
                val b = Docs.decodeBitmap(ctx, uri, 2560)
                val t = Storage.openOutput(ctx, Storage.baseName(Storage.displayName(ctx, uri)) + "_leger.jpg")
                Docs.writeBitmap(b, "jpg", v["q"]!!.toInt(), t.stream)
                out += t.finish()
                b.recycle()
            }
            done(out)
        },
        Op("collage", "Assembler (collage)", listOf(Param.Choice("d", "Sens", listOf("v" to "Vertical", "h" to "Horizontal"), "v")), minFiles = 2,
            note = "Les images sont collées les unes après les autres.") { ctx, files, v ->
            val vertical = v["d"] == "v"
            val bmps = files.map { Docs.decodeBitmap(ctx, it, 1600) }
            val target = if (vertical) bmps.minOf { it.width } else bmps.minOf { it.height }
            val parts = bmps.map { b ->
                if (vertical) Bitmap.createScaledBitmap(b, target, max(1, b.height * target / b.width), true)
                else Bitmap.createScaledBitmap(b, max(1, b.width * target / b.height), target, true)
            }
            val w = if (vertical) target else parts.sumOf { it.width }
            val h = if (vertical) parts.sumOf { it.height } else target
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            var pos = 0f
            parts.forEach { p ->
                if (vertical) { c.drawBitmap(p, 0f, pos, null); pos += p.height } else { c.drawBitmap(p, pos, 0f, null); pos += p.width }
            }
            val t = Storage.openOutput(ctx, "Collage_${MediaTools.stamp()}.jpg")
            Docs.writeBitmap(out, "jpg", 92, t.stream)
            done(listOf(t.finish()))
        },
    )
}

// ============================================================== PDF

object PdfTools {

    private fun ranges(spec: String, count: Int): List<Int> =
        spec.split(',', ';').flatMap { part ->
            val p = part.trim()
            when {
                p.isEmpty() -> emptyList()
                p.contains('-') -> {
                    val a = p.substringBefore('-').trim().toIntOrNull() ?: 1
                    val b = p.substringAfter('-').trim().toIntOrNull() ?: count
                    (a..b).toList()
                }
                else -> listOfNotNull(p.toIntOrNull())
            }
        }.filter { it in 1..count }.distinct()

    private fun save(ctx: Context, doc: PDDocument, name: String): SavedFile {
        val t = Storage.openOutput(ctx, name)
        try {
            doc.save(t.stream)
        } catch (e: Throwable) {
            t.abort(); throw e
        }
        return t.finish()
    }

    val ops = listOf(
        Op("merge", "Fusionner", minFiles = 2, note = "Les PDF sont assemblés dans l'ordre de la liste.") { ctx, files, _ ->
            PDFBoxResourceLoader.init(ctx)
            update(-1f, "Fusion de ${files.size} PDF…")
            val t = Storage.openOutput(ctx, "Fusion_${MediaTools.stamp()}.pdf")
            val streams = files.map { open(ctx, it) }
            try {
                PDFMergerUtility().apply {
                    streams.forEach { addSource(it) }
                    destinationStream = t.stream
                    mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
                }
            } catch (e: Throwable) {
                t.abort(); throw e
            } finally {
                streams.forEach { runCatching { it.close() } }
            }
            done(listOf(t.finish()))
        },
        Op("extract", "Extraire des pages", listOf(Param.Field("p", "Pages (ex. 1-3,5)", "1"))) { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val out = files.map { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        val pages = ranges(v["p"]!!, doc.numberOfPages)
                        if (pages.isEmpty()) error("Aucune page valide (le PDF a ${doc.numberOfPages} pages)")
                        PDDocument().use { nd ->
                            pages.forEach { nd.importPage(doc.getPage(it - 1)) }
                            save(ctx, nd, Storage.baseName(Storage.displayName(ctx, uri)) + "_pages.pdf")
                        }
                    }
                }
            }
            done(out)
        },
        Op("split", "Séparer chaque page", note = "Une page = un PDF, le tout dans un ZIP.") { ctx, files, _ ->
            PDFBoxResourceLoader.init(ctx)
            val out = files.map { uri ->
                val base = Storage.baseName(Storage.displayName(ctx, uri))
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        val t = Storage.openOutput(ctx, "${base}_pages.zip")
                        ZipOutputStream(t.stream).use { zip ->
                            for (i in 0 until doc.numberOfPages) {
                                update(i.toFloat() / doc.numberOfPages, "Page ${i + 1} / ${doc.numberOfPages}")
                                PDDocument().use { nd ->
                                    nd.importPage(doc.getPage(i))
                                    zip.putNextEntry(ZipEntry("%s_page_%03d.pdf".format(base, i + 1)))
                                    nd.save(NoClose(zip))
                                    zip.closeEntry()
                                }
                            }
                        }
                        t.finish()
                    }
                }
            }
            done(out)
        },
        Op("rotate", "Pivoter les pages", listOf(Param.Choice("r", "Rotation", listOf("90" to "90° →", "270" to "90° ←", "180" to "180°"), "90"))) { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val r = v["r"]!!.toInt()
            val out = files.map { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        for (i in 0 until doc.numberOfPages) doc.getPage(i).let { it.rotation = (it.rotation + r) % 360 }
                        save(ctx, doc, Storage.baseName(Storage.displayName(ctx, uri)) + "_pivote.pdf")
                    }
                }
            }
            done(out)
        },
        Op("delete", "Supprimer des pages", listOf(Param.Field("p", "Pages à supprimer (ex. 2,4-6)", "1"))) { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val out = files.map { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        val pages = ranges(v["p"]!!, doc.numberOfPages).sortedDescending()
                        if (pages.size >= doc.numberOfPages) error("Impossible de supprimer toutes les pages")
                        pages.forEach { doc.removePage(it - 1) }
                        save(ctx, doc, Storage.baseName(Storage.displayName(ctx, uri)) + "_modifie.pdf")
                    }
                }
            }
            done(out)
        },
        Op("protect", "Protéger par mot de passe", listOf(Param.Field("pw", "Mot de passe", ""))) { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val pw = v["pw"]!!
            if (pw.isBlank()) error("Choisis un mot de passe")
            val out = files.map { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        val policy = StandardProtectionPolicy(pw, pw, AccessPermission())
                        policy.encryptionKeyLength = 128
                        doc.protect(policy)
                        save(ctx, doc, Storage.baseName(Storage.displayName(ctx, uri)) + "_protege.pdf")
                    }
                }
            }
            done(out)
        },
        Op("unlock", "Retirer le mot de passe", listOf(Param.Field("pw", "Mot de passe actuel", ""))) { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val out = files.map { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input, v["pw"]!!).use { doc ->
                        doc.isAllSecurityToBeRemoved = true
                        save(ctx, doc, Storage.baseName(Storage.displayName(ctx, uri)) + "_deverrouille.pdf")
                    }
                }
            }
            done(out)
        },
        Op("images", "Images → PDF", note = "Choisis des photos : un seul PDF avec une page par photo.") { ctx, files, _ ->
            val t = Storage.openOutput(ctx, "Images_${MediaTools.stamp()}.pdf")
            Docs.imagesToPdf(ctx, files, t.stream) { n -> update(n.toFloat() / files.size, "Page $n / ${files.size}") }
            done(listOf(t.finish()))
        },
    )
}

// ============================================================== Infos, empreintes, OCR, QR

object InfoTools {

    private fun hashes(ctx: Context, uri: Uri, p: Progress): String {
        val algs = listOf("MD5", "SHA-1", "SHA-256").map { MessageDigest.getInstance(it) }
        val total = Storage.size(ctx, uri).coerceAtLeast(1)
        var read = 0L
        open(ctx, uri).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                algs.forEach { it.update(buf, 0, n) }
                read += n
                p.update(read.toFloat() / total, "Calcul des empreintes…")
            }
        }
        return algs.joinToString("\n") { d -> d.algorithm + " : " + d.digest().joinToString("") { "%02x".format(it) } }
    }

    private fun details(ctx: Context, uri: Uri): String = buildString {
        val name = Storage.displayName(ctx, uri)
        appendLine("Nom : $name")
        appendLine("Taille : ${Storage.humanSize(Storage.size(ctx, uri))} (${Storage.size(ctx, uri)} octets)")
        appendLine("Type : ${Storage.mimeOf(ctx, uri, name)}")
        runCatching {
            val info = FFprobeKit.getMediaInformation(FF.input(ctx, uri)).mediaInformation
            if (info != null) {
                appendLine("Format : ${info.longFormat ?: info.format}")
                info.duration?.toDoubleOrNull()?.let { d -> appendLine("Durée : %d:%02d".format(d.toInt() / 60, d.toInt() % 60)) }
                info.bitrate?.toLongOrNull()?.let { appendLine("Débit : ${it / 1000} kb/s") }
                info.streams.orEmpty().forEach { s ->
                    when (s.type) {
                        "video" -> appendLine("Vidéo : ${s.codec} ${s.width}×${s.height} ${s.averageFrameRate?.let { fr -> fps(fr) } ?: ""}")
                        "audio" -> appendLine("Audio : ${s.codec} ${s.sampleRate ?: ""} Hz ${s.channelLayout ?: ""}")
                        "subtitle" -> appendLine("Sous-titres : ${s.codec}")
                    }
                }
            }
        }
        runCatching {
            open(ctx, uri).use { input ->
                val e = ExifInterface(input)
                e.getAttribute(ExifInterface.TAG_MAKE)?.let { appendLine("Appareil : $it ${e.getAttribute(ExifInterface.TAG_MODEL) ?: ""}") }
                e.getAttribute(ExifInterface.TAG_DATETIME)?.let { appendLine("Date de prise : $it") }
                val ll = FloatArray(2)
                @Suppress("DEPRECATION")
                if (e.getLatLong(ll)) appendLine("GPS : ${ll[0]}, ${ll[1]}")
            }
        }
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(ctx, uri).use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0) appendLine("Dimensions : ${o.outWidth}×${o.outHeight} px")
        }
    }

    private fun fps(fr: String): String {
        val a = fr.substringBefore('/').toDoubleOrNull() ?: return ""
        val b = fr.substringAfter('/', "1").toDoubleOrNull() ?: 1.0
        return if (b > 0 && a > 0) "· %.0f i/s".format(a / b) else ""
    }

    private suspend fun ocr(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromFilePath(ctx, uri))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun decodeQr(bmp: Bitmap): String? {
        val small = if (max(bmp.width, bmp.height) > 1600) {
            val s = 1600f / max(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        } else bmp
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(small.width, small.height, px)))).text
        }.getOrNull()
    }

    val ops = listOf(
        Op("info", "Infos détaillées") { ctx, files, _ ->
            val text = files.joinToString("\n\n") { details(ctx, it) }
            JobState.Done("Infos de ${files.size} fichier(s)", text = text)
        },
        Op("hash", "Empreintes MD5 / SHA") { ctx, files, _ ->
            val text = files.joinToString("\n\n") { Storage.displayName(ctx, it) + "\n" + hashes(ctx, it, this) }
            JobState.Done("Empreintes calculées", text = text)
        },
    )

    val ocrOps = listOf(
        Op("ocr", "Photo → texte", note = "Reconnaît le texte (lettres latines) sur des photos, captures, documents scannés. Hors connexion.") { ctx, files, _ ->
            val sb = StringBuilder()
            files.forEachIndexed { i, uri ->
                update(i.toFloat() / files.size, "Lecture de l'image ${i + 1} / ${files.size}…")
                if (files.size > 1) sb.append("— ").append(Storage.displayName(ctx, uri)).append(" —\n")
                sb.append(ocr(ctx, uri)).append("\n\n")
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) error("Aucun texte trouvé")
            val t = Storage.openOutput(ctx, "Texte_${MediaTools.stamp()}.txt")
            t.stream.write(text.toByteArray())
            done(listOf(t.finish()), text)
        },
        Op("ocr_pdf", "Photo → PDF texte", note = "Crée un PDF à partir du texte reconnu.") { ctx, files, _ ->
            val parts = ArrayList<String>()
            for ((i, uri) in files.withIndex()) {
                update(i.toFloat() / files.size, "Lecture de l'image ${i + 1} / ${files.size}…")
                parts += ocr(ctx, uri)
            }
            val text = parts.joinToString("\n\n").trim()
            if (text.isEmpty()) error("Aucun texte trouvé")
            val t = Storage.openOutput(ctx, "Texte_${MediaTools.stamp()}.pdf")
            Docs.textToPdf(text, t.stream)
            done(listOf(t.finish()), text)
        },
        Op("qr", "Lire un QR code dans une image") { ctx, files, _ ->
            val text = files.joinToString("\n") { uri ->
                val b = Docs.decodeBitmap(ctx, uri, 2400)
                (decodeQr(b) ?: "Aucun QR code trouvé").also { b.recycle() }
            }
            JobState.Done("Lecture terminée", text = text)
        },
    )
}
