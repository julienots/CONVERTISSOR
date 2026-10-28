package com.superessence.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.core.JobState
import com.superessence.app.core.Jobs
import com.superessence.app.core.Storage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = SE.Bg,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SE.Bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SE.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, SE.SurfaceHi, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SE.Text)
        content()
    }
}

@Composable
fun Hint(text: String) = Text(text, color = SE.Muted, fontSize = 13.sp)

@Composable
fun BigButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, color: Color = SE.Cyan, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color(0xFF00121F)),
    ) {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SE.Cyan,
                    selectedLabelColor = Color(0xFF00121F),
                ),
            )
        }
    }
}

/** Sélecteur de fichiers (Storage Access Framework). */
@Composable
fun rememberPicker(multiple: Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    fun keep(uris: List<Uri>) {
        uris.forEach { u ->
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.isNotEmpty()) onPicked(uris)
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { keep(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> keep(listOfNotNull(u)) }
    return { if (multiple) many.launch(arrayOf("*/*")) else one.launch(arrayOf("*/*")) }
}

@Composable
fun FileList(uris: List<Uri>, onRemove: ((Uri) -> Unit)? = null) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        uris.take(50).forEach { u ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SE.SurfaceHi, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AttachFile, null, tint = SE.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(Storage.displayName(ctx, u), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(Storage.humanSize(Storage.size(ctx, u)), color = SE.Muted, fontSize = 12.sp)
                }
                if (onRemove != null) {
                    IconButton(onClick = { onRemove(u) }) { Icon(Icons.Filled.Close, "Retirer", tint = SE.Muted) }
                }
            }
        }
        if (uris.size > 50) Hint("… et ${uris.size - 50} autres")
    }
}

@Composable
fun PickBox(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .border(2.dp, SE.glow, RoundedCornerShape(20.dp))
            .background(SE.Surface, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AttachFile, null, tint = SE.Cyan)
            Spacer(Modifier.width(8.dp))
            Text(text, color = SE.Text, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Affiche l'état d'une tâche : progression, résultat (ouvrir / partager) ou erreur. */
@Composable
fun JobPanel(key: String) {
    val ctx = LocalContext.current
    val state by Jobs.state(key).collectAsState()
    when (val s = state) {
        JobState.Idle -> Unit
        is JobState.Running -> Section {
            Text(s.message, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (s.progress < 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = SE.Cyan)
            } else {
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = SE.Cyan)
                Text("${(s.progress * 100).toInt()} %", color = SE.Muted, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { Jobs.cancel(key) }) { Text("Annuler") }
        }
        is JobState.Done -> Section {
            Text("✅ " + s.message, color = SE.Green, fontWeight = FontWeight.SemiBold)
            s.files.take(20).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Text(Storage.humanSize(f.size), color = SE.Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { Storage.openFile(ctx, f) }) { Icon(Icons.Filled.OpenInNew, "Ouvrir", tint = SE.Cyan) }
                    IconButton(onClick = { Storage.shareFiles(ctx, listOf(f)) }) { Icon(Icons.Filled.Share, "Partager", tint = SE.Cyan) }
                }
            }
            if (s.files.size > 20) Hint("… et ${s.files.size - 20} autres fichiers")
            Row {
                if (s.files.size > 1) TextButton(onClick = { Storage.shareFiles(ctx, s.files) }) { Text("Tout partager") }
                TextButton(onClick = { Jobs.reset(key) }) { Text("OK") }
            }
        }
        is JobState.Failed -> Section {
            Text("⚠️ " + s.error, color = SE.Red)
            TextButton(onClick = { Jobs.reset(key) }) { Text("OK") }
        }
    }
}

@Composable
fun isBusy(key: String): Boolean {
    val state by Jobs.state(key).collectAsState()
    return state is JobState.Running
}

@Composable
fun Pill(text: String, color: Color = SE.Cyan) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
