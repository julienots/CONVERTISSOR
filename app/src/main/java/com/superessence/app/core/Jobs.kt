package com.superessence.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    fun start(key: String, block: suspend Progress.() -> JobState.Done) {
        running[key]?.cancel()
        val f = flow(key)
        f.value = JobState.Running(-1f, "Préparation…")
        running[key] = scope.launch {
            try {
                f.value = Progress(f).block()
            } catch (e: CancellationException) {
                f.value = JobState.Failed("Annulé")
            } catch (e: Throwable) {
                f.value = JobState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel(key: String) {
        running.remove(key)?.cancel()
    }

    fun reset(key: String) {
        cancel(key)
        flow(key).value = JobState.Idle
    }
}
