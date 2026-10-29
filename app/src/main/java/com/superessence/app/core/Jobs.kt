package com.superessence.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.superessence.app.SuperEssenceApp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap

sealed interface JobState {
    data object Idle : JobState
    /** progress dans [0,1], ou < 0 si indéterminé. */
    data class Running(val progress: Float, val message: String) : JobState
    data class Done(val message: String, val files: List<SavedFile> = emptyList(), val text: String? = null) : JobState
    data class Failed(val error: String) : JobState
}

class Progress internal constructor(private val flow: MutableStateFlow<JobState>) {
    fun update(progress: Float, message: String) {
        flow.value = JobState.Running(progress, message)
    }
}

/** Tâches longues indépendantes des écrans (survivent à la navigation). */
object Jobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, MutableStateFlow<JobState>>()
    private val running = ConcurrentHashMap<String, Job>()

    fun state(key: String): StateFlow<JobState> = flow(key)

    private fun flow(key: String) = states.getOrPut(key) { MutableStateFlow(JobState.Idle) }

    private val groups = ConcurrentHashMap<String, MutableStateFlow<List<String>>>()
    private val counter = AtomicInteger(0)

    private fun group(name: String) = groups.getOrPut(name) { MutableStateFlow(emptyList()) }

    /** Clés des tâches d'un groupe (plusieurs conversions en même temps), la plus récente d'abord. */
    fun keys(groupName: String): StateFlow<List<String>> = group(groupName)

    /** Lance une nouvelle tâche en parallèle des autres du même groupe. */
    fun startNew(groupName: String, label: String, block: suspend Progress.() -> JobState.Done): String {
        val key = "$groupName#${counter.incrementAndGet()}"
        group(groupName).update { listOf(key) + it }
        start(key, label, block)
        return key
    }

    fun start(key: String, label: String = "Conversion", block: suspend Progress.() -> JobState.Done) {
        running[key]?.cancel()
        val f = flow(key)
        f.value = JobState.Running(-1f, "Préparation…")
        val app = SuperEssenceApp.instance
        Background.begin(app, label)
        running[key] = scope.launch {
            try {
                val done = Progress(f).block()
                f.value = done
                Background.notifyDone(app, "$label : terminé")
            } catch (e: CancellationException) {
                if (f.value is JobState.Running) f.value = JobState.Failed("Annulé")
            } catch (e: Throwable) {
                f.value = JobState.Failed(e.message ?: e.javaClass.simpleName)
                Background.notifyDone(app, "$label : échec")
            } finally {
                coroutineContext[Job]?.let { running.remove(key, it) }
                Background.end(app)
            }
        }
    }

    fun cancel(key: String) {
        running.remove(key)?.cancel()
    }

    fun reset(key: String) {
        cancel(key)
        flow(key).value = JobState.Idle
        val g = key.substringBefore('#', "")
        if (g.isNotEmpty()) {
            group(g).update { list -> list - key }
            states.remove(key)
        }
    }
}
