package com.superessence.app.youtube

import android.content.Context
import com.superessence.app.convert.FF
import com.superessence.app.convert.Formats
import com.superessence.app.core.Storage
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import android.net.Uri
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

/** Pont OkHttp pour NewPipeExtractor. */
private class OkDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha demandé", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

/** [info] est null pour un lien de fichier direct (non YouTube) : [directUrl] est alors utilisé. */
data class VideoInfo(
    val title: String,
    val uploader: String,
    val durationSec: Long,
    val info: StreamInfo?,
    val directUrl: String? = null,
    val directName: String? = null,
)

data class SearchHit(val title: String, val artist: String, val url: String, val durationSec: Long)

/** Liens YouTube (et autres services pris en charge : SoundCloud…) → MP3 / M4A / MP4. */
object YouTube {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ready = false

    private fun init() {
        if (!ready) {
            synchronized(this) {
                if (!ready) {
                    NewPipe.init(OkDownloader(client))
                    ready = true
                }
            }
        }
    }

    /** Réécrit les liens de partage (Dropbox, Google Drive) en liens de téléchargement direct. */
    fun normalize(url: String): String {
        val u = url.trim()
        Regex("drive\\.google\\.com/file/d/([\\w-]+)").find(u)?.let {
            return "https://drive.google.com/uc?export=download&id=${it.groupValues[1]}"
        }
        if (u.contains("dropbox.com")) {
            return if (u.contains("dl=0")) u.replace("dl=0", "dl=1") else if (u.contains("dl=1")) u else u + (if (u.contains('?')) "&dl=1" else "?dl=1")
        }
        return u
    }

    private fun isStreaming(url: String): Boolean = try {
        NewPipe.getServiceByUrl(url); true
    } catch (e: ExtractionException) {
        false
    }

    fun fetch(url: String): VideoInfo {
        init()
        val u = normalize(url)
        if (!isStreaming(u)) return direct(u)
        val info = StreamInfo.getInfo(u)
        return VideoInfo(info.name ?: "audio", info.uploaderName ?: "", info.duration, info)
    }

