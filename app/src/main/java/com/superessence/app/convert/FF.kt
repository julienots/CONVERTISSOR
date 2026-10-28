package com.superessence.app.convert

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Petit pont coroutine vers FFmpegKit. */
object FF {

    fun input(ctx: Context, uri: Uri): String =
        if (uri.scheme == "file") uri.path!! else FFmpegKitConfig.getSafParameterForRead(ctx, uri)

    fun durationMs(ctx: Context, uri: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }.getOrDefault(0L)

    fun hasVideo(ctx: Context, uri: Uri): Boolean = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        } finally {
            r.release()
        }
    }.getOrDefault(false)

    suspend fun run(args: List<String>, durationMs: Long, onProgress: (Float) -> Unit) {
        suspendCancellableCoroutine { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                (listOf("-hide_banner", "-y") + args).toTypedArray(),
                { s ->
                    if (!cont.isActive) return@executeWithArgumentsAsync
                    val rc = s.returnCode
                    when {
                        ReturnCode.isSuccess(rc) -> cont.resume(Unit)
                        ReturnCode.isCancel(rc) -> cont.resumeWithException(CancellationException("Annulé"))
                        else -> {
                            val log = s.allLogsAsString.orEmpty().lines().filter { it.isNotBlank() }.takeLast(4)
                            cont.resumeWithException(IOException("Échec de la conversion : " + log.joinToString(" | ")))
                        }
                    }
                },
                { },
                { st ->
                    if (durationMs > 0) onProgress((st.time / durationMs).toFloat().coerceIn(0f, 1f))
                },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
    }

    suspend fun convert(
        ctx: Context,
        uri: Uri,
        out: File,
        codecArgs: List<String>,
        onProgress: (Float) -> Unit,
        preInput: List<String> = emptyList(),
    ) {
        val d = durationMs(ctx, uri)
        run(preInput + listOf("-i", input(ctx, uri)) + codecArgs + out.absolutePath, d, onProgress)
    }
}
