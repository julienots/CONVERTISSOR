package com.superessence.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.superessence.app.convert.Docs
import com.superessence.app.convert.FF
import com.superessence.app.convert.Formats
import com.superessence.app.core.JobState
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import com.superessence.app.tools.MediaTools.MP3
import com.superessence.app.tools.MediaTools.done
import com.superessence.app.tools.MediaTools.each
import com.superessence.app.tools.MediaTools.mp3
import com.superessence.app.tools.MediaTools.mp4
import com.superessence.app.tools.MediaTools.num
import com.superessence.app.tools.MediaTools.sameVideo
import com.superessence.app.tools.MediaTools.single
import com.superessence.app.tools.MediaTools.stamp
import com.superessence.app.tools.MediaTools.time
import com.superessence.app.ui.Op
import com.superessence.app.ui.Param
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.pow

/** Opérations vidéo et audio supplémentaires (FFmpeg). */
object MoreMedia {

    private fun f(d: Double) = String.format(Locale.US, "%.4f", d)

    /** Rend un texte dans une image PNG transparente (pour l'incruster dans une vidéo). */
    private fun textPng(ctx: Context, text: String, width: Int, color: Int, box: Boolean): File {
        val size = max(24f, width / 18f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(size / 8, 2f, 2f, Color.BLACK)
        }
        val lines = text.split('\n')
        val w = (lines.maxOf { p.measureText(it) } + size).toInt().coerceAtLeast(2)
        val h = (lines.size * size * 1.25f + size / 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w + w % 2, h + h % 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (box) c.drawColor(Color.argb(140, 0, 0, 0))
        lines.forEachIndexed { i, l -> c.drawText(l, size / 2, size * (1.1f + i * 1.25f), p) }
        val file = File(Storage.workDir(ctx, "txt"), "text.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file
    }

    private fun overlayPos(pos: String, margin: String = "20") = when (pos) {
        "top" -> "(W-w)/2:$margin"
        "center" -> "(W-w)/2:(H-h)/2"
        "tl" -> "$margin:$margin"
        "tr" -> "W-w-$margin:$margin"
        "bl" -> "$margin:H-h-$margin"
        "br" -> "W-w-$margin:H-h-$margin"
        else -> "(W-w)/2:H-h-$margin"
    }

    private fun videoWidth(ctx: Context, uri: Uri): Int = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h else w
        } finally {
            r.release()
        }
    }.getOrDefault(1280)

    /** Découpe chaque fichier en morceaux de [seconds] secondes (sans réencoder). */
    private suspend fun Progress.segments(ctx: Context, files: List<Uri>, seconds: Double, ext: (String) -> String, codec: List<String>): JobState.Done {
        val out = ArrayList<SavedFile>()
        files.forEachIndexed { i, uri ->
            val name = Storage.displayName(ctx, uri)
            val e = ext(Storage.extension(name))
            val dir = Storage.workDir(ctx, "seg")
            FF.run(
                listOf("-i", FF.input(ctx, uri)) + codec + listOf(
                    "-f", "segment", "-segment_time", f(seconds), "-reset_timestamps", "1", File(dir, "part_%03d.$e").absolutePath,
                ),
                FF.durationMs(ctx, uri),
            ) { update((i + it) / files.size, "Découpage de $name…") }
            dir.listFiles()?.sortedBy { it.name }?.forEachIndexed { k, part ->
                out += Storage.save(ctx, part, "${Storage.baseName(name)}_partie${k + 1}.$e", Storage.baseName(name))
            }
            dir.deleteRecursively()
        }
        return done(out)
    }

    private val positions = listOf("bottom" to "En bas", "top" to "En haut", "center" to "Au centre")
    private val corners = listOf("br" to "Bas droite", "bl" to "Bas gauche", "tr" to "Haut droite", "tl" to "Haut gauche", "center" to "Centre")

    // ============================================================== Vidéo