    /** Lien de fichier quelconque : on lit le nom et on vérifie qu'il est accessible. */
    private fun direct(url: String): VideoInfo {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful && r.code != 416) throw IOException("Lien inaccessible (HTTP ${r.code})")
            val ctype = r.header("Content-Type").orEmpty()
            if (ctype.startsWith("text/html")) throw IOException("Ce lien est une page web, pas un fichier ni une vidéo prise en charge")
            val fromHeader = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(r.header("Content-Disposition").orEmpty())?.groupValues?.get(1)
            val fromUrl = Uri.parse(r.request.url.toString()).lastPathSegment
            val name = Storage.sanitize(Uri.decode(fromHeader ?: fromUrl ?: "fichier")).ifBlank { "fichier" }
            return VideoInfo(name, "", 0, null, r.request.url.toString(), name)
        }
    }

    /** Recherche de morceaux (YouTube Music) ou de vidéos. */
    fun search(query: String, music: Boolean): List<SearchHit> {
        init()
        val service = ServiceList.YouTube
        val filter = if (music) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS else YoutubeSearchQueryHandlerFactory.VIDEOS
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        return SearchInfo.getInfo(service, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { SearchHit(it.name, (it.uploaderName ?: "").removeSuffix(" - Topic"), it.url, it.duration) }
    }

    private fun bestAudio(info: StreamInfo): AudioStream {
        val streams = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (streams.isEmpty()) throw IOException("Aucun flux audio téléchargeable pour ce lien")
        // Piste originale de préférence, puis meilleur débit (M4A en priorité, plus compatible)
        val original = streams.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { streams }
        return original.maxWith(compareBy<AudioStream>({ it.averageBitrate }, { if (it.format == MediaFormat.M4A) 1 else 0 }))
    }

    private fun bestVideo(info: StreamInfo, maxHeight: Int): VideoStream? =
        info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 && it.height in 1..maxHeight }
            .maxByOrNull { it.height * 1000 + it.fps }

    /** Télécharge une URL par morceaux (YouTube bride les requêtes non fragmentées). */
    private suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val chunk = 8L shl 20
        var pos = 0L
        var total = -1L
        dest.outputStream().buffered(1 shl 20).use { out ->
            while (total < 0 || pos < total) {
                coroutineContext.ensureActive()
                val end = pos + chunk - 1
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$pos-$end")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.code == 416 && total < 0) {
                        total = pos
                        return@use
                    }
                    if (!r.isSuccessful) throw IOException("Téléchargement refusé (HTTP ${r.code})")
                    if (total < 0) {
                        total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                            ?: r.body?.contentLength()?.takeIf { it > 0 && r.code == 200 }
                            ?: Long.MAX_VALUE
                    }
                    val body = r.body ?: throw IOException("Réponse vide")
                    val read = body.byteStream().use { Storage.copyStream(it, out) { b -> onProgress(pos + b, total) } }
                    pos += read
                    if (r.code == 200 || read == 0L) total = pos // serveur sans prise en charge des plages
                }
            }
        }
    }

    val AUDIO_FORMATS = listOf("mp3", "m4a", "flac", "wav", "ogg", "opus")

    private fun audioArgs(format: String, kbps: Int): List<String> = when (format) {
        "mp3" -> listOf("-c:a", "libmp3lame", "-b:a", "${kbps}k")
        "m4a" -> listOf("-c:a", "aac", "-b:a", "${kbps}k")
        "ogg" -> listOf("-c:a", "libvorbis", "-b:a", "${kbps}k")
        "opus" -> listOf("-c:a", "libopus", "-b:a", "${minOf(kbps, 256)}k")
        "flac" -> listOf("-c:a", "flac")
        else -> listOf("-c:a", "pcm_s16le")
    }

    private fun coverUrl(info: StreamInfo?): String? =
        info?.thumbnails?.filter { it.url.isNotBlank() }?.maxByOrNull { it.height }?.url

    private fun fetchCover(url: String?, dest: File): File? {
        if (url == null) return null
        return runCatching {
            val req = okhttp3.Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.byteStream()?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
            dest.takeIf { it.length() > 0 }
        }.getOrNull()
    }

    /**
     * Télécharge + convertit, puis place le résultat dans [destDir]. Renvoie le fichier final.
     * [quality] : débit audio en kb/s (ex. « 192 ») ou hauteur vidéo (ex. « 1080 »).
     */
    suspend fun produce(
        ctx: Context,
        video: VideoInfo,
        formatIn: String,
        quality: String,
        embedTags: Boolean,
        destDir: File,
        report: (Float, String) -> Unit,
    ): File {
        val work = Storage.workDir(ctx, "yt")
        val dur = video.durationSec * 1000
        val direct = video.info == null
        val format = if (formatIn == "original" && !direct) "mp4" else formatIn
        val base = Storage.sanitize(video.title.let { if (direct && format != "original") Storage.baseName(it) else it })
        val kbps = quality.toIntOrNull()?.coerceIn(64, 320) ?: 192
        val maxH = quality.toIntOrNull()?.takeIf { it >= 144 } ?: 1080
        try {
            destDir.mkdirs()
            if (direct) {
                val src = File(work, "source." + Storage.extension(video.directName.orEmpty()).ifBlank { "bin" })
                download(video.directUrl!!, src) { b, t ->
                    report(if (t in 1 until Long.MAX_VALUE) (if (format == "original") 1f else 0.7f) * b / t else -1f, "Téléchargement… ${Storage.humanSize(b)}")
                }
                val final = if (format == "original") File(destDir, video.directName.orEmpty().ifBlank { "fichier" }) else File(destDir, "$base.$format")
                if (format == "original") {
                    if (!src.renameTo(final)) src.copyTo(final, overwrite = true)
                } else {
                    val out = File(work, "out.$format")
                    val args = if (format in AUDIO_FORMATS) listOf("-vn") + audioArgs(format, kbps) else Formats.ffmpegArgs(format).second
                    val d = FF.durationMs(ctx, Uri.fromFile(src))
                    FF.run(listOf("-i", src.absolutePath) + args + out.absolutePath, d) { report(0.7f + 0.3f * it, "Conversion en ${format.uppercase()}…") }
                    if (!out.renameTo(final)) out.copyTo(final, overwrite = true)
                }
                return final
            }

            val info = video.info!!
            val audio = bestAudio(info)
            val audioFile = File(work, "audio." + (audio.format?.suffix ?: "m4a"))
            download(audio.content, audioFile) { b, t ->
                val share = if (format == "mp4") 0.3f else 0.7f
                report(if (t in 1 until Long.MAX_VALUE) share * b / t else -1f, "Téléchargement de l'audio… ${Storage.humanSize(b)}")
            }
            val out: File
            if (format in AUDIO_FORMATS) {
                out = File(work, "out.$format")
                val artist = video.uploader.removeSuffix(" - Topic")
                val meta = if (embedTags) listOf("-metadata", "title=${video.title}", "-metadata", "artist=$artist") else emptyList()
                val cover = if (embedTags && format in setOf("mp3", "m4a")) fetchCover(coverUrl(info), File(work, "cover.jpg")) else null
                val progress: (Float) -> Unit = { report(0.7f + 0.3f * it, "Conversion en ${format.uppercase()}…") }
                var done = false
                if (cover != null) {
                    val coverArgs = if (format == "mp3") {
                        listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
                    } else listOf("-disposition:v:0", "attached_pic")
                    done = runCatching {
                        FF.run(
                            listOf("-i", audioFile.absolutePath, "-i", cover.absolutePath, "-map", "0:a", "-map", "1:v") +
                                audioArgs(format, kbps) +
                                listOf("-c:v", "mjpeg", "-vf", "scale=600:600:force_original_aspect_ratio=increase,crop=600:600") +
                                coverArgs + meta + out.absolutePath,
                            dur, progress,
                        )
                    }.isSuccess
                }
                if (!done) {
                    FF.run(listOf("-i", audioFile.absolutePath, "-vn") + audioArgs(format, kbps) + meta + out.absolutePath, dur, progress)
                }
            } else {
                val v = bestVideo(info, maxH)
                out = File(work, "out.mp4")
                if (v != null) {
                    val videoFile = File(work, "video.mp4")
                    download(v.content, videoFile) { b, t ->
                        report(if (t in 1 until Long.MAX_VALUE) 0.3f + 0.6f * b / t else -1f, "Téléchargement de la vidéo (${v.height}p)… ${Storage.humanSize(b)}")
                    }
                    report(0.95f, "Assemblage audio + vidéo…")
                    FF.run(
                        listOf(
                            "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
                            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                            "-movflags", "+faststart", out.absolutePath,
                        ),
                        dur,
                    ) { }
                } else {
                    val muxed = info.videoStreams
                        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 }
                        .maxByOrNull { it.height }
                        ?: throw IOException("Aucune vidéo MP4 téléchargeable pour ce lien")
                    download(muxed.content, out) { b, t ->
                        report(if (t in 1 until Long.MAX_VALUE) 0.3f + 0.7f * b / t else -1f, "Téléchargement de la vidéo… ${Storage.humanSize(b)}")
                    }
                }
            }
            val ext = if (format in AUDIO_FORMATS) format else "mp4"
            val final = File(destDir, "$base.$ext")
            if (!out.renameTo(final)) out.copyTo(final, overwrite = true)
            return final
        } finally {
            work.deleteRecursively()
        }
    }

    /** Développe une playlist en liste de liens de vidéos (sinon renvoie le lien tel quel). */
    fun expand(url: String): List<String> {
        init()
        return runCatching {
            if (!isStreaming(url)) return@runCatching listOf(url)
            val service = NewPipe.getServiceByUrl(url)
            if (service.getLinkTypeByUrl(url) == StreamingService.LinkType.PLAYLIST) {
                val info = PlaylistInfo.getInfo(service, url)
                val items = ArrayList(info.relatedItems.map { it.url })
                var page = info.nextPage
                while (info.hasNextPage() && page != null && items.size < 500) {
                    val more = PlaylistInfo.getMoreItems(service, url, page)
                    items += more.items.map { it.url }
                    page = if (more.hasNextPage()) more.nextPage else null
                }
                items
            } else {
                listOf(url)
            }
        }.getOrDefault(listOf(url))
    }
}
