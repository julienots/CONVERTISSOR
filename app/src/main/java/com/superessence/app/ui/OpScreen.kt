package com.superessence.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import com.superessence.app.core.JobState
import com.superessence.app.core.Jobs
import com.superessence.app.core.Progress

/** Réglage d'une opération : champ texte/nombre ou liste de choix. */
sealed class Param(val key: String, val label: String) {
    class Field(key: String, label: String, val default: String, val number: Boolean = false) : Param(key, label)
    class Choice(key: String, label: String, val options: List<Pair<String, String>>, val default: String) : Param(key, label)
}

/** Une opération d'un outil (ex. « Couper », « Fusionner »). */
class Op(
    val id: String,
    val label: String,
    val params: List<Param> = emptyList(),
    val minFiles: Int = 1,
    val note: String? = null,
    val action: suspend Progress.(ctx: Context, files: List<Uri>, values: Map<String, String>) -> JobState.Done,
)

/** Écran générique : choisir des fichiers, une opération, ses réglages, puis lancer (en parallèle). */
@Composable
fun OpScreen(title: String, group: String, intro: String, ops: List<Op>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files = remember { mutableStateListOf<Uri>().apply { addAll(Shared.takePending()) } }
    var op by remember { mutableStateOf(ops.first()) }
    val values = remember { mutableStateMapOf<String, String>() }
    val pick = rememberPicker(true) { p -> files.addAll(p.filter { it !in files }) }

    fun value(p: Param): String = values[p.key] ?: when (p) {
        is Param.Field -> p.default
        is Param.Choice -> p.default
    }

    ToolScaffold(title, onBack) {
        Hint(intro)
        Section("Fichiers") {
            PickBox(if (files.isEmpty()) "Choisir des fichiers" else "Ajouter des fichiers", pick)
            if (files.isNotEmpty()) FileList(files) { files.remove(it) }
        }
        Section("Action") {
            ChoiceChips(ops, op, { it.label }) { op = it }
            op.note?.let { Hint(it) }
            op.params.forEach { p ->
                when (p) {
                    is Param.Field -> OutlinedTextField(
                        value = value(p),
                        onValueChange = { values[p.key] = it },
                        label = { Text(p.label) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (p.number) KeyboardType.Decimal else KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is Param.Choice -> {
                        Text(p.label)
                        ChoiceChips(p.options, p.options.firstOrNull { it.first == value(p) }, { it.second }) {
                            values[p.key] = it.first
                        }
                    }
                }
            }
            val enough = files.size >= op.minFiles
            if (!enough && files.isNotEmpty()) Hint("Choisis au moins ${op.minFiles} fichiers pour cette action.")
            BigButton(op.label, Icons.Filled.PlayArrow, enabled = enough) {
                val current = op
                val list = files.toList()
                val vals = current.params.associate { it.key to value(it) }
                Jobs.startNew(group, "$title : ${current.label}") { current.action.invoke(this, ctx.applicationContext, list, vals) }
            }
        }
        JobGroup(group)
    }
}
