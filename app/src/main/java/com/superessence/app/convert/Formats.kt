package com.superessence.app.convert

import com.superessence.app.core.Storage

enum class Kind(val label: String) {
    IMAGE("Image"),
    VIDEO("Vidéo"),
    AUDIO("Audio"),
    TEXT("Texte"),
    PDF("PDF"),
    DOCX("Document Word"),
    ARCHIVE("Archive"),
    APP("Paquet d'application"),
    OTHER("Fichier"),
}

data class Target(val id: String, val label: String)

object Formats {
    private val image = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif", "ico")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "wmv", "m4v", "ts", "mts", "mpg", "mpeg")
    private val audio = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus", "amr", "wma", "aiff", "mka")
    private val text = setOf("txt", "md", "csv", "json", "xml", "html", "htm", "log", "srt", "vtt", "ini", "yml", "yaml", "kt", "java", "py", "js", "css", "sql")
    private val archive = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "txz", "bz2")
    private val app = setOf("xapk", "apks", "apkm", "apk")

    fun kind(name: String, mime: String?): Kind {
        val ext = Storage.extension(name)
        return when {
            ext in app -> Kind.APP
            ext == "pdf" || mime == "application/pdf" -> Kind.PDF
            ext == "docx" -> Kind.DOCX
            ext in image || (mime?.startsWith("image/") == true) -> Kind.IMAGE
            ext in video || (mime?.startsWith("video/") == true) -> Kind.VIDEO
            ext in audio || (mime?.startsWith("audio/") == true) -> Kind.AUDIO
            ext in text || (mime?.startsWith("text/") == true) -> Kind.TEXT
            ext in archive -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }

    private val audioTargets = listOf(
        Target("mp3", "MP3"),
        Target("m4a", "M4A (AAC)"),
        Target("wav", "WAV"),
        Target("ogg", "OGG"),
        Target("flac", "FLAC"),
        Target("opus", "OPUS"),
    )
    private val zip = Target("zip", "ZIP (compressé)")

    fun targets(kind: Kind): List<Target> = when (kind) {
        Kind.VIDEO -> audioTargets + listOf(
            Target("mp4", "MP4"),
            Target("mkv", "MKV"),
            Target("webm", "WEBM"),
            Target("avi", "AVI"),
            Target("mov", "MOV"),
            Target("gif", "GIF animé (30 s max)"),
            Target("frames", "Images (ZIP, 1 image/s)"),
            Target("transcribe", "Texte (transcription)"),
            zip,
        )
        Kind.AUDIO -> audioTargets + listOf(Target("transcribe", "Texte (transcription)"), zip)
        Kind.IMAGE -> listOf(
            Target("jpg", "JPG"),
            Target("png", "PNG"),
            Target("webp", "WEBP"),
            Target("pdf", "PDF (toutes les images dans un PDF)"),
            zip,
        )
        Kind.TEXT -> listOf(Target("pdf", "PDF"), Target("txt", "TXT"), zip)
        Kind.PDF -> listOf(Target("txt", "TXT (texte du PDF)"), Target("png", "Images PNG (ZIP)"), Target("jpg", "Images JPG (ZIP)"), zip)
        Kind.DOCX -> listOf(Target("txt", "TXT"), Target("pdf", "PDF"), zip)
        Kind.ARCHIVE -> listOf(Target("extract", "Extraire le contenu"), zip)
        Kind.APP -> listOf(Target("apk", "APK unique (outil APK)"), Target("extract", "Extraire le contenu"))
        Kind.OTHER -> listOf(zip)
    }

    /** Extension produite et arguments FFmpeg pour une cible audio/vidéo. */
    fun ffmpegArgs(target: String): Pair<String, List<String>> = when (target) {
        "mp3" -> "mp3" to listOf("-vn", "-c:a", "libmp3lame", "-q:a", "2")
        "m4a" -> "m4a" to listOf("-vn", "-c:a", "aac", "-b:a", "192k")
        "wav" -> "wav" to listOf("-vn", "-c:a", "pcm_s16le")
        "ogg" -> "ogg" to listOf("-vn", "-c:a", "libvorbis", "-q:a", "5")
        "flac" -> "flac" to listOf("-vn", "-c:a", "flac")
        "opus" -> "opus" to listOf("-vn", "-c:a", "libopus", "-b:a", "128k")
        "mp4" -> "mp4" to H264 + listOf("-movflags", "+faststart")
        "mov" -> "mov" to H264
        "mkv" -> "mkv" to listOf("-map", "0:v:0?", "-map", "0:a?", "-c", "copy")
        "webm" -> "webm" to listOf(
            "-c:v", "libvpx", "-b:v", "1500k", "-deadline", "realtime", "-cpu-used", "8",
            "-c:a", "libopus", "-b:a", "128k",
        )
        "avi" -> "avi" to listOf("-c:v", "mpeg4", "-q:v", "4", "-c:a", "libmp3lame", "-q:a", "3")
        "gif" -> "gif" to listOf(
            "-t", "30",
            "-vf", "fps=10,scale=480:-2:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse",
            "-loop", "0",
        )
        else -> error("Format inconnu : $target")
    }

    val H264 = listOf(
        "-c:v", "libopenh264", "-b:v", "2500k", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k",
    )
}
