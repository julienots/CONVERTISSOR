package com.superessence.app.youtube

import android.content.Context
import com.superessence.app.convert.FF
import com.superessence.app.core.Storage
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
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

data class VideoInfo(val title: String, val uploader: String, val durationSec: Long, val info: StreamInfo)

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

    fun fetch(url: String): VideoInfo {
        init()
        val info = StreamInfo.getInfo(url.trim())
        return VideoInfo(info.name ?: "audio", info.uploaderName ?: "", info.duration, info)
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

    /** Télécharge + convertit, puis place le résultat dans [destDir]. Renvoie le fichier final. */
    suspend fun produce(ctx: Context, video: VideoInfo, format: String, destDir: File, report: (Float, String) -> Unit): File {
        val p = object {
            fun update(f: Float, msg: String) = report(f, msg)
        }
        val work = Storage.workDir(ctx, "yt")
        val base = Storage.sanitize(video.title)
        val dur = video.durationSec * 1000
        try {
            val audio = bestAudio(video.info)
            val audioFile = File(work, "audio." + (audio.format?.suffix ?: "m4a"))
            download(audio.content, audioFile) { b, t ->
                val share = if (format == "mp4") 0.3f else 0.7f
                p.update(if (t in 1 until Long.MAX_VALUE) share * b / t else -1f, "Téléchargement de l'audio… ${Storage.humanSize(b)}")
            }
            val out: File
            when (format) {
                "mp3" -> {
                    out = File(work, "out.mp3")
                    FF.run(
                        listOf("-i", audioFile.absolutePath, "-vn", "-c:a", "libmp3lame", "-b:a", "192k", out.absolutePath),
                        dur,
                    ) { p.update(0.7f + 0.3f * it, "Conversion en MP3…") }
                }
                "m4a" -> {
                    out = File(work, "out.m4a")
                    FF.run(listOf("-i", audioFile.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", out.absolutePath), dur) {
                        p.update(0.7f + 0.3f * it, "Conversion en M4A…")
                    }
                }
                else -> {
                    val v = bestVideo(video.info, 1080)
                    out = File(work, "out.mp4")
                    if (v != null) {
                        val videoFile = File(work, "video.mp4")
                        download(v.content, videoFile) { b, t ->
                            p.update(if (t in 1 until Long.MAX_VALUE) 0.3f + 0.6f * b / t else -1f, "Téléchargement de la vidéo (${v.height}p)… ${Storage.humanSize(b)}")
                        }
                        p.update(0.95f, "Assemblage audio + vidéo…")
                        FF.run(
                            listOf(
                                "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
                                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                                "-movflags", "+faststart", out.absolutePath,
                            ),
                            dur,
                        ) { }
                    } else {
                        val muxed = video.info.videoStreams
                            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == MediaFormat.MPEG_4 }
                            .maxByOrNull { it.height }
                            ?: throw IOException("Aucune vidéo MP4 téléchargeable pour ce lien")
                        download(muxed.content, out) { b, t ->
                            p.update(if (t in 1 until Long.MAX_VALUE) 0.3f + 0.7f * b / t else -1f, "Téléchargement de la vidéo… ${Storage.humanSize(b)}")
                        }
                    }
                }
            }
            destDir.mkdirs()
            val final = File(destDir, "$base.$format")
            if (!out.renameTo(final)) {
                out.copyTo(final, overwrite = true)
            }
            return final
        } finally {
            work.deleteRecursively()
        }
    }

    /** Développe une playlist en liste de liens de vidéos (sinon renvoie le lien tel quel). */
    fun expand(url: String): List<String> {
        init()
        return runCatching {
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
