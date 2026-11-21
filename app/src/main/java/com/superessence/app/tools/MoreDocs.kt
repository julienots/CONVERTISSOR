package com.superessence.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import com.superessence.app.convert.Docs
import com.superessence.app.convert.FF
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import com.superessence.app.tools.MediaTools.stamp
import com.superessence.app.ui.Op
import com.superessence.app.ui.Param
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun finished(files: List<SavedFile>, text: String? = null) =
    JobState.Done("Terminé : ${files.size} fichier(s) dans Téléchargements/${Storage.FOLDER}", files, text)

// ============================================================== Photo

object MoreImages {

    private fun outExt(name: String) = Storage.extension(name).let { if (it in setOf("png", "webp", "jpg")) it else "jpg" }

    /** Applique [transform] à chaque image. [ext] force un format (ex. PNG pour la transparence). */
    private suspend fun Progress.each(
        ctx: Context, files: List<Uri>, suffix: String, ext: String? = null, maxDim: Int = 4096, transform: (Bitmap) -> Bitmap,
    ): JobState.Done {
        val out = ArrayList<SavedFile>()
        files.forEachIndexed { i, uri ->
            update(i.toFloat() / files.size, "Image ${i + 1} / ${files.size}…")
            val name = Storage.displayName(ctx, uri)
            val e = ext ?: outExt(name)
            val src = Docs.decodeBitmap(ctx, uri, maxDim)
            val res = transform(src)
            val t = Storage.openOutput(ctx, "${Storage.baseName(name)}_$suffix.$e")
            try {
                Docs.writeBitmap(res, e, 92, t.stream)
            } catch (ex: Throwable) {
                t.abort(); throw ex
            }
            out += t.finish()
            if (res !== src) res.recycle()
            src.recycle()
        }
        return finished(out)
    }

