package com.superessence.app.tools

import android.content.Context
import android.net.Uri
import com.superessence.app.convert.Docs
import com.superessence.app.convert.FF
import com.superessence.app.convert.Formats
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import com.superessence.app.ui.Op
import com.superessence.app.ui.Param
import java.io.File

/** Outils vidéo et audio (FFmpeg). */
object MediaTools {

    private val MP3 = listOf("-vn", "-c:a", "libmp3lame", "-q:a", "2")

    private fun done(files: List<SavedFile>) =
        JobState.Done("Terminé ! ${files.size} fichier(s) dans Téléchargements/${Storage.FOLDER}", files)

    /** Convertit « 1:30 », « 90 » ou « 0:01:30 » en secondes (texte pour FFmpeg). */
    fun time(v: String): String {
        val parts = v.trim().replace(',', '.').split(':').map { it.toDoubleOrNull() ?: 0.0 }
        val sec = parts.fold(0.0) { acc, x -> acc * 60 + x }
        return String.format(java.util.Locale.US, "%.3f", sec)
    }

    private fun num(v: String, def: Double) = v.replace(',', '.').toDoubleOrNull() ?: def

    /** Applique une commande à chaque fichier et enregistre le résultat. */
    private suspend fun Progress.each(
        ctx: Context,
        files: List<Uri>,
        suffix: String,
        ext: (String) -> String,
        args: (Uri) -> List<String>,
        preInput: List<String> = emptyList(),
    ): JobState.Done {
        val out = ArrayList<SavedFile>()
        files.forEachIndexed { i, uri ->
            val name = Storage.displayName(ctx, uri)
            val e = ext(Storage.extension(name).ifBlank { "mp4" })
            val tmp = File(Storage.workDir(ctx, "media"), "out.$e")
            val prefix = if (files.size > 1) "(${i + 1}/${files.size}) " else ""
            FF.convert(ctx, uri, tmp, args(uri), { update((i + it) / files.size, "$prefix$name…") }, preInput)
            out += Storage.save(ctx, tmp, "${Storage.baseName(name)}_$suffix.$e")
            tmp.parentFile?.deleteRecursively()
        }
        return done(out)
    }

    private suspend fun Progress.single(ctx: Context, args: List<String>, durMs: Long, outName: String): JobState.Done {
        val tmp = File(Storage.workDir(ctx, "media"), "out." + Storage.extension(outName))
        FF.run(args + tmp.absolutePath, durMs) { update(it, "Traitement…") }
        val saved = Storage.save(ctx, tmp, outName)
        tmp.parentFile?.deleteRecursively()
        return done(listOf(saved))
    }

    private val sameVideo: (String) -> String = { if (it in setOf("mp4", "mkv", "mov", "webm", "avi", "3gp")) it else "mp4" }
    private val mp4: (String) -> String = { "mp4" }
    private val mp3: (String) -> String = { "mp3" }
    private val speeds = listOf("0.5" to "×0,5", "0.75" to "×0,75", "1.25" to "×1,25", "1.5" to "×1,5", "2" to "×2")

