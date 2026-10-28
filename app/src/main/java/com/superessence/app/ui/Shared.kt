package com.superessence.app.ui

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** Fichiers / liens reçus par « Partager » et passage de fichiers entre écrans. */
object Shared {
    val incoming = MutableStateFlow<List<Uri>>(emptyList())
    val sharedText = MutableStateFlow<String?>(null)
    private var pending: List<Uri> = emptyList()

    fun handOff(uris: List<Uri>) {
        pending = uris
    }

    fun takePending(): List<Uri> = pending.also { pending = emptyList() }
}
