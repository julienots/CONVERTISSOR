package com.superessence.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Petits éléments réutilisés par les outils de calcul et du quotidien. */

fun copyText(ctx: Context, text: String, toast: String = "Copié") {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Fluxo", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

/** Lit un nombre tapé à la française ou à l'anglaise (« 1 234,5 » ou « 1234.5 »). */
fun num(s: String): Double? = s.replace(' ', ' ').replace(" ", "").replace(',', '.').toDoubleOrNull()

/** Formate un nombre sans zéros inutiles (« 12,5 », « 1 234 »). */
fun fmt(d: Double, decimals: Int = 2): String {
    if (d.isNaN() || d.isInfinite()) return "—"
    val s = String.format(Locale.FRANCE, "%,.${decimals}f", d)
    return if (s.contains(',')) s.trimEnd('0').trimEnd(',') else s
}

fun money(d: Double): String = if (d.isNaN() || d.isInfinite()) "—" else String.format(Locale.FRANCE, "%,.2f", d)

@Composable
fun NumField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun TextIn(value: String, onChange: (String) -> Unit, label: String, minLines: Int = 1, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minLines <= 1,
        minLines = minLines,
        modifier = modifier,
    )
}

/** Une ligne de résultat « libellé … valeur ». Un appui copie la valeur. */
@Composable
fun ResultRow(label: String, value: String, big: Boolean = false, color: Color = SE.Text) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(SE.SurfaceHi, RoundedCornerShape(10.dp))
            .clickable { copyText(ctx, value) }
            .padding(horizontal = 12.dp, vertical = if (big) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SE.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = if (big) 22.sp else 16.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, hint: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
