package com.superessence.app.youtube

import android.content.Context
import com.superessence.app.core.Background
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class YtStatus { WAITING, RUNNING, READY, SAVED, FAILED }

data class YtItem(
    val id: Long,
    val url: String,
    val format: String,
    val title: String = url,
    val status: YtStatus = YtStatus.WAITING,
    val progress: Float = -1f,
    val message: String = "En attente…",
    val file: File? = null,
    val saved: SavedFile? = null,
    val saving: Boolean = false,
)

/** File de téléchargements YouTube : plusieurs liens en parallèle, continue app fermée. */
object YtQueue {
    private const val PARALLEL = 3
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(PARALLEL)
    private val ids = AtomicLong(0)
    private val jobs = ConcurrentHashMap<Long, Job>()

    val items = MutableStateFlow<List<YtItem>>(emptyList())

    /** « Télécharger automatiquement » : enregistre dans Téléchargements dès que c'est prêt. */
    val autoSave = MutableStateFlow(false)

    private fun outDir(ctx: Context) = File(ctx.filesDir, "yt_out")

    private fun patch(id: Long, f: (YtItem) -> YtItem) =
        items.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** Extrait tous les liens d'un texte (un par ligne, ou mélangés). */
    fun parseLinks(text: String): List<String> =
        Regex("https?://\\S+").findAll(text).map { it.value.trimEnd(',', ';', ')', ']') }.distinct().toList()

    fun add(ctx: Context, text: String, format: String): Int {
        val app = ctx.applicationContext
        val links = parseLinks(text)
        links.forEach { link ->
            // Une playlist est développée en tâche de fond
            if (link.contains("list=") && !link.contains("watch")) {
                Background.begin(app, "Lecture de la playlist")
                scope.launch {
                    try {
                        YouTube.expand(link).forEach { enqueue(app, it, format) }
                    } finally {
                        Background.end(app)
                    }
                }
            } else {
                enqueue(app, link, format)
            }
        }
        return links.size
    }

    private fun enqueue(ctx: Context, url: String, format: String) {
        val item = YtItem(ids.incrementAndGet(), url, format)
        items.update { it + item }
        start(ctx, item)
    }

    private fun start(ctx: Context, item: YtItem) {
        Background.begin(ctx, "Liens → ${item.format.uppercase()}")
        jobs[item.id] = scope.launch {
            try {
                gate.withPermit {
                    patch(item.id) { it.copy(status = YtStatus.RUNNING, message = "Analyse du lien…") }
                    val video = YouTube.fetch(item.url)
                    patch(item.id) { it.copy(title = video.title) }
                    val dest = File(outDir(ctx), item.id.toString())
                    val file = YouTube.produce(ctx, video, item.format, dest) { p, msg ->
                        patch(item.id) { it.copy(progress = p, message = msg) }
                    }
                    patch(item.id) { it.copy(status = YtStatus.READY, file = file, progress = 1f, message = "Prêt") }
                    if (autoSave.value) save(ctx, items.value.first { it.id == item.id })
                    Background.notifyDone(ctx, "« ${video.title} » est prêt")
                }
            } catch (e: CancellationException) {
                patch(item.id) { it.copy(status = YtStatus.FAILED, message = "Annulé") }
            } catch (e: Throwable) {
                patch(item.id) { it.copy(status = YtStatus.FAILED, message = e.message ?: e.javaClass.simpleName) }
            } finally {
                jobs.remove(item.id)
                Background.end(ctx)
            }
        }
    }

    /** Enregistre le résultat dans Téléchargements/Fluxo. */
    fun save(ctx: Context, item: YtItem) {
        val file = item.file ?: return
        if (item.saving || item.saved != null) return
        patch(item.id) { it.copy(saving = true) }
        scope.launch {
            try {
                val saved = Storage.save(ctx.applicationContext, file, file.name)
                file.parentFile?.deleteRecursively()
                patch(item.id) { it.copy(saving = false, saved = saved, file = null, status = YtStatus.SAVED) }
            } catch (e: Throwable) {
                patch(item.id) { it.copy(saving = false, message = "Enregistrement impossible : ${e.message}") }
            }
        }
    }

    fun saveAll(ctx: Context) = items.value.filter { it.status == YtStatus.READY }.forEach { save(ctx, it) }

    fun retry(ctx: Context, item: YtItem) {
        val fresh = item.copy(status = YtStatus.WAITING, progress = -1f, message = "En attente…")
        patch(item.id) { fresh }
        start(ctx.applicationContext, fresh)
    }

    fun remove(item: YtItem) {
        jobs.remove(item.id)?.cancel()
        item.file?.parentFile?.deleteRecursively()
        items.update { list -> list.filter { it.id != item.id } }
    }

    fun clearFinished() {
        items.update { list -> list.filter { it.status == YtStatus.WAITING || it.status == YtStatus.RUNNING || it.status == YtStatus.READY } }
    }
}
