package com.superessence.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/** Conversions images / documents (sans FFmpeg). */
object Docs {
    private const val A4_W = 595
    private const val A4_H = 842

    fun decodeBitmap(ctx: Context, uri: Uri, maxDim: Int = 0): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                if (maxDim > 0 && max(w, h) > maxDim) {
                    val s = maxDim.toFloat() / max(w, h)
                    dec.setTargetSize(max(1, (w * s).toInt()), max(1, (h * s).toInt()))
                }
            }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        if (maxDim > 0) {
            while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
        }
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, real) }
            ?: error("Image illisible")
    }

    fun writeBitmap(bmp: Bitmap, format: String, quality: Int, out: OutputStream) {
        val ok = when (format) {
            "png" -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            } else {
                @Suppress("DEPRECATION")
                bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
            }
            else -> {
                val flat = if (bmp.hasAlpha()) {
                    val b = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                    b
                } else {
                    bmp
                }
                flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
        }
        if (!ok) error("Encodage de l'image impossible")
    }

    fun imagesToPdf(ctx: Context, uris: List<Uri>, out: OutputStream, onPage: (Int) -> Unit) {
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            uris.forEachIndexed { i, uri ->
                val bmp = decodeBitmap(ctx, uri, 2400)
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) A4_H else A4_W
                val ph = if (landscape) A4_W else A4_H
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val margin = 18f
                val s = min((pw - 2 * margin) / bmp.width, (ph - 2 * margin) / bmp.height)
                val w = bmp.width * s
                val h = bmp.height * s
                val left = (pw - w) / 2
                val top = (ph - h) / 2
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), paint)
                doc.finishPage(page)
                bmp.recycle()
                onPage(i + 1)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun textToPdf(text: String, out: OutputStream) {
        val doc = PdfDocument()
        val margin = 48
        val width = A4_W - 2 * margin
        val usable = A4_H - 2 * margin
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.BLACK
        }
        val content = text.ifEmpty { " " }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        try {
            var line = 0
            var pageNo = 1
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - top <= usable) last++
                if (last == line) last = line + 1
                val bottom = layout.getLineBottom(last - 1)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                c.save()
                c.translate(margin.toFloat(), (margin - top).toFloat())
                c.clipRect(0, top, width, bottom)
                layout.draw(c)
                c.restore()
                doc.finishPage(page)
                line = last
                pageNo++
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    fun readText(ctx: Context, uri: Uri, name: String): String {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fichier illisible")
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext == "html" || ext == "htm") {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            raw
        }
    }

    fun docxToText(ctx: Context, uri: Uri): String {
        val sb = StringBuilder()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name != "word/document.xml") continue
                    val p = Xml.newPullParser()
                    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    p.setInput(zip, "UTF-8")
                    var inText = false
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        when (p.eventType) {
                            XmlPullParser.START_TAG -> when (p.name) {
                                "w:t" -> inText = true
                                "w:tab" -> sb.append('\t')
                                "w:br", "w:cr" -> sb.append('\n')
                            }
                            XmlPullParser.END_TAG -> when (p.name) {
                                "w:t" -> inText = false
                                "w:p" -> sb.append('\n')
                            }
                            XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                        }
                    }
                    break
                }
            }
        } ?: error("Fichier illisible")
        return sb.toString()
    }

    fun pdfToText(ctx: Context, uri: Uri): String {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val input = ctx.contentResolver.openInputStream(uri) ?: error("PDF illisible")
        return input.use { PDDocument.load(it).use { doc -> PDFTextStripper().getText(doc) } }
    }

    /** Rend chaque page du PDF en image dans un ZIP. */
    fun pdfToImages(ctx: Context, uri: Uri, format: String, out: OutputStream, onPage: (Int, Int) -> Unit) {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("PDF illisible")
        PdfRenderer(pfd).use { renderer ->
            ZipOutputStream(out.buffered()).use { zip ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val scale = min(2f, 2480f / max(page.width, page.height))
                        val bmp = Bitmap.createBitmap(
                            max(1, (page.width * scale).toInt()),
                            max(1, (page.height * scale).toInt()),
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        zip.putNextEntry(ZipEntry("page_%03d.%s".format(i + 1, format)))
                        writeBitmap(bmp, format, 92, zip)
                        zip.closeEntry()
                        bmp.recycle()
                    }
                    onPage(i + 1, renderer.pageCount)
                }
            }
        }
    }
}