    val video = listOf(
        Op(
            "compress", "Réduire le poids",
            listOf(Param.Choice("q", "Qualité", listOf("1800" to "Bonne (720p)", "1000" to "Moyenne (720p)", "600" to "Légère (480p)", "350" to "Très légère (360p)"), "1000")),
            note = "Idéal pour envoyer une vidéo par message.",
        ) { ctx, files, v ->
            val br = v["q"]!!
            val h = when (br) { "600" -> 480; "350" -> 360; else -> 720 }
            each(ctx, files, "leger", mp4, {
                listOf("-vf", "scale=-2:'min($h,ih)'", "-c:v", "libopenh264", "-b:v", "${br}k", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart")
            })
        },
        Op("to_mp3", "Extraire le son (MP3)") { ctx, files, _ ->
            each(ctx, files, "son", mp3, { MP3 })
        },
        Op(
            "gif", "Transformer en GIF",
            listOf(
                Param.Field("start", "Début (ex. 0:05)", "0:00"),
                Param.Field("d", "Durée (secondes)", "5", number = true),
                Param.Choice("w", "Largeur", listOf("320" to "320 px", "480" to "480 px", "640" to "640 px"), "480"),
                Param.Choice("fps", "Fluidité", listOf("8" to "8 i/s", "12" to "12 i/s", "15" to "15 i/s"), "12"),
            ),
        ) { ctx, files, v ->
            val d = num(v["d"]!!, 5.0).coerceIn(0.5, 60.0)
            each(ctx, files, "anim", { "gif" }, {
                listOf("-t", f(d), "-vf", "fps=${v["fps"]},scale=${v["w"]}:-2:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", "-loop", "0")
            }, listOf("-ss", time(v["start"]!!)))
        },
        Op(
            "crop", "Recadrer (format)",
            listOf(Param.Choice("r", "Format", listOf("1:1" to "Carré 1:1", "9:16" to "Vertical 9:16", "16:9" to "Paysage 16:9", "4:5" to "Portrait 4:5", "4:3" to "4:3"), "1:1")),
            note = "Coupe les bords pour obtenir le format choisi (centré).",
        ) { ctx, files, v ->
            val (a, b) = v["r"]!!.split(':').map { it.toInt() }
            val vf = "crop='min(iw,ih*$a/$b)':'min(ih,iw*$b/$a)',scale=trunc(iw/2)*2:trunc(ih/2)*2"
            each(ctx, files, "recadre", mp4, { listOf("-vf", vf) + Formats.H264 })
        },
        Op(
            "vertical", "Format TikTok / Reels (fond flou)",
            listOf(Param.Choice("r", "Format", listOf("v" to "Vertical 9:16", "s" to "Carré 1:1", "h" to "Paysage 16:9"), "v")),
            note = "Garde toute l'image et remplit les bords avec une version floutée.",
        ) { ctx, files, v ->
            val (w, h) = when (v["r"]) { "s" -> 720 to 720; "h" -> 1280 to 720; else -> 720 to 1280 }
            val vf = "split[a][b];[a]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,boxblur=20:5[bg];" +
                "[b]scale=$w:$h:force_original_aspect_ratio=decrease[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1"
            each(ctx, files, "format", mp4, { listOf("-vf", vf) + Formats.H264 })
        },
        Op(
            "text", "Ajouter un texte",
            listOf(
                Param.Field("t", "Texte (\\n pour une nouvelle ligne)", "Mon texte"),
                Param.Choice("pos", "Position", positions, "bottom"),
                Param.Choice("c", "Couleur", listOf("w" to "Blanc", "y" to "Jaune", "k" to "Noir"), "w"),
                Param.Choice("box", "Fond", listOf("0" to "Sans", "1" to "Bandeau sombre"), "0"),
            ),
        ) { ctx, files, v ->
            val color = when (v["c"]) { "y" -> Color.YELLOW; "k" -> Color.BLACK; else -> Color.WHITE }
            each(ctx, files, "texte", mp4, { uri ->
                val png = textPng(ctx, v["t"]!!.replace("\\n", "\n"), videoWidth(ctx, uri), color, v["box"] == "1")
                listOf("-i", png.absolutePath, "-filter_complex", "[0:v][1:v]overlay=${overlayPos(v["pos"]!!, "40")}[v]", "-map", "[v]", "-map", "0:a?") + Formats.H264
            })
        },
        Op(
            "logo", "Ajouter un logo / une image",
            listOf(
                Param.Choice("pos", "Position", corners, "br"),
                Param.Choice("s", "Taille", listOf("0.12" to "Petite", "0.2" to "Moyenne", "0.35" to "Grande"), "0.2"),
            ),
            minFiles = 2, note = "1er fichier = vidéo, 2e fichier = image (PNG transparent conseillé).",
        ) { ctx, files, v ->
            val name = Storage.baseName(Storage.displayName(ctx, files[0]))
            val w = (videoWidth(ctx, files[0]) * num(v["s"]!!, 0.2)).toInt().let { it - it % 2 }.coerceAtLeast(16)
            single(
                ctx,
                listOf(
                    "-i", FF.input(ctx, files[0]), "-i", FF.input(ctx, files[1]),
                    "-filter_complex", "[1:v]scale=$w:-1[l];[0:v][l]overlay=${overlayPos(v["pos"]!!, "30")}[v]",
                    "-map", "[v]", "-map", "0:a?",
                ) + Formats.H264,
                FF.durationMs(ctx, files[0]), "${name}_logo.mp4",
            )
        },
        Op(
            "filter", "Filtres de couleur",
            listOf(Param.Choice("f", "Filtre", listOf(
                "bw" to "Noir et blanc", "sepia" to "Sépia", "bright" to "Plus lumineux", "contrast" to "Plus contrasté",
                "vivid" to "Couleurs vives", "vintage" to "Vintage", "cold" to "Froid", "warm" to "Chaud", "vignette" to "Vignette",
            ), "bw")),
        ) { ctx, files, v ->
            val vf = when (v["f"]) {
                "sepia" -> "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131"
                "bright" -> "eq=brightness=0.08:contrast=1.05"
                "contrast" -> "eq=contrast=1.3"
                "vivid" -> "eq=saturation=1.6"
                "vintage" -> "curves=preset=vintage"
                "cold" -> "colorbalance=bs=0.25:bm=0.15"
                "warm" -> "colorbalance=rs=0.2:rm=0.15"
                "vignette" -> "vignette=PI/4"
                else -> "hue=s=0"
            }
            each(ctx, files, v["f"]!!, mp4, { listOf("-vf", vf) + Formats.H264 })
        },
        Op(
            "blur", "Flouter / pixeliser",
            listOf(Param.Choice("m", "Effet", listOf("blur" to "Flou", "pixel" to "Pixels"), "blur")),
            note = "S'applique à toute l'image.",
        ) { ctx, files, v ->
            val vf = if (v["m"] == "pixel") "scale=trunc(iw/32)*2:trunc(ih/32)*2,scale=iw*16:ih*16:flags=neighbor" else "boxblur=12:2"
            each(ctx, files, v["m"]!!, mp4, { listOf("-vf", vf) + Formats.H264 })
        },
        Op("denoise", "Réduire le grain (bruit vidéo)") { ctx, files, _ ->
            each(ctx, files, "net", mp4, { listOf("-vf", "hqdn3d=4:3:6:4") + Formats.H264 })
        },
        Op("stabilize", "Stabiliser", note = "Réduit les tremblements d'une vidéo filmée à la main.") { ctx, files, _ ->
            each(ctx, files, "stable", mp4, { listOf("-vf", "deshake") + Formats.H264 })
        },
        Op("fade", "Fondu au noir (début et fin)", listOf(Param.Field("d", "Durée du fondu (s)", "1", number = true))) { ctx, files, v ->
            val d = num(v["d"]!!, 1.0).coerceIn(0.1, 10.0)
            each(ctx, files, "fondu", mp4, { uri ->
                val st = (FF.durationMs(ctx, uri) / 1000.0 - d).coerceAtLeast(0.0)
                listOf("-vf", "fade=t=in:d=${f(d)},fade=t=out:st=${f(st)}:d=${f(d)}", "-af", "afade=t=in:d=${f(d)},afade=t=out:st=${f(st)}:d=${f(d)}") + Formats.H264
            })
        },
        Op("boomerang", "Boomerang", note = "La vidéo joue à l'endroit puis à l'envers. Pour des clips courts (quelques secondes).") { ctx, files, _ ->
            each(ctx, files, "boomerang", mp4, {
                listOf("-filter_complex", "[0:v]split[a][b];[b]reverse[r];[a][r]concat=n=2:v=1:a=0[v]", "-map", "[v]", "-an") + Formats.H264
            })
        },
        Op("loop", "Répéter en boucle", listOf(Param.Choice("n", "Nombre de fois", listOf("2" to "×2", "3" to "×3", "5" to "×5", "10" to "×10"), "3"))) { ctx, files, v ->
            val n = v["n"]!!.toInt()
            each(ctx, files, "x$n", sameVideo, { listOf("-c", "copy") }, listOf("-stream_loop", (n - 1).toString()))
        },
        Op(
            "timelapse", "Accéléré / ralenti",
            listOf(Param.Choice("f", "Effet", listOf("0.25" to "Ralenti ×0,25", "4" to "Accéléré ×4", "8" to "Accéléré ×8", "16" to "Time-lapse ×16"), "8")),
            note = "Le son est retiré.",
        ) { ctx, files, v ->
            val k = num(v["f"]!!, 8.0)
            val vf = if (k < 1) "setpts=PTS/${f(k)},minterpolate=fps=30:mi_mode=dup" else "setpts=PTS/${f(k)}"
            each(ctx, files, "x${v["f"]}", mp4, { listOf("-vf", vf, "-an") + Formats.H264 })
        },
        Op("vflip", "Retourner (haut ↔ bas)") { ctx, files, _ ->
            each(ctx, files, "retourne", mp4, { listOf("-vf", "vflip") + Formats.H264 })
        },
        Op("fps", "Images par seconde", listOf(Param.Choice("r", "Cadence", listOf("15" to "15 i/s", "24" to "24 i/s", "30" to "30 i/s"), "30"))) { ctx, files, v ->
            each(ctx, files, "${v["r"]}fps", mp4, { listOf("-vf", "fps=${v["r"]}") + Formats.H264 })
        },
        Op("louder", "Monter le son", listOf(Param.Choice("f", "Volume", listOf("1.5" to "+50 %", "2" to "×2", "3" to "×3", "0.5" to "−50 %"), "2"))) { ctx, files, v ->
            each(ctx, files, "volume", sameVideo, { listOf("-c:v", "copy", "-af", "volume=${v["f"]}", "-c:a", "aac", "-b:a", "192k") })
        },
        Op(
            "cut_out", "Supprimer un passage",
            listOf(Param.Field("start", "De (ex. 0:10)", "0:10"), Param.Field("end", "À (ex. 0:20)", "0:20")),
            note = "Retire le passage choisi et recolle le reste.",
        ) { ctx, files, v ->
            val a = time(v["start"]!!); val b = time(v["end"]!!)
            each(ctx, files, "sans_passage", mp4, {
                listOf(
                    "-vf", "select='not(between(t,$a,$b))',setpts=N/FRAME_RATE/TB",
                    "-af", "aselect='not(between(t,$a,$b))',asetpts=N/SR/TB",
                ) + Formats.H264
            })
        },
        Op("split", "Découper en morceaux", listOf(Param.Field("s", "Durée d'un morceau (secondes)", "30", number = true)),
            note = "Pratique pour les statuts WhatsApp (30 s). Sans réencodage : les coupes se font sur les images clés.") { ctx, files, v ->
            segments(ctx, files, num(v["s"]!!, 30.0).coerceAtLeast(1.0), sameVideo, listOf("-c", "copy", "-map", "0"))
        },
        Op("sheet", "Planche d'aperçus (16 images)", note = "Une image JPG avec 16 vignettes réparties sur toute la vidéo.") { ctx, files, _ ->
            each(ctx, files, "apercu", { "jpg" }, { uri ->
                val dur = (FF.durationMs(ctx, uri) / 1000.0).coerceAtLeast(1.0)
                listOf("-vf", "fps=${f(16 / dur)},scale=360:-2,tile=4x4:padding=4:color=white", "-frames:v", "1", "-q:v", "3")
            })
        },
        Op("audio_cover", "Audio + image → vidéo", minFiles = 2, note = "1er fichier = musique, 2e fichier = image. Pour publier un son en vidéo.") { ctx, files, _ ->
            val name = Storage.baseName(Storage.displayName(ctx, files[0]))
            single(
                ctx,
                listOf(
                    "-loop", "1", "-framerate", "2", "-i", FF.input(ctx, files[1]), "-i", FF.input(ctx, files[0]),
                    "-vf", "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1",
                    "-c:v", "libopenh264", "-b:v", "800k", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest",
                ),
                FF.durationMs(ctx, files[0]), "${name}_video.mp4",
            )
        },
    )

    // ============================================================== Audio

    private fun pitchFilter(semitones: Double): String {
        val r = 2.0.pow(semitones / 12)
        return "asetrate=44100*${f(r)},aresample=44100,atempo=${f(1 / r)}"
    }

    val audio = listOf(
        Op(
            "pitch", "Changer la tonalité",
            listOf(Param.Choice("n", "Demi-tons", listOf("-4" to "−4", "-2" to "−2", "-1" to "−1", "1" to "+1", "2" to "+2", "4" to "+4"), "2")),
            note = "Plus aigu ou plus grave, sans changer la vitesse.",
        ) { ctx, files, v ->
            each(ctx, files, "ton${v["n"]}", mp3, { listOf("-af", "aresample=44100," + pitchFilter(num(v["n"]!!, 2.0))) + MP3 })
        },
        Op("nightcore", "Nightcore (plus rapide et aigu)") { ctx, files, _ ->
            each(ctx, files, "nightcore", mp3, { listOf("-af", "aresample=44100,asetrate=44100*1.25,aresample=44100") + MP3 })
        },
        Op("slowed", "Slowed + reverb") { ctx, files, _ ->
            each(ctx, files, "slowed", mp3, { listOf("-af", "aresample=44100,asetrate=44100*0.85,aresample=44100,aecho=0.8:0.88:60:0.35") + MP3 })
        },
        Op("echo", "Écho / réverbération", listOf(Param.Choice("t", "Type", listOf("room" to "Petite salle", "hall" to "Grande salle", "echo" to "Écho lointain"), "room"))) { ctx, files, v ->
            val af = when (v["t"]) {
                "hall" -> "aecho=0.8:0.85:60|120|180:0.45|0.35|0.25"
                "echo" -> "aecho=0.8:0.9:500:0.3"
                else -> "aecho=0.8:0.88:30|50:0.3|0.2"
            }
            each(ctx, files, "echo", mp3, { listOf("-af", af) + MP3 })
        },
        Op("8d", "Son 8D (tourne autour de la tête)", note = "À écouter avec un casque.") { ctx, files, _ ->
            each(ctx, files, "8D", mp3, { listOf("-af", "apulsator=hz=0.08:amount=0.85", "-ac", "2") + MP3 })
        },
        Op("denoise", "Réduire le bruit de fond", note = "Atténue souffle, ventilateur, bruit de rue.") { ctx, files, _ ->
            each(ctx, files, "propre", mp3, { listOf("-af", "highpass=f=70,afftdn=nf=-25") + MP3 })
        },
        Op("voice", "Voix plus claire (podcast)", note = "Coupe les graves inutiles, compresse et égalise le volume.") { ctx, files, _ ->
            each(ctx, files, "voix", mp3, { listOf("-af", "highpass=f=80,afftdn=nf=-25,acompressor=threshold=-20dB:ratio=3:attack=5:release=100,loudnorm=I=-16:TP=-1.5") + MP3 })
        },
        Op("silence", "Supprimer les silences", note = "Retire les blancs de plus d'une seconde.") { ctx, files, _ ->
            each(ctx, files, "sans_silence", mp3, {
                listOf("-af", "silenceremove=start_periods=1:start_threshold=-45dB:stop_periods=-1:stop_duration=1:stop_threshold=-45dB") + MP3
            })
        },
        Op("treble", "Booster les aigus", listOf(Param.Choice("g", "Intensité", listOf("4" to "Léger", "8" to "Moyen", "12" to "Fort"), "8"))) { ctx, files, v ->
            each(ctx, files, "aigus", mp3, { listOf("-af", "treble=g=${v["g"]}") + MP3 })
        },
        Op(
            "eq", "Égaliseur (préréglages)",
            listOf(Param.Choice("p", "Style", listOf("pop" to "Pop", "rock" to "Rock", "classic" to "Classique", "voice" to "Voix", "bassless" to "Moins de basses"), "pop")),
        ) { ctx, files, v ->
            val af = when (v["p"]) {
                "rock" -> "equalizer=f=80:t=q:w=1:g=5,equalizer=f=1000:t=q:w=1:g=-2,equalizer=f=8000:t=q:w=1:g=4"
                "classic" -> "equalizer=f=100:t=q:w=1:g=3,equalizer=f=4000:t=q:w=1:g=-2,equalizer=f=12000:t=q:w=1:g=3"
                "voice" -> "highpass=f=100,equalizer=f=2500:t=q:w=1:g=4,equalizer=f=250:t=q:w=1:g=-3"
                "bassless" -> "bass=g=-10"
                else -> "equalizer=f=60:t=q:w=1:g=3,equalizer=f=3000:t=q:w=1:g=2,equalizer=f=10000:t=q:w=1:g=3"
            }
            each(ctx, files, v["p"]!!, mp3, { listOf("-af", af) + MP3 })
        },
        Op("m4r", "Sonnerie iPhone (M4R, 30 s)", listOf(Param.Field("start", "Début (ex. 0:45)", "0:00"))) { ctx, files, v ->
            each(ctx, files, "sonnerie", { "m4r" }, {
                listOf("-t", "30", "-vn", "-c:a", "aac", "-b:a", "192k", "-af", "afade=t=out:st=28:d=2", "-f", "mp4")
            }, listOf("-ss", time(v["start"]!!)))
        },
        Op("bitrate", "Qualité MP3", listOf(Param.Choice("b", "Débit", listOf("96" to "96 kb/s (léger)", "128" to "128 kb/s", "192" to "192 kb/s", "320" to "320 kb/s (max)"), "192"))) { ctx, files, v ->
            each(ctx, files, "${v["b"]}k", mp3, { listOf("-vn", "-c:a", "libmp3lame", "-b:a", "${v["b"]}k") })
        },
        Op("split", "Découper en morceaux", listOf(Param.Field("m", "Durée d'un morceau (minutes)", "5", number = true))) { ctx, files, v ->
            segments(ctx, files, num(v["m"]!!, 5.0).coerceAtLeast(0.1) * 60, mp3, listOf("-vn") + MP3)
        },
        Op(
            "mix", "Mélanger deux pistes",
            listOf(Param.Choice("vol", "Volume de la 2e piste", listOf("0.15" to "Très bas", "0.3" to "Bas", "0.6" to "Moyen", "1" to "Égal"), "0.3")),
            minFiles = 2, note = "1er fichier = voix (ou piste principale), 2e = musique de fond.",
        ) { ctx, files, v ->
            single(
                ctx,
                listOf(
                    "-i", FF.input(ctx, files[0]), "-stream_loop", "-1", "-i", FF.input(ctx, files[1]),
                    "-filter_complex", "[1:a]volume=${v["vol"]}[m];[0:a][m]amix=inputs=2:duration=first:dropout_transition=2:normalize=0[a]",
                    "-map", "[a]",
                ) + MP3,
                FF.durationMs(ctx, files[0]), "Mix_${stamp()}.mp3",
            )
        },
        Op("loop", "Répéter en boucle", listOf(Param.Choice("n", "Nombre de fois", listOf("2" to "×2", "3" to "×3", "5" to "×5", "10" to "×10"), "2"))) { ctx, files, v ->
            val n = v["n"]!!.toInt()
            each(ctx, files, "x$n", mp3, { MP3 }, listOf("-stream_loop", (n - 1).toString()))
        },
        Op(
            "channels", "Canaux gauche / droite",
            listOf(Param.Choice("c", "Action", listOf("swap" to "Inverser G ↔ D", "left" to "Garder la gauche", "right" to "Garder la droite"), "swap")),
        ) { ctx, files, v ->
            val af = when (v["c"]) { "left" -> "pan=mono|c0=c0"; "right" -> "pan=mono|c0=c1"; else -> "pan=stereo|c0=c1|c1=c0" }
            each(ctx, files, v["c"]!!, mp3, { listOf("-af", af) + MP3 })
        },
        Op("delay", "Ajouter un silence au début", listOf(Param.Field("s", "Secondes", "2", number = true))) { ctx, files, v ->
            val ms = (num(v["s"]!!, 2.0) * 1000).toLong()
            each(ctx, files, "decale", mp3, { listOf("-af", "adelay=$ms|$ms") + MP3 })
        },
        Op("wave", "Image de l'onde (PNG)", note = "Dessine la forme d'onde du son, pour une miniature ou une vidéo.") { ctx, files, _ ->
            each(ctx, files, "onde", { "png" }, { listOf("-filter_complex", "aformat=channel_layouts=mono,showwavespic=s=1600x400:colors=0x2563EB", "-frames:v", "1") })
        },
        Op("spectrum", "Spectrogramme (PNG)") { ctx, files, _ ->
            each(ctx, files, "spectre", { "png" }, { listOf("-lavfi", "showspectrumpic=s=1280x512:legend=1", "-frames:v", "1") })
        },
        Op("limiter", "Éviter la saturation (limiteur)") { ctx, files, _ ->
            each(ctx, files, "limite", mp3, { listOf("-af", "alimiter=limit=0.89:level=disabled") + MP3 })
        },
    )
}
