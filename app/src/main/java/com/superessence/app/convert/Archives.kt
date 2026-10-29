package com.superessence.app.convert

import android.content.Context
import android.net.Uri
import com.github.junrar.Junrar
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object Archives {

    /** Compresse plusieurs fichiers dans un seul ZIP enregistré dans Téléchargements. */
    fun zip(ctx: Context, uris: List<Uri>, zipName: String, onFile: (Int, Long) -> Unit): SavedFile {
        val target = Storage.openOutput(ctx, zipName)
        try {
            val used = HashSet<String>()
            var total = 0L
            ZipOutputStream(target.stream.buffered(1 shl 20)).use { zip ->
                zip.setLevel(Deflater.BEST_COMPRESSION)
                uris.forEachIndexed { i, uri ->
                    var name = Storage.sanitize(Storage.displayName(ctx, uri))
                    var n = 1
                    while (!used.add(name)) {
                        name = "${Storage.baseName(name)} ($n)." + Storage.extension(name)
                        n++
                    }
                    zip.putNextEntry(ZipEntry(name))
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        Storage.copyStream(input, zip) { b -> onFile(i, total + b) }
                    }
                    zip.closeEntry()
                    total += Storage.size(ctx, uri).coerceAtLeast(0)
                }
            }
        } catch (e: Throwable) {
            target.abort()
            throw e
        }
        return target.finish()
    }

    /** Extrait une archive (zip, xapk, 7z, rar, tar, gz…) dans Téléchargements/Fluxo/<nom>. */
    fun extract(ctx: Context, uri: Uri, onFile: (String) -> Unit): List<SavedFile> {
        val name = Storage.displayName(ctx, uri)
        val folder = Storage.baseName(name).removeSuffix(".tar")
        val ext = Storage.extension(name)
        val out = ArrayList<SavedFile>()

        fun saveStream(entryName: String, input: InputStream) {
            val clean = entryName.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }
            if (clean.isEmpty()) return
            val sub = (listOf(folder) + clean.dropLast(1)).joinToString("/")
            val target = Storage.openOutput(ctx, clean.last(), sub)
            try {
                target.written = Storage.copyStream(input, target.stream)
            } catch (e: Throwable) {
                target.abort()
                throw e
            }
            out += target.finish()
            onFile(clean.joinToString("/"))
        }

        fun fromArchiveStream(ais: ArchiveInputStream<*>) {
            while (true) {
                val e = ais.nextEntry ?: break
                if (e.isDirectory || !ais.canReadEntryData(e)) continue
                saveStream(e.name, ais)
            }
        }

        when (ext) {
            "zip", "xapk", "apks", "apkm", "jar", "docx", "xlsx", "pptx", "odt", "epub" -> {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Archive illisible")
                ZipInputStream(input.buffered(1 shl 20)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory) saveStream(e.name, zip)
                    }
                }
            }
            "7z" -> {
                val tmp = Storage.copyToFile(ctx, uri, File(Storage.workDir(ctx, "7z"), "in.7z"))
                SevenZFile.builder().setFile(tmp).get().use { sz ->
                    while (true) {
                        val e = sz.nextEntry ?: break
                        if (e.isDirectory || !e.hasStream()) continue
                        saveStream(e.name, sz.getInputStream(e))
                    }
                }
                tmp.parentFile?.deleteRecursively()
            }
            "rar" -> {
                val dir = Storage.workDir(ctx, "rar")
                val tmp = Storage.copyToFile(ctx, uri, File(dir, "in.rar"))
                val dest = File(dir, "out").apply { mkdirs() }
                Junrar.extract(tmp, dest)
                dest.walkTopDown().filter { it.isFile }.forEach { f ->
                    f.inputStream().use { saveStream(f.relativeTo(dest).path, it) }
                }
                dir.deleteRecursively()
            }
            "tar" -> {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Archive illisible")
                TarArchiveInputStream(input.buffered()).use { fromArchiveStream(it) }
            }
            "tgz", "gz", "xz", "txz", "bz2" -> {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Archive illisible")
                val decompressed = CompressorStreamFactory().createCompressorInputStream(input.buffered())
                val isTar = name.lowercase().let { it.endsWith(".tar.gz") || it.endsWith(".tgz") || it.endsWith(".tar.xz") || it.endsWith(".txz") || it.endsWith(".tar.bz2") }
                if (isTar) {
                    TarArchiveInputStream(decompressed).use { fromArchiveStream(it) }
                } else {
                    decompressed.use { saveStream(Storage.baseName(name), it) }
                }
            }
            else -> error("Format d'archive non pris en charge : .$ext")
        }
        if (out.isEmpty()) error("L'archive est vide")
        return out
    }
}