    private fun filtered(b: Bitmap, cm: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    private fun cropRatio(b: Bitmap, a: Int, c: Int): Bitmap {
        val targetW = min(b.width.toDouble(), b.height * a.toDouble() / c).toInt().coerceAtLeast(1)
        val targetH = min(b.height.toDouble(), b.width * c.toDouble() / a).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(b, (b.width - targetW) / 2, (b.height - targetH) / 2, targetW, targetH)
    }

    private fun blur(b: Bitmap, factor: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(b, max(1, b.width / factor), max(1, b.height / factor), true)
        val smaller = Bitmap.createScaledBitmap(small, max(1, small.width / 2), max(1, small.height / 2), true)
        val back = Bitmap.createScaledBitmap(smaller, b.width, b.height, true)
        small.recycle(); smaller.recycle()
        return back
    }

    private fun boldPaint(size: Float, color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /** Texte centré avec contour noir (style mème). */
    private fun drawOutlined(c: Canvas, text: String, x: Float, y: Float, size: Float) {
        val stroke = boldPaint(size, Color.BLACK).apply { style = Paint.Style.STROKE; strokeWidth = size / 8 }
        val fill = boldPaint(size)
        c.drawText(text, x, y, stroke)
        c.drawText(text, x, y, fill)
    }

    private fun fitSize(text: String, maxW: Float, start: Float): Float {
        var s = start
        val p = boldPaint(s)
        while (s > 12 && p.measureText(text) > maxW) { s *= 0.9f; p.textSize = s }
        return s
    }

    private fun sharpen(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height
        val src = IntArray(w * h); b.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            var r = 0; var g = 0; var bl = 0
            for ((k, wt) in listOf(i to 5, i - 1 to -1, i + 1 to -1, i - w to -1, i + w to -1)) {
                val c = src[k]
                r += (c shr 16 and 255) * wt; g += (c shr 8 and 255) * wt; bl += (c and 255) * wt
            }
            dst[i] = (src[i] and -0x1000000) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        }
        return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
    }

    val ops = listOf(
        Op("format", "Changer de format", listOf(Param.Choice("f", "Format", listOf("jpg" to "JPG", "png" to "PNG", "webp" to "WEBP"), "jpg"))) { ctx, files, v ->
            each(ctx, files, "converti", v["f"]) { it }
        },
        Op("crop", "Recadrer (format)", listOf(Param.Choice("r", "Format", listOf("16:9" to "16:9", "4:3" to "4:3", "3:2" to "3:2", "9:16" to "9:16 (story)", "4:5" to "4:5 (Instagram)"), "16:9"))) { ctx, files, v ->
            val (a, c) = v["r"]!!.split(':').map { it.toInt() }
            each(ctx, files, "recadre") { cropRatio(it, a, c) }
        },
        Op("exact", "Taille exacte", listOf(Param.Field("w", "Largeur (px)", "1080", number = true), Param.Field("h", "Hauteur (px)", "1080", number = true)),
            note = "L'image est recadrée au centre pour remplir exactement la taille demandée.") { ctx, files, v ->
            val w = (v["w"]!!.toDoubleOrNull() ?: 1080.0).toInt().coerceIn(8, 8000)
            val h = (v["h"]!!.toDoubleOrNull() ?: 1080.0).toInt().coerceIn(8, 8000)
            each(ctx, files, "${w}x$h") { b ->
                val c = cropRatio(b, w, h)
                Bitmap.createScaledBitmap(c, w, h, true).also { if (c !== b) c.recycle() }
            }
        },
        Op(
            "filter", "Filtres",
            listOf(Param.Choice("f", "Filtre", listOf(
                "sepia" to "Sépia", "invert" to "Négatif", "bright" to "Plus lumineux", "dark" to "Plus sombre", "contrast" to "Contraste +",
                "vivid" to "Couleurs vives", "fade" to "Délavé", "warm" to "Chaud", "cold" to "Froid",
            ), "sepia")),
        ) { ctx, files, v ->
            val cm = when (v["f"]) {
                "invert" -> ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
                "bright" -> ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 30f, 0f, 1f, 0f, 0f, 30f, 0f, 0f, 1f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))
                "dark" -> ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, -35f, 0f, 1f, 0f, 0f, -35f, 0f, 0f, 1f, 0f, -35f, 0f, 0f, 0f, 1f, 0f))
                "contrast" -> { val k = 1.3f; val t = (1 - k) * 128; ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)) }
                "vivid" -> ColorMatrix().apply { setSaturation(1.6f) }
                "fade" -> ColorMatrix().apply { setSaturation(0.5f); postConcat(ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 30f, 0f, 0.85f, 0f, 0f, 30f, 0f, 0f, 0.85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))) }
                "warm" -> ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0.85f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
                "cold" -> ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
                else -> ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            }
            each(ctx, files, v["f"]!!) { filtered(it, cm) }
        },
        Op("blur", "Flou / pixels", listOf(Param.Choice("m", "Effet", listOf("light" to "Flou léger", "strong" to "Flou fort", "pixel" to "Pixeliser"), "light"))) { ctx, files, v ->
            each(ctx, files, v["m"]!!, maxDim = 2400) { b ->
                when (v["m"]) {
                    "pixel" -> {
                        val s = Bitmap.createScaledBitmap(b, max(1, b.width / 24), max(1, b.height / 24), false)
                        Bitmap.createScaledBitmap(s, b.width, b.height, false).also { s.recycle() }
                    }
                    "strong" -> blur(b, 16)
                    else -> blur(b, 5)
                }
            }
        },
        Op("sharpen", "Plus net", note = "Renforce les contours (photos un peu floues).") { ctx, files, _ ->
            each(ctx, files, "net", maxDim = 2560) { sharpen(it) }
        },
        Op("vignette", "Vignette (bords assombris)") { ctx, files, _ ->
            each(ctx, files, "vignette") { b ->
                val out = b.copy(Bitmap.Config.ARGB_8888, true)
                val r = max(out.width, out.height) * 0.75f
                val p = Paint().apply {
                    shader = RadialGradient(out.width / 2f, out.height / 2f, r, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(170, 0, 0, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                }
                Canvas(out).drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), p)
                out
            }
        },
        Op("round", "Coins arrondis (PNG)", listOf(Param.Choice("r", "Arrondi", listOf("0.05" to "Léger", "0.1" to "Moyen", "0.2" to "Fort"), "0.1"))) { ctx, files, v ->
            each(ctx, files, "arrondi", "png") { b ->
                val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                val rad = min(b.width, b.height) * v["r"]!!.toFloat()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                Canvas(out).drawRoundRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), rad, rad, p)
                out
            }
        },
        Op("circle", "Rond (photo de profil)", note = "Recadre en cercle, fond transparent (PNG).") { ctx, files, _ ->
            each(ctx, files, "rond", "png") { b ->
                val sq = cropRatio(b, 1, 1)
                val out = Bitmap.createBitmap(sq.width, sq.height, Bitmap.Config.ARGB_8888)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
                Canvas(out).drawCircle(sq.width / 2f, sq.height / 2f, sq.width / 2f, p)
                if (sq !== b) sq.recycle()
                out
            }
        },
        Op(
            "border", "Cadre / bordure",
            listOf(
                Param.Choice("c", "Couleur", listOf("w" to "Blanc", "k" to "Noir", "g" to "Gris"), "w"),
                Param.Choice("s", "Épaisseur", listOf("0.02" to "Fine", "0.05" to "Moyenne", "0.1" to "Épaisse"), "0.05"),
            ),
        ) { ctx, files, v ->
            val color = when (v["c"]) { "k" -> Color.BLACK; "g" -> Color.rgb(128, 128, 128); else -> Color.WHITE }
            each(ctx, files, "cadre") { b ->
                val m = (max(b.width, b.height) * v["s"]!!.toFloat()).roundToInt()
                val out = Bitmap.createBitmap(b.width + 2 * m, b.height + 2 * m, Bitmap.Config.ARGB_8888)
                Canvas(out).apply { drawColor(color); drawBitmap(b, m.toFloat(), m.toFloat(), null) }
                out
            }
        },
        Op(
            "pad", "Carré sans recadrer",
            listOf(Param.Choice("bg", "Fond", listOf("blur" to "Flou", "w" to "Blanc", "k" to "Noir"), "blur")),
            note = "Toute la photo tient dans un carré (pratique pour Instagram).",
        ) { ctx, files, v ->
            each(ctx, files, "carre", maxDim = 3000) { b ->
                val s = max(b.width, b.height)
                val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                when (v["bg"]) {
                    "w" -> c.drawColor(Color.WHITE)
                    "k" -> c.drawColor(Color.BLACK)
                    else -> {
                        val sq = cropRatio(b, 1, 1)
                        val bl = blur(Bitmap.createScaledBitmap(sq, s, s, true), 12)
                        c.drawBitmap(bl, 0f, 0f, null)
                        c.drawColor(Color.argb(60, 0, 0, 0))
                        bl.recycle()
                    }
                }
                c.drawBitmap(b, (s - b.width) / 2f, (s - b.height) / 2f, null)
                out
            }
        },
        Op("grid", "Découper en grille", listOf(Param.Choice("g", "Grille", listOf("3x1" to "3 côte à côte", "3x3" to "3 × 3", "2x2" to "2 × 2", "1x2" to "2 l'une sous l'autre"), "3x3")),
            note = "Pour publier un panorama ou une mosaïque en plusieurs posts.") { ctx, files, v ->
            val (cols, rows) = v["g"]!!.split('x').map { it.toInt() }
            val out = ArrayList<SavedFile>()
            files.forEach { uri ->
                val name = Storage.baseName(Storage.displayName(ctx, uri))
                val b = Docs.decodeBitmap(ctx, uri, 4096)
                val w = b.width / cols; val h = b.height / rows
                var n = 1
                for (r in 0 until rows) for (c in 0 until cols) {
                    val part = Bitmap.createBitmap(b, c * w, r * h, w, h)
                    val t = Storage.openOutput(ctx, "${name}_%02d.jpg".format(n++), name)
                    Docs.writeBitmap(part, "jpg", 95, t.stream)
                    out += t.finish()
                    part.recycle()
                }
                b.recycle()
            }
            finished(out)
        },
        Op("meme", "Mème (texte haut et bas)", listOf(Param.Field("top", "Texte du haut", "QUAND TU"), Param.Field("bottom", "Texte du bas", "TROUVES LE BON OUTIL"))) { ctx, files, v ->
            each(ctx, files, "meme", maxDim = 2000) { b ->
                val out = b.copy(Bitmap.Config.ARGB_8888, true)
                val c = Canvas(out)
                val top = v["top"]!!.uppercase(); val bottom = v["bottom"]!!.uppercase()
                val maxW = out.width * 0.94f
                if (top.isNotBlank()) { val s = fitSize(top, maxW, out.width / 9f); drawOutlined(c, top, out.width / 2f, s * 1.1f, s) }
                if (bottom.isNotBlank()) { val s = fitSize(bottom, maxW, out.width / 9f); drawOutlined(c, bottom, out.width / 2f, out.height - s * 0.4f, s) }
                out
            }
        },
        Op("caption", "Légende (bandeau en bas)", listOf(Param.Field("t", "Texte", "Vacances 2026"))) { ctx, files, v ->
            each(ctx, files, "legende") { b ->
                val bar = (b.height * 0.12f).roundToInt().coerceAtLeast(40)
                val out = Bitmap.createBitmap(b.width, b.height + bar, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                c.drawColor(Color.WHITE)
                c.drawBitmap(b, 0f, 0f, null)
                val text = v["t"]!!
                val s = fitSize(text, b.width * 0.9f, bar * 0.5f)
                c.drawText(text, b.width / 2f, b.height + bar / 2f + s / 3, boldPaint(s, Color.rgb(30, 30, 30)))
                out
            }
        },
        Op("date", "Date de prise dans un coin", note = "Utilise la date enregistrée par l'appareil photo (EXIF).") { ctx, files, _ ->
            val out = ArrayList<SavedFile>()
            files.forEachIndexed { i, uri ->
                update(i.toFloat() / files.size, "Image ${i + 1} / ${files.size}…")
                val raw = runCatching { open(ctx, uri).use { ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ExifInterface(open(ctx, uri)).getAttribute(ExifInterface.TAG_DATETIME) } }.getOrNull()
                val label = raw?.let { r -> runCatching { val p = r.split(' ')[0].split(':'); "${p[2]}/${p[1]}/${p[0]}" }.getOrNull() } ?: java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRANCE).format(java.util.Date())
                val b = Docs.decodeBitmap(ctx, uri, 4096)
                val o = b.copy(Bitmap.Config.ARGB_8888, true)
                val size = max(20f, o.width / 30f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 150, 40); textSize = size; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
                    setShadowLayer(size / 6, 1f, 1f, Color.BLACK)
                }
                Canvas(o).drawText(label, o.width - size, o.height - size, p)
                val name = Storage.displayName(ctx, uri)
                val t = Storage.openOutput(ctx, "${Storage.baseName(name)}_date.jpg")
                Docs.writeBitmap(o, "jpg", 93, t.stream)
                out += t.finish()
                o.recycle(); b.recycle()
            }
            finished(out)
        },
        Op("sticker", "Sticker WhatsApp (512 × 512)", note = "Image WEBP carrée avec fond transparent autour.") { ctx, files, _ ->
            each(ctx, files, "sticker", "webp") { b ->
                val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val s = min(496f / b.width, 496f / b.height)
                val w = b.width * s; val h = b.height * s
                Canvas(out).drawBitmap(b, null, RectF((512 - w) / 2, (512 - h) / 2, (512 + w) / 2, (512 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                out
            }
        },
        Op("icons", "Icônes d'appli (toutes tailles)", note = "Crée un ZIP avec les tailles Android, iOS et favicon.") { ctx, files, _ ->
            val sizes = listOf(16, 32, 48, 72, 96, 120, 144, 152, 180, 192, 256, 512, 1024)
            val out = files.map { uri ->
                val b = Docs.decodeBitmap(ctx, uri, 2048)
                val sq = cropRatio(b, 1, 1)
                val t = Storage.openOutput(ctx, Storage.baseName(Storage.displayName(ctx, uri)) + "_icones.zip")
                ZipOutputStream(t.stream).use { zip ->
                    sizes.forEach { s ->
                        val ic = Bitmap.createScaledBitmap(sq, s, s, true)
                        zip.putNextEntry(ZipEntry("icon_${s}x$s.png"))
                        ic.compress(Bitmap.CompressFormat.PNG, 100, NoClose(zip))
                        zip.closeEntry()
                        ic.recycle()
                    }
                }
                b.recycle()
                t.finish()
            }
            finished(out)
        },
        Op("flatten", "Fond transparent → blanc") { ctx, files, _ ->
            each(ctx, files, "fond_blanc", "jpg") { b ->
                val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
                out
            }
        },
        Op("vflip", "Retourner (haut ↔ bas)") { ctx, files, _ ->
            each(ctx, files, "retourne") { b -> Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { preScale(1f, -1f) }, true) }
        },
        Op("palette", "Couleurs dominantes", note = "Donne les codes des 8 couleurs principales.") { ctx, files, _ ->
            val text = files.joinToString("\n\n") { uri ->
                val b = Docs.decodeBitmap(ctx, uri, 200)
                val counts = HashMap<Int, Int>()
                val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                px.forEach { c -> if (Color.alpha(c) > 128) { val k = ((Color.red(c) / 32) shl 6) or ((Color.green(c) / 32) shl 3) or (Color.blue(c) / 32); counts[k] = (counts[k] ?: 0) + 1 } }
                val total = counts.values.sum().coerceAtLeast(1)
                Storage.displayName(ctx, uri) + "\n" + counts.entries.sortedByDescending { it.value }.take(8).joinToString("\n") { (k, n) ->
                    val r = (k shr 6 and 7) * 32 + 16; val g = (k shr 3 and 7) * 32 + 16; val bl = (k and 7) * 32 + 16
                    "#%02X%02X%02X  %d %%".format(r, g, bl, n * 100 / total)
                }.also { b.recycle() }
            }
            JobState.Done("Couleurs trouvées", text = text)
        },
        Op("ascii", "Image → texte ASCII", note = "Transforme l'image en dessin fait de caractères.") { ctx, files, _ ->
            val chars = "@%#*+=-:. "
            val text = files.joinToString("\n\n") { uri ->
                val src = Docs.decodeBitmap(ctx, uri, 1000)
                val w = 70; val h = max(1, (src.height * w / src.width.toFloat() / 2).toInt())
                val b = Bitmap.createScaledBitmap(src, w, h, true)
                buildString {
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            val c = b.getPixel(x, y)
                            val l = (0.3 * Color.red(c) + 0.59 * Color.green(c) + 0.11 * Color.blue(c)) / 255
                            append(chars[(l * (chars.length - 1)).toInt()])
                        }
                        append('\n')
                    }
                }.also { b.recycle(); src.recycle() }
            }
            val t = Storage.openOutput(ctx, "ASCII_${stamp()}.txt")
            t.stream.write(text.toByteArray())
            finished(listOf(t.finish()), text)
        },
        Op("gif", "Photos → GIF animé", listOf(Param.Field("d", "Secondes par photo", "0.5", number = true)), minFiles = 2) { ctx, files, v ->
            val d = (v["d"]!!.replace(',', '.').toDoubleOrNull() ?: 0.5).coerceIn(0.05, 10.0)
            val dir = Storage.workDir(ctx, "gif")
            files.forEachIndexed { i, uri ->
                update(0.5f * (i + 1) / files.size, "Préparation des photos…")
                val b = Docs.decodeBitmap(ctx, uri, 720)
                val sq = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
                val s = min(720f / b.width, 720f / b.height)
                val w = b.width * s; val h = b.height * s
                Canvas(sq).apply { drawColor(Color.WHITE); drawBitmap(b, null, RectF((720 - w) / 2, (720 - h) / 2, (720 + w) / 2, (720 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG)) }
                File(dir, "img%04d.png".format(i + 1)).outputStream().use { sq.compress(Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle(); sq.recycle()
            }
            val tmp = File(dir, "anim.gif")
            FF.run(
                listOf("-framerate", String.format(java.util.Locale.US, "%.4f", 1 / d), "-i", File(dir, "img%04d.png").absolutePath,
                    "-vf", "scale=480:-2:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0", tmp.absolutePath),
                (d * files.size * 1000).toLong(),
            ) { update(0.5f + 0.5f * it, "Création du GIF…") }
            val saved = Storage.save(ctx, tmp, "Animation_${stamp()}.gif")
            dir.deleteRecursively()
            finished(listOf(saved))
        },
    )
}

// ============================================================== PDF

object MorePdf {

    private fun base(ctx: Context, uri: Uri) = Storage.baseName(Storage.displayName(ctx, uri))

    private fun save(ctx: Context, doc: PDDocument, name: String): SavedFile {
        val t = Storage.openOutput(ctx, name)
        try { doc.save(t.stream) } catch (e: Throwable) { t.abort(); throw e }
        return t.finish()
    }

    private fun ranges(spec: String, count: Int): List<Int> =
        spec.split(',', ';').flatMap { part ->
            val p = part.trim()
            when {
                p.isEmpty() -> emptyList()
                p.contains('-') -> {
                    val a = p.substringBefore('-').trim().toIntOrNull() ?: 1
                    val b = p.substringAfter('-').trim().toIntOrNull() ?: count
                    if (a <= b) (a..b).toList() else (a downTo b).toList()
                }
                else -> listOfNotNull(p.toIntOrNull())
            }
        }.filter { it in 1..count }

    private inline fun Progress.perPdf(ctx: Context, files: List<Uri>, block: (Uri, PDDocument) -> SavedFile): JobState.Done {
        PDFBoxResourceLoader.init(ctx)
        val out = files.mapIndexed { i, uri ->
            update(i.toFloat() / files.size, "PDF ${i + 1} / ${files.size}…")
            open(ctx, uri).use { input -> PDDocument.load(input).use { doc -> block(uri, doc) } }
        }
        return finished(out)
    }

    val ops = listOf(
        Op("compress", "Réduire le poids", listOf(Param.Choice("q", "Qualité", listOf("75" to "Bonne", "55" to "Moyenne", "35" to "Faible"), "55")),
            note = "Les pages sont transformées en images compressées : le texte ne sera plus sélectionnable.") { ctx, files, v ->
            val q = v["q"]!!.toInt()
            val out = files.mapIndexed { fi, uri ->
                val tmp = File(Storage.workDir(ctx, "pdfc"), "in.pdf")
                Storage.copyToFile(ctx, uri, tmp)
                val doc = PdfDocument()
                val t = Storage.openOutput(ctx, base(ctx, uri) + "_leger.pdf")
                try {
                    android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { r ->
                            for (i in 0 until r.pageCount) {
                                update((fi + i.toFloat() / r.pageCount) / files.size, "Page ${i + 1} / ${r.pageCount}")
                                r.openPage(i).use { page ->
                                    val scale = if (q >= 75) 2f else 1.5f
                                    val bmp = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
                                    val jpg = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    val p = doc.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create())
                                    p.canvas.drawBitmap(jpg, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                                    doc.finishPage(p)
                                    bmp.recycle(); jpg.recycle()
                                }
                            }
                        }
                    }
                    doc.writeTo(t.stream)
                } catch (e: Throwable) {
                    t.abort(); throw e
                } finally {
                    doc.close(); tmp.parentFile?.deleteRecursively()
                }
                t.finish()
            }
            finished(out)
        },
        Op("number", "Numéroter les pages", listOf(Param.Choice("pos", "Position", listOf("c" to "En bas au centre", "r" to "En bas à droite"), "c"),
            Param.Choice("fmt", "Style", listOf("n" to "1, 2, 3", "nt" to "1 / 10", "p" to "Page 1"), "n"))) { ctx, files, v ->
            perPdf(ctx, files) { uri, doc ->
                val n = doc.numberOfPages
                for (i in 0 until n) {
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    val label = when (v["fmt"]) { "nt" -> "${i + 1} / $n"; "p" -> "Page ${i + 1}"; else -> "${i + 1}" }
                    val size = 10f
                    val w = PDType1Font.HELVETICA.getStringWidth(label) / 1000 * size
                    val x = if (v["pos"] == "r") box.width - w - 36 else (box.width - w) / 2
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, size)
                        cs.newLineAtOffset(box.lowerLeftX + x, box.lowerLeftY + 20)
                        cs.showText(label)
                        cs.endText()
                    }
                }
                save(ctx, doc, base(ctx, uri) + "_numerote.pdf")
            }
        },
        Op("watermark", "Filigrane texte", listOf(Param.Field("t", "Texte", "CONFIDENTIEL"))) { ctx, files, v ->
            val text = v["t"]!!.ifBlank { "COPIE" }
            perPdf(ctx, files) { uri, doc ->
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    val size = min(box.width, box.height) / 9
                    val w = PDType1Font.HELVETICA_BOLD.getStringWidth(text) / 1000 * size
                    val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.18f }
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.setNonStrokingColor(0.5f, 0.5f, 0.5f)
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, size)
                        val angle = Math.atan2(box.height.toDouble(), box.width.toDouble())
                        val cx = box.width / 2 - (w / 2) * Math.cos(angle).toFloat()
                        val cy = box.height / 2 - (w / 2) * Math.sin(angle).toFloat()
                        cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getRotateInstance(angle, cx, cy))
                        cs.showText(text)
                        cs.endText()
                    }
                }
                save(ctx, doc, base(ctx, uri) + "_filigrane.pdf")
            }
        },
        Op("reverse", "Inverser l'ordre des pages") { ctx, files, _ ->
            perPdf(ctx, files) { uri, doc ->
                PDDocument().use { nd ->
                    for (i in doc.numberOfPages - 1 downTo 0) nd.importPage(doc.getPage(i))
                    save(ctx, nd, base(ctx, uri) + "_inverse.pdf")
                }
            }
        },
        Op("reorder", "Réorganiser les pages", listOf(Param.Field("o", "Nouvel ordre (ex. 3,1,2,4-6)", "2,1")),
            note = "Une page peut être répétée. Les pages non citées sont retirées.") { ctx, files, v ->
            perPdf(ctx, files) { uri, doc ->
                val order = ranges(v["o"]!!, doc.numberOfPages)
                if (order.isEmpty()) error("Ordre invalide (le PDF a ${doc.numberOfPages} pages)")
                PDDocument().use { nd ->
                    order.forEach { nd.importPage(doc.getPage(it - 1)) }
                    save(ctx, nd, base(ctx, uri) + "_reorganise.pdf")
                }
            }
        },
        Op("oddeven", "Pages paires / impaires", listOf(Param.Choice("k", "Garder", listOf("odd" to "Impaires (1, 3, 5…)", "even" to "Paires (2, 4, 6…)"), "odd"))) { ctx, files, v ->
            perPdf(ctx, files) { uri, doc ->
                PDDocument().use { nd ->
                    for (i in 0 until doc.numberOfPages) if ((i % 2 == 0) == (v["k"] == "odd")) nd.importPage(doc.getPage(i))
                    save(ctx, nd, base(ctx, uri) + "_" + (if (v["k"] == "odd") "impaires" else "paires") + ".pdf")
                }
            }
        },
        Op("interleave", "Assembler recto / verso", minFiles = 2,
            note = "1er PDF = rectos, 2e PDF = versos scannés à l'envers : les pages sont remises dans l'ordre.") { ctx, files, _ ->
            PDFBoxResourceLoader.init(ctx)
            val a = open(ctx, files[0]).use { PDDocument.load(it) }
            val b = open(ctx, files[1]).use { PDDocument.load(it) }
            try {
                PDDocument().use { nd ->
                    val n = max(a.numberOfPages, b.numberOfPages)
                    for (i in 0 until n) {
                        if (i < a.numberOfPages) nd.importPage(a.getPage(i))
                        val j = b.numberOfPages - 1 - i
                        if (j >= 0) nd.importPage(b.getPage(j))
                    }
                    finished(listOf(save(ctx, nd, "RectoVerso_${stamp()}.pdf")))
                }
            } finally { a.close(); b.close() }
        },
        Op("blank", "Ajouter une page blanche", listOf(Param.Choice("w", "Où", listOf("end" to "À la fin", "start" to "Au début", "each" to "Après chaque page"), "end"))) { ctx, files, v ->
            perPdf(ctx, files) { uri, doc ->
                val box = doc.getPage(0).mediaBox
                when (v["w"]) {
                    "start" -> doc.pages.insertBefore(PDPage(box), doc.getPage(0))
                    "each" -> { val pages = (0 until doc.numberOfPages).map { doc.getPage(it) }; pages.forEach { doc.pages.insertAfter(PDPage(box), it) } }
                    else -> doc.addPage(PDPage(box))
                }
                save(ctx, doc, base(ctx, uri) + "_blanche.pdf")
            }
        },
        Op("signature", "Signer (ajouter une image)", listOf(
            Param.Field("p", "Page (0 = dernière)", "0", number = true),
            Param.Choice("pos", "Position", listOf("br" to "Bas droite", "bl" to "Bas gauche", "tr" to "Haut droite"), "br"),
        ), minFiles = 2, note = "1er fichier = PDF, 2e = image de ta signature (PNG sur fond transparent idéalement).") { ctx, files, v ->
            PDFBoxResourceLoader.init(ctx)
            val sig = Docs.decodeBitmap(ctx, files[1], 1200)
            open(ctx, files[0]).use { input ->
                PDDocument.load(input).use { doc ->
                    val n = doc.numberOfPages
                    val p = (v["p"]!!.toDoubleOrNull()?.toInt() ?: 0).let { if (it in 1..n) it - 1 else n - 1 }
                    val page = doc.getPage(p)
                    val box = page.mediaBox
                    val img = LosslessFactory.createFromImage(doc, sig)
                    val w = box.width * 0.28f
                    val h = w * sig.height / sig.width
                    val x = if (v["pos"] == "bl") 40f else box.width - w - 40
                    val y = if (v["pos"] == "tr") box.height - h - 40 else 40f
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { it.drawImage(img, box.lowerLeftX + x, box.lowerLeftY + y, w, h) }
                    finished(listOf(save(ctx, doc, base(ctx, files[0]) + "_signe.pdf")))
                }
            }.also { sig.recycle() }
        },
        Op("images", "Pages → images (ZIP)", listOf(Param.Choice("f", "Format", listOf("jpg" to "JPG", "png" to "PNG"), "jpg"))) { ctx, files, v ->
            val out = files.map { uri ->
                val t = Storage.openOutput(ctx, base(ctx, uri) + "_images.zip")
                try { Docs.pdfToImages(ctx, uri, v["f"]!!, t.stream) { a, b -> update(a.toFloat() / b, "Page $a / $b") } } catch (e: Throwable) { t.abort(); throw e }
                t.finish()
            }
            finished(out)
        },
        Op("text", "Extraire le texte") { ctx, files, _ ->
            val parts = files.map { uri -> base(ctx, uri) to Docs.pdfToText(ctx, uri) }
            val out = parts.map { (n, txt) ->
                val t = Storage.openOutput(ctx, "$n.txt")
                t.stream.write(txt.toByteArray())
                t.finish()
            }
            finished(out, parts.joinToString("\n\n") { it.second }.take(20000))
        },
        Op("info", "Infos du PDF") { ctx, files, _ ->
            PDFBoxResourceLoader.init(ctx)
            val text = files.joinToString("\n\n") { uri ->
                open(ctx, uri).use { input ->
                    PDDocument.load(input).use { doc ->
                        val i = doc.documentInformation
                        val box = doc.getPage(0).mediaBox
                        buildString {
                            appendLine(Storage.displayName(ctx, uri))
                            appendLine("Pages : ${doc.numberOfPages}")
                            appendLine("Taille : ${Storage.humanSize(Storage.size(ctx, uri))}")
                            appendLine("Format page 1 : ${(box.width / 72 * 25.4).roundToInt()} × ${(box.height / 72 * 25.4).roundToInt()} mm")
                            i.title?.let { appendLine("Titre : $it") }
                            i.author?.let { appendLine("Auteur : $it") }
                            i.creator?.let { appendLine("Créé avec : $it") }
                            i.producer?.let { appendLine("Producteur : $it") }
                            i.creationDate?.let { appendLine("Créé le : ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE).format(it.time)}") }
                            appendLine("Version PDF : ${doc.version}")
                        }
                    }
                }
            }
            JobState.Done("Infos de ${files.size} PDF", text = text)
        },
        Op("meta", "Modifier titre et auteur", listOf(Param.Field("title", "Titre", ""), Param.Field("author", "Auteur", ""))) { ctx, files, v ->
            perPdf(ctx, files) { uri, doc ->
                doc.documentInformation.apply {
                    if (v["title"]!!.isNotBlank()) title = v["title"]
                    if (v["author"]!!.isNotBlank()) author = v["author"]
                    producer = "Fluxo"
                }
                save(ctx, doc, base(ctx, uri) + "_modifie.pdf")
            }
        },
    )
}

