package com.superessence.app.convert

import android.content.Context
import android.net.Uri
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

enum class Level(val label: String) { LIGHT("Légère"), MEDIUM("Moyenne"), STRONG("Forte") }

object Compressor {

    suspend fun run(ctx: Context, uris: List<Uri>, level: Level, zipAll: Boolean, p: Progress): JobState.Done {
        val before = uris.sumOf { Storage.size(ctx, it).coerceAtLeast(0) }
        val saved = ArrayList<SavedFile>()
        if (zipAll) {
            val names = uris.map { Storage.displayName(ctx, it) }
            val zipName = if (uris.size == 1) Storage.baseName(names[0]) + ".zip" else "Archive_${Converter.stamp()}.zip"
            saved += Archives.zip(ctx, uris, zipName) { i, b ->
                p.update(b.toFloat() / before.coerceAtLeast(1), "Compression de ${names[i]}…")
            }
        } else {
            uris.forEachIndexed { i, uri ->
                coroutineContext.ensureActive()
                val name = Storage.displayName(ctx, uri)
                val base = Storage.baseName(name)
                val kind = Formats.kind(name, Storage.mimeOf(ctx, uri, name))
                val prefix = if (uris.size > 1) "(${i + 1}/${uris.size}) " else ""
                fun step(f: Float, msg: String) = p.update((i + f.coerceIn(0f, 1f)) / uris.size, prefix + msg)
                step(0f, "Compression de $name…")
                when (kind) {
                    Kind.IMAGE -> {
                        val (maxDim, q) = when (level) {
                            Level.LIGHT -> 4000 to 85
                            Level.MEDIUM -> 2560 to 72
                            Level.STRONG -> 1600 to 55
                        }
                        val bmp = Docs.decodeBitmap(ctx, uri, maxDim)
                        val out = Storage.openOutput(ctx, "${base}_compressé.jpg")
                        try {
                            Docs.writeBitmap(bmp, "jpg", q, out.stream)
                        } catch (e: Throwable) {
                            out.abort(); throw e
                        }
                        bmp.recycle()
                        saved += out.finish()
                    }
                    Kind.VIDEO -> {
                        val (maxW, rate, arate) = when (level) {
                            Level.LIGHT -> Triple(1920, "3000k", "160k")
                            Level.MEDIUM -> Triple(1280, "1500k", "128k")
                            Level.STRONG -> Triple(854, "700k", "96k")
                        }
                        val tmp = File(Storage.workDir(ctx, "cmp"), "out.mp4")
                        FF.convert(
                            ctx, uri, tmp,
                            listOf(
                                "-vf", "scale=trunc(min($maxW\\,iw)/2)*2:-2",
                                "-c:v", "libopenh264", "-b:v", rate, "-pix_fmt", "yuv420p",
                                "-c:a", "aac", "-b:a", arate, "-movflags", "+faststart",
                            ),
                            { step(it, "Compression vidéo…") },
                        )
                        saved += Storage.save(ctx, tmp, "${base}_compressé.mp4")
                        tmp.parentFile?.deleteRecursively()
                    }
                    Kind.AUDIO -> {
                        val rate = when (level) {
                            Level.LIGHT -> "192k"
                            Level.MEDIUM -> "128k"
                            Level.STRONG -> "80k"
                        }
                        val tmp = File(Storage.workDir(ctx, "cmp"), "out.mp3")
                        FF.convert(ctx, uri, tmp, listOf("-vn", "-c:a", "libmp3lame", "-b:a", rate), { step(it, "Compression audio…") })
                        saved += Storage.save(ctx, tmp, "${base}_compressé.mp3")
                        tmp.parentFile?.deleteRecursively()
                    }
                    else -> saved += Archives.zip(ctx, listOf(uri), "$base.zip") { _, b ->
                        step(b.toFloat() / Storage.size(ctx, uri).coerceAtLeast(1), "Compression ZIP…")
                    }
                }
            }
        }
        val after = saved.sumOf { it.size }
        val gain = if (before > 0) ((1 - after.toDouble() / before) * 100).toInt() else 0
        return JobState.Done(
            "Terminé : ${Storage.humanSize(before)} → ${Storage.humanSize(after)}" +
                (if (gain > 0) " (−$gain %)" else ""),
            saved,
        )
    }
}
