package com.superessence.app.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Un fichier enregistré dans Téléchargements/Fluxo. */
data class SavedFile(val name: String, val uri: Uri, val size: Long, val mime: String)

/** Sortie en cours d'écriture : appeler [finish] une fois le contenu écrit. */
class OutputTarget(
    val name: String,
    val uri: Uri,
    raw: OutputStream,
    private val mime: String,
    private val onFinish: (Long) -> Unit,
    private val onAbort: () -> Unit,
) {
    private val counting = CountingStream(raw)
    val stream: OutputStream get() = counting
    var written = 0L
    fun finish(): SavedFile {
        counting.close()
        val size = maxOf(written, counting.count)
        onFinish(size)
        return SavedFile(name, uri, size, mime)
    }

    fun abort() {
        runCatching { stream.close() }
        runCatching { onAbort() }
    }
}

class CountingStream(private val out: OutputStream) : OutputStream() {
    var count = 0L
        private set
    private var closed = false
    override fun write(b: Int) {
        out.write(b); count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len); count += len
    }

    override fun flush() = out.flush()
    override fun close() {
        if (!closed) {
            closed = true
            out.close()
        }
    }
}

object Storage {
    const val FOLDER = "Fluxo"

    fun workDir(ctx: Context, prefix: String): File =
        File(ctx.cacheDir, "work/$prefix-${System.nanoTime()}").apply { mkdirs() }

    fun clearWork(ctx: Context) {
        File(ctx.cacheDir, "work").deleteRecursively()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return File(uri.path!!).name
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "fichier"
    }

    fun size(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path!!).length()
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        }
        runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { return it.length }
        }
        return -1
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = name.substringBeforeLast('.').ifBlank { name }

    fun mimeOf(name: String): String = when (val ext = extension(name)) {
        "apk" -> "application/vnd.android.package-archive"
        "xapk", "apks", "apkm" -> "application/zip"
        "srt" -> "application/x-subrip"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun mimeOf(ctx: Context, uri: Uri, name: String): String =
        ctx.contentResolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: mimeOf(name)

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(150).ifBlank { "fichier" }

    /** Crée un fichier dans Téléchargements/Fluxo[/sousDossier]. */
    fun openOutput(ctx: Context, rawName: String, subFolder: String? = null): OutputTarget {
        val name = sanitize(rawName)
        val mime = mimeOf(name)
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + (subFolder?.split('/')?.filter { it.isNotBlank() }?.joinToString("") { "/" + sanitize(it) } ?: "")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Impossible de créer $name dans Téléchargements")
            val out = resolver.openOutputStream(uri) ?: error("Impossible d'écrire $name")
            OutputTarget(name, uri, out, mime, onFinish = {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }, onAbort = { resolver.delete(uri, null, null) })
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
            var file = File(dir, name)
            var i = 1
            while (file.exists()) {
                file = File(dir, "${baseName(name)} ($i).${extension(name)}".removeSuffix("."))
                i++
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
            OutputTarget(file.name, uri, FileOutputStream(file), mime, onFinish = {
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
            }, onAbort = { file.delete() })
        }
    }

    fun copyStream(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}): Long {
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            onBytes(total)
        }
        return total
    }

    /** Copie un fichier local vers Téléchargements/Fluxo. */
    fun save(ctx: Context, file: File, name: String = file.name, subFolder: String? = null): SavedFile {
        val target = openOutput(ctx, name, subFolder)
        try {
            file.inputStream().use { target.written = copyStream(it, target.stream) }
        } catch (e: Throwable) {
            target.abort()
            throw e
        }
        return target.finish()
    }

    fun copyToFile(ctx: Context, uri: Uri, dest: File, onBytes: (Long) -> Unit = {}): File {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("Fichier illisible")
        input.use { i -> FileOutputStream(dest).use { o -> copyStream(i, o, onBytes) } }
        return dest
    }

    fun uriForCache(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    fun openFile(ctx: Context, f: SavedFile) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(Intent.createChooser(i, "Ouvrir avec").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun shareFiles(ctx: Context, files: List<SavedFile>) {
        if (files.isEmpty()) return
        val i = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files[0].mime).putExtra(Intent.EXTRA_STREAM, files[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var v = bytes / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) {
            v /= 1024; u++
        }
        return String.format(java.util.Locale.FRANCE, "%.1f %s", v, units[u])
    }
}