    val videoOps = listOf(
        Op(
            "trim", "Couper",
            listOf(Param.Field("start", "Début (ex. 0:10)", "0:00"), Param.Field("end", "Fin (ex. 1:30)", "0:30")),
            note = "Coupe sans réencoder : très rapide.",
        ) { ctx, files, v ->
            each(ctx, files, "coupe", sameVideo, { listOf("-ss", time(v["start"]!!), "-to", time(v["end"]!!), "-c", "copy", "-avoid_negative_ts", "make_zero") })
        },
        Op("mute", "Retirer le son") { ctx, files, _ ->
            each(ctx, files, "muet", sameVideo, { listOf("-an", "-c:v", "copy") })
        },
        Op("speed", "Vitesse", listOf(Param.Choice("f", "Vitesse", speeds, "2"))) { ctx, files, v ->
            val f = num(v["f"]!!, 2.0)
            each(ctx, files, "x$f", mp4, { listOf("-vf", "setpts=PTS/$f", "-af", "atempo=$f") + Formats.H264 })
        },
        Op(
            "rotate", "Pivoter",
            listOf(Param.Choice("r", "Rotation", listOf("1" to "90° →", "2" to "90° ←", "3" to "180°", "h" to "Miroir"), "1")),
        ) { ctx, files, v ->
            val vf = when (v["r"]) {
                "2" -> "transpose=2"
                "3" -> "transpose=1,transpose=1"
                "h" -> "hflip"
                else -> "transpose=1"
            }
            each(ctx, files, "pivot", mp4, { listOf("-vf", vf) + Formats.H264 })
        },
        Op(
            "resize", "Redimensionner",
            listOf(Param.Choice("h", "Hauteur", listOf("360" to "360p", "480" to "480p", "720" to "720p", "1080" to "1080p"), "720")),
        ) { ctx, files, v ->
            each(ctx, files, "${v["h"]}p", mp4, { listOf("-vf", "scale=-2:${v["h"]}") + Formats.H264 })
        },
        Op("reverse", "Lecture à l'envers", note = "Réservé aux vidéos courtes (moins d'une minute).") { ctx, files, _ ->
            each(ctx, files, "envers", mp4, { listOf("-vf", "reverse", "-af", "areverse") + Formats.H264 })
        },
        Op("frame", "Capture d'image", listOf(Param.Field("t", "Moment (ex. 0:05)", "0:01"))) { ctx, files, v ->
            each(ctx, files, "capture", { "jpg" }, { listOf("-frames:v", "1", "-q:v", "2") }, listOf("-ss", time(v["t"]!!)))
        },
        Op("replace_audio", "Changer la musique", minFiles = 2, note = "1er fichier = vidéo, 2e fichier = musique.") { ctx, files, _ ->
            val name = Storage.baseName(Storage.displayName(ctx, files[0]))
            single(
                ctx,
                listOf(
                    "-i", FF.input(ctx, files[0]), "-i", FF.input(ctx, files[1]),
                    "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                ),
                FF.durationMs(ctx, files[0]), "${name}_musique.mp4",
            )
        },
        Op("merge", "Fusionner", minFiles = 2, note = "Les vidéos sont mises bout à bout (dans l'ordre de la liste), en 720p.") { ctx, files, _ ->
            val inputs = files.flatMap { listOf("-i", FF.input(ctx, it)) }
            val chains = files.indices.joinToString(";") { i ->
                "[$i:v]scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30[v$i]"
            }
            val concat = files.indices.joinToString("") { "[v$it][$it:a]" } + "concat=n=${files.size}:v=1:a=1[v][a]"
            val dur = files.sumOf { FF.durationMs(ctx, it) }
            single(ctx, inputs + listOf("-filter_complex", "$chains;$concat", "-map", "[v]", "-map", "[a]") + Formats.H264, dur, "Fusion_${stamp()}.mp4")
        },
        Op(
            "slideshow", "Photos → vidéo",
            listOf(Param.Field("d", "Secondes par photo", "3", number = true)),
            note = "Choisis des photos : elles deviennent un diaporama MP4.",
        ) { ctx, files, v ->
            val d = num(v["d"]!!, 3.0).coerceIn(0.5, 60.0)
            val dir = Storage.workDir(ctx, "slides")
            files.forEachIndexed { i, uri ->
                update(0.3f * (i + 1) / files.size, "Préparation des photos…")
                val bmp = Docs.decodeBitmap(ctx, uri, 1920)
                File(dir, "img%04d.jpg".format(i + 1)).outputStream().use { Docs.writeBitmap(bmp, "jpg", 92, it) }
                bmp.recycle()
            }
            val tmp = File(dir, "diaporama.mp4")
            FF.run(
                listOf(
                    "-framerate", "1/$d", "-i", File(dir, "img%04d.jpg").absolutePath,
                    "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                    "-c:v", "libopenh264", "-b:v", "2500k", "-pix_fmt", "yuv420p", tmp.absolutePath,
                ),
                (d * files.size * 1000).toLong(),
            ) { update(0.3f + 0.7f * it, "Création de la vidéo…") }
            val saved = Storage.save(ctx, tmp, "Diaporama_${stamp()}.mp4")
            dir.deleteRecursively()
            done(listOf(saved))
        },
    )

    val audioOps = listOf(
        Op("trim", "Couper", listOf(Param.Field("start", "Début (ex. 0:10)", "0:00"), Param.Field("end", "Fin (ex. 0:40)", "0:30")),
            note = "Pratique pour faire une sonnerie.") { ctx, files, v ->
            each(ctx, files, "coupe", mp3, { listOf("-ss", time(v["start"]!!), "-to", time(v["end"]!!)) + MP3 })
        },
        Op("volume", "Volume", listOf(Param.Choice("f", "Volume", listOf("0.5" to "−50 %", "1.5" to "+50 %", "2" to "×2", "3" to "×3"), "1.5"))) { ctx, files, v ->
            each(ctx, files, "volume", mp3, { listOf("-af", "volume=${v["f"]}") + MP3 })
        },
        Op("normalize", "Normaliser le son", note = "Égalise le volume (loudnorm).") { ctx, files, _ ->
            each(ctx, files, "normalise", mp3, { listOf("-af", "loudnorm=I=-14:TP=-1.5:LRA=11") + MP3 })
        },
        Op("speed", "Vitesse", listOf(Param.Choice("f", "Vitesse", speeds, "1.25"))) { ctx, files, v ->
            each(ctx, files, "x${v["f"]}", mp3, { listOf("-af", "atempo=${v["f"]}") + MP3 })
        },
        Op("bass", "Booster les basses", listOf(Param.Choice("g", "Intensité", listOf("5" to "Léger", "10" to "Moyen", "15" to "Fort"), "10"))) { ctx, files, v ->
            each(ctx, files, "basses", mp3, { listOf("-af", "bass=g=${v["g"]}") + MP3 })
        },
        Op("karaoke", "Retirer la voix (karaoké)", note = "Efface ce qui est au centre du mixage (souvent la voix).") { ctx, files, _ ->
            each(ctx, files, "karaoke", mp3, { listOf("-af", "pan=stereo|c0=c0-c1|c1=c1-c0") + MP3 })
        },
        Op("fade", "Fondu entrée / sortie", listOf(Param.Field("d", "Durée du fondu (s)", "3", number = true))) { ctx, files, v ->
            val d = num(v["d"]!!, 3.0)
            each(ctx, files, "fondu", mp3, { uri ->
                val total = FF.durationMs(ctx, uri) / 1000.0
                val st = (total - d).coerceAtLeast(0.0)
                listOf("-af", "afade=t=in:d=$d,afade=t=out:st=$st:d=$d") + MP3
            })
        },
        Op("reverse", "À l'envers") { ctx, files, _ ->
            each(ctx, files, "envers", mp3, { listOf("-af", "areverse") + MP3 })
        },
        Op("merge", "Fusionner", minFiles = 2, note = "Les audios sont mis bout à bout dans l'ordre de la liste.") { ctx, files, _ ->
            val inputs = files.flatMap { listOf("-i", FF.input(ctx, it)) }
            val concat = files.indices.joinToString("") { "[$it:a]" } + "concat=n=${files.size}:v=0:a=1[a]"
            single(ctx, inputs + listOf("-filter_complex", concat, "-map", "[a]") + MP3, files.sumOf { FF.durationMs(ctx, it) }, "Fusion_${stamp()}.mp3")
        },
        Op("mono", "Stéréo → mono") { ctx, files, _ ->
            each(ctx, files, "mono", mp3, { listOf("-ac", "1") + MP3 })
        },
    )

    fun stamp(): String = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
}