// ============================================================== Infos supplémentaires

object MoreInfo {
    val ops = listOf(
        Op("compare", "Comparer deux fichiers", minFiles = 2, note = "Vérifie si deux fichiers sont exactement identiques (SHA-256).") { ctx, files, _ ->
            val hs = files.map { uri ->
                val md = java.security.MessageDigest.getInstance("SHA-256")
                open(ctx, uri).use { input ->
                    val buf = ByteArray(1 shl 20)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                }
                Storage.displayName(ctx, uri) to md.digest().joinToString("") { "%02x".format(it) }
            }
            val same = hs.map { it.second }.distinct().size == 1
            JobState.Done(if (same) "Les fichiers sont identiques" else "Les fichiers sont différents",
                text = hs.joinToString("\n") { "${it.first}\n  ${it.second}" })
        },
        Op("words", "Compter mots et caractères", note = "TXT, PDF, Word (DOCX), HTML, Markdown…") { ctx, files, _ ->
            val text = files.joinToString("\n\n") { uri ->
                val name = Storage.displayName(ctx, uri)
                val content = when (Storage.extension(name)) {
                    "pdf" -> Docs.pdfToText(ctx, uri)
                    "docx" -> Docs.docxToText(ctx, uri)
                    else -> Docs.readText(ctx, uri, name)
                }
                val words = content.split(Regex("\\s+")).count { it.isNotBlank() }
                "$name\nMots : $words\nCaractères : ${content.length}\nSans espaces : ${content.count { !it.isWhitespace() }}\n" +
                    "Lignes : ${content.lines().size}\nPhrases : ${content.split(Regex("[.!?]+\\s")).count { it.isNotBlank() }}\nLecture : ≈ ${(words / 200.0).roundToInt().coerceAtLeast(1)} min"
            }
            JobState.Done("Comptage terminé", text = text)
        },
        Op("zip", "Voir le contenu d'un ZIP") { ctx, files, _ ->
            val text = files.joinToString("\n\n") { uri ->
                var count = 0; var total = 0L
                val list = StringBuilder()
                open(ctx, uri).use { ZipInputStream(it).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        count++
                        val size = if (e.size >= 0) e.size else z.readBytes().size.toLong()
                        total += size
                        if (count <= 300) list.append(e.name).append("  (").append(Storage.humanSize(size)).append(")\n")
                    }
                } }
                "${Storage.displayName(ctx, uri)} : $count éléments, ${Storage.humanSize(total)} une fois décompressé\n$list"
            }
            JobState.Done("Contenu de l'archive", text = text)
        },
        Op("duration", "Durée totale (audio / vidéo)") { ctx, files, _ ->
            val ds = files.map { Storage.displayName(ctx, it) to FF.durationMs(ctx, it) }
            fun hms(ms: Long) = (ms / 1000).let { "%d:%02d:%02d".format(it / 3600, it % 3600 / 60, it % 60) }
            JobState.Done("Durée totale : ${hms(ds.sumOf { it.second })}", text = ds.joinToString("\n") { "${hms(it.second)}  ${it.first}" })
        },
        Op("exif", "Toutes les infos photo (EXIF)") { ctx, files, _ ->
            val tags = listOf(
                ExifInterface.TAG_MAKE to "Marque", ExifInterface.TAG_MODEL to "Modèle", ExifInterface.TAG_DATETIME_ORIGINAL to "Prise le",
                ExifInterface.TAG_EXPOSURE_TIME to "Exposition (s)", ExifInterface.TAG_F_NUMBER to "Ouverture f/", ExifInterface.TAG_ISO_SPEED_RATINGS to "ISO",
                ExifInterface.TAG_FOCAL_LENGTH to "Focale", ExifInterface.TAG_FLASH to "Flash", ExifInterface.TAG_WHITE_BALANCE to "Balance des blancs",
                ExifInterface.TAG_IMAGE_WIDTH to "Largeur", ExifInterface.TAG_IMAGE_LENGTH to "Hauteur", ExifInterface.TAG_ORIENTATION to "Orientation",
                ExifInterface.TAG_SOFTWARE to "Logiciel", ExifInterface.TAG_ARTIST to "Auteur", ExifInterface.TAG_GPS_ALTITUDE to "Altitude",
            )
            val text = files.joinToString("\n\n") { uri ->
                runCatching {
                    open(ctx, uri).use { input ->
                        val e = ExifInterface(input)
                        val ll = FloatArray(2)
                        @Suppress("DEPRECATION")
                        val gps = if (e.getLatLong(ll)) "\nGPS : ${ll[0]}, ${ll[1]}\nCarte : https://maps.google.com/?q=${ll[0]},${ll[1]}" else "\nGPS : aucun"
                        Storage.displayName(ctx, uri) + "\n" + tags.mapNotNull { (t, l) -> e.getAttribute(t)?.let { "$l : $it" } }.joinToString("\n") + gps
                    }
                }.getOrElse { Storage.displayName(ctx, uri) + " : pas d'infos EXIF" }
            }
            JobState.Done("Infos photo", text = text)
        },
    )

    val ocrOps = listOf(
        Op("ocr_extract", "Trouver liens, e-mails et numéros", note = "Lit la photo puis ne garde que les liens, adresses e-mail et numéros de téléphone.") { ctx, files, _ ->
            val all = StringBuilder()
            files.forEachIndexed { i, uri ->
                update(i.toFloat() / files.size, "Lecture de l'image ${i + 1} / ${files.size}…")
                all.append(InfoTools.ocr(ctx, uri)).append('\n')
            }
            val s = all.toString()
            val links = Regex("(https?://|www\\.)\\S+").findAll(s).map { it.value }.distinct().toList()
            val mails = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").findAll(s).map { it.value }.distinct().toList()
            val phones = Regex("(\\+?\\d[\\d .-]{7,}\\d)").findAll(s).map { it.value.trim() }.distinct().toList()
            val text = buildString {
                if (links.isNotEmpty()) appendLine("Liens :\n" + links.joinToString("\n") + "\n")
                if (mails.isNotEmpty()) appendLine("E-mails :\n" + mails.joinToString("\n") + "\n")
                if (phones.isNotEmpty()) appendLine("Téléphones :\n" + phones.joinToString("\n"))
            }.trim()
            JobState.Done(if (text.isEmpty()) "Rien trouvé" else "Éléments trouvés", text = text.ifEmpty { null })
        },
    )
}
