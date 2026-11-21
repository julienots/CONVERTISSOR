package com.superessence.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.app.core.Prefs
import com.superessence.app.tools.Calc
import com.superessence.app.tools.Numbers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

// ------------------------------------------------------------------ Calculatrice

@Composable
fun CalcScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var expr by rememberSaveable { mutableStateOf("") }
    var deg by rememberSaveable { mutableStateOf(true) }
    val history = remember { mutableStateListOf<String>().apply { addAll(Prefs.getString("calc_hist").split('\n').filter { it.isNotBlank() }) } }
    val preview = runCatching { if (expr.isBlank()) null else Calc.eval(expr, deg) }.getOrNull()

    fun equals() {
        val r = runCatching { Calc.eval(expr, deg) }.getOrNull() ?: return
        history.add(0, "$expr = ${fmt(r, 10)}")
        while (history.size > 30) history.removeAt(history.size - 1)
        Prefs.putString("calc_hist", history.joinToString("\n"))
        expr = fmt(r, 10).replace(" ", "").replace(" ", "").replace(" ", "")
    }

    val keys = listOf(
        listOf("C", "(", ")", "⌫"),
        listOf("7", "8", "9", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("1", "2", "3", "−"),
        listOf("0", ",", "%", "+"),
        listOf("√", "^", "π", "="),
    )
    val sci = listOf("sin(", "cos(", "tan(", "ln(", "log(", "!", "e", "abs(")

    ToolScaffold("Calculatrice", onBack) {
        Section {
            Text(expr.ifEmpty { "0" }, fontSize = 30.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            Text(preview?.let { "= " + fmt(it, 10) } ?: " ", fontSize = 20.sp, color = SE.Muted, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().clickable { preview?.let { copyText(ctx, fmt(it, 10)) } })
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            keys.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val accent = k == "="
                        val op = k in setOf("÷", "×", "−", "+", "C", "⌫", "(", ")", "%", "√", "^", "π")
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(if (accent) SE.Accent else if (op) SE.SurfaceHi else SE.Surface, RoundedCornerShape(12.dp))
                                .clickable {
                                    when (k) {
                                        "C" -> expr = ""
                                        "⌫" -> expr = expr.dropLast(1)
                                        "=" -> equals()
                                        else -> expr += k
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(k, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = if (accent) SE.OnAccent else SE.Text)
                        }
                    }
                }
            }
        }
        Section("Fonctions") {
            ChoiceChips(sci, null, { it.removeSuffix("(") }) { expr += it }
            SwitchRow("Angles en degrés", deg, if (deg) "Sinon : radians" else "Radians") { deg = it }
        }
        if (history.isNotEmpty()) {
            Section("Historique") {
                history.take(15).forEach { h ->
                    Text(h, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { expr = h.substringBefore(" =") })
                }
                TextButton(onClick = { history.clear(); Prefs.putString("calc_hist", "") }) { Text("Effacer l'historique") }
            }
        }
    }
}

// ------------------------------------------------------------------ Pourcentages, TVA, remises

@Composable
fun PercentScreen(onBack: () -> Unit) {
    var a by rememberSaveable { mutableStateOf("20") }
    var b by rememberSaveable { mutableStateOf("150") }
    var from by rememberSaveable { mutableStateOf("80") }
    var to by rememberSaveable { mutableStateOf("100") }
    var price by rememberSaveable { mutableStateOf("100") }
    var rate by rememberSaveable { mutableStateOf("20") }
    var ht by rememberSaveable { mutableStateOf(true) }
    var oldP by rememberSaveable { mutableStateOf("59,99") }
    var disc by rememberSaveable { mutableStateOf("30") }
    var disc2 by rememberSaveable { mutableStateOf("") }
    var part by rememberSaveable { mutableStateOf("45") }
    var whole by rememberSaveable { mutableStateOf("60") }

    ToolScaffold("Pourcentages", onBack) {
        Section("x % de y") {
            Row2 { NumField(a, { a = it }, "Pourcentage", Modifier.weight(1f)); NumField(b, { b = it }, "Nombre", Modifier.weight(1f)) }
            val x = num(a); val y = num(b)
            if (x != null && y != null) {
                ResultRow("$a % de $b", fmt(x * y / 100), big = true)
                ResultRow("$b + $a %", fmt(y * (1 + x / 100)))
                ResultRow("$b − $a %", fmt(y * (1 - x / 100)))
            }
        }
        Section("Quel pourcentage ?") {
            Row2 { NumField(part, { part = it }, "Partie", Modifier.weight(1f)); NumField(whole, { whole = it }, "Total", Modifier.weight(1f)) }
            val p = num(part); val w = num(whole)
            if (p != null && w != null && w != 0.0) ResultRow("$part sur $whole", fmt(p / w * 100) + " %", big = true)
        }
        Section("Évolution") {
            Row2 { NumField(from, { from = it }, "Avant", Modifier.weight(1f)); NumField(to, { to = it }, "Après", Modifier.weight(1f)) }
            val f = num(from); val t = num(to)
            if (f != null && t != null && f != 0.0) {
                val e = (t - f) / abs(f) * 100
                ResultRow("Variation", (if (e >= 0) "+" else "") + fmt(e) + " %", big = true, color = if (e >= 0) SE.Green else SE.Red)
                ResultRow("Différence", fmt(t - f))
                ResultRow("Coefficient", "× " + fmt(t / f, 4))
            }
        }
        Section("TVA") {
            NumField(price, { price = it }, if (ht) "Prix HT" else "Prix TTC")
            ChoiceChips(listOf(true, false), ht, { if (it) "J'ai le HT" else "J'ai le TTC" }) { ht = it }
            ChoiceChips(listOf("20", "10", "5,5", "2,1", "8,1", "21", "19"), rate, { "$it %" }) { rate = it }
            NumField(rate, { rate = it }, "Taux (%)")
            val p = num(price); val r = num(rate)
            if (p != null && r != null) {
                val htV = if (ht) p else p / (1 + r / 100)
                val ttcV = if (ht) p * (1 + r / 100) else p
                ResultRow("HT", money(htV))
                ResultRow("TVA", money(ttcV - htV))
                ResultRow("TTC", money(ttcV), big = true)
            }
        }
        Section("Soldes & remises") {
            NumField(oldP, { oldP = it }, "Prix de départ")
            Row2 { NumField(disc, { disc = it }, "Remise (%)", Modifier.weight(1f)); NumField(disc2, { disc2 = it }, "2e remise (%)", Modifier.weight(1f)) }
            val p = num(oldP); val d = num(disc) ?: 0.0; val d2 = num(disc2) ?: 0.0
            if (p != null) {
                val np = p * (1 - d / 100) * (1 - d2 / 100)
                ResultRow("Nouveau prix", money(np), big = true, color = SE.Green)
                ResultRow("Économie", money(p - np))
                if (d2 > 0) ResultRow("Remise totale réelle", fmt((1 - np / p) * 100) + " %")
            }
        }
    }
}

// ------------------------------------------------------------------ Argent

@Composable
fun MoneyScreen(onBack: () -> Unit) {
    var bill by rememberSaveable { mutableStateOf("80") }
    var tip by rememberSaveable { mutableStateOf("10") }
    var people by rememberSaveable { mutableStateOf("4") }
    var loan by rememberSaveable { mutableStateOf("150000") }
    var loanRate by rememberSaveable { mutableStateOf("3,5") }
    var years by rememberSaveable { mutableStateOf("20") }
    var save by rememberSaveable { mutableStateOf("1000") }
    var monthly by rememberSaveable { mutableStateOf("100") }
    var saveRate by rememberSaveable { mutableStateOf("3") }
    var saveYears by rememberSaveable { mutableStateOf("10") }
    var gross by rememberSaveable { mutableStateOf("2500") }
    var status by rememberSaveable { mutableStateOf("0.22") }
    var hourly by rememberSaveable { mutableStateOf("12") }
    var hours by rememberSaveable { mutableStateOf("35") }

    ToolScaffold("Argent", onBack) {
        Section("Addition & pourboire") {
            NumField(bill, { bill = it }, "Montant de l'addition")
            ChoiceChips(listOf("0", "5", "10", "15", "20"), tip, { "$it %" }) { tip = it }
            NumField(people, { people = it }, "Nombre de personnes", decimal = false)
            val b = num(bill); val t = num(tip) ?: 0.0; val n = (num(people) ?: 1.0).coerceAtLeast(1.0)
            if (b != null) {
                ResultRow("Pourboire", money(b * t / 100))
                ResultRow("Total", money(b * (1 + t / 100)))
                ResultRow("Par personne", money(b * (1 + t / 100) / n), big = true)
            }
        }
        Section("Crédit (mensualités)") {
            NumField(loan, { loan = it }, "Montant emprunté")
            Row2 { NumField(loanRate, { loanRate = it }, "Taux annuel (%)", Modifier.weight(1f)); NumField(years, { years = it }, "Durée (années)", Modifier.weight(1f)) }
            val c = num(loan); val r = num(loanRate); val y = num(years)
            if (c != null && r != null && y != null && y > 0) {
                val n = y * 12
                val m = r / 100 / 12
                val pay = if (m == 0.0) c / n else c * m / (1 - (1 + m).pow(-n))
                ResultRow("Mensualité", money(pay), big = true)
                ResultRow("Coût total", money(pay * n))
                ResultRow("Dont intérêts", money(pay * n - c))
            }
        }
        Section("Épargne (intérêts composés)") {
            Row2 { NumField(save, { save = it }, "Départ", Modifier.weight(1f)); NumField(monthly, { monthly = it }, "Par mois", Modifier.weight(1f)) }
            Row2 { NumField(saveRate, { saveRate = it }, "Taux annuel (%)", Modifier.weight(1f)); NumField(saveYears, { saveYears = it }, "Années", Modifier.weight(1f)) }
            val s0 = num(save) ?: 0.0; val pm = num(monthly) ?: 0.0; val r = num(saveRate) ?: 0.0; val y = num(saveYears) ?: 0.0
            val m = r / 100 / 12
            val n = y * 12
            val fv = if (m == 0.0) s0 + pm * n else s0 * (1 + m).pow(n) + pm * ((1 + m).pow(n) - 1) / m
            val put = s0 + pm * n
            ResultRow("Capital final", money(fv), big = true)
            ResultRow("Versé", money(put))
            ResultRow("Intérêts gagnés", money(fv - put), color = SE.Green)
        }
        Section("Salaire brut → net (estimation France)") {
            NumField(gross, { gross = it }, "Brut mensuel")
            val st = listOf("0.22" to "Non-cadre", "0.25" to "Cadre", "0.15" to "Fonction publique", "0.45" to "Indépendant")
            ChoiceChips(st, st.firstOrNull { it.first == status }, { it.second }) { status = it.first }
            val g = num(gross)
            if (g != null) {
                val net = g * (1 - status.toDouble())
                ResultRow("Net mensuel (avant impôt)", money(net), big = true)
                ResultRow("Net annuel", money(net * 12))
                ResultRow("Brut annuel", money(g * 12))
            }
            Hint("Estimation grossière : les cotisations réelles varient.")
        }
        Section("Taux horaire") {
            Row2 { NumField(hourly, { hourly = it }, "€ / heure", Modifier.weight(1f)); NumField(hours, { hours = it }, "Heures / semaine", Modifier.weight(1f)) }
            val h = num(hourly); val w = num(hours)
            if (h != null && w != null) {
                ResultRow("Par semaine", money(h * w))
                ResultRow("Par mois", money(h * w * 52 / 12), big = true)
                ResultRow("Par an", money(h * w * 52))
            }
        }
    }
}

// ------------------------------------------------------------------ Dates

private val dmy = DateTimeFormatter.ofPattern("d/M/yyyy")

private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim().replace('-', '/').replace('.', '/'), dmy) }.getOrNull()

private fun longDate(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)).replaceFirstChar { it.uppercase() }

@Composable
fun DatesScreen(onBack: () -> Unit) {
    val today = LocalDate.now()
    var d1 by rememberSaveable { mutableStateOf(today.format(dmy)) }
    var d2 by rememberSaveable { mutableStateOf(today.plusDays(100).format(dmy)) }
    var base by rememberSaveable { mutableStateOf(today.format(dmy)) }
    var add by rememberSaveable { mutableStateOf("30") }
    var unit by rememberSaveable { mutableStateOf("j") }
    var birth by rememberSaveable { mutableStateOf("1/1/2000") }

    ToolScaffold("Dates", onBack) {
        Hint("Format des dates : jour/mois/année (ex. 14/7/2026).")
        Section("Aujourd'hui") {
            ResultRow("Date", longDate(today))
            ResultRow("Semaine n°", today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString())
            ResultRow("Jour de l'année", "${today.dayOfYear} / ${today.lengthOfYear()}")
            ResultRow("Jours restants dans l'année", (today.lengthOfYear() - today.dayOfYear).toString())
        }
        Section("Écart entre deux dates") {
            Row2 { TextIn(d1, { d1 = it }, "Du", modifier = Modifier.weight(1f)); TextIn(d2, { d2 = it }, "Au", modifier = Modifier.weight(1f)) }
            val a = parseDate(d1); val b = parseDate(d2)
            if (a != null && b != null) {
                val days = ChronoUnit.DAYS.between(a, b)
                ResultRow("Jours", fmt(days.toDouble(), 0), big = true)
                ResultRow("Semaines", fmt(days / 7.0, 1))
                ResultRow("Mois", fmt(ChronoUnit.MONTHS.between(a, b).toDouble(), 0))
                var work = 0
                var d = if (a <= b) a else b
                val end = if (a <= b) b else a
                while (d < end && work < 100000) { if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) work++; d = d.plusDays(1) }
                ResultRow("Jours ouvrés (lun.–ven.)", work.toString())
            } else Hint("Date invalide.")
        }
        Section("Ajouter / retirer") {
            TextIn(base, { base = it }, "Date de départ")
            NumField(add, { add = it }, "Quantité (négatif pour retirer)", decimal = false)
            val units = listOf("j" to "Jours", "s" to "Semaines", "m" to "Mois", "a" to "Années")
            ChoiceChips(units, units.firstOrNull { it.first == unit }, { it.second }) { unit = it.first }
            val d = parseDate(base); val n = add.trim().toLongOrNull()
            if (d != null && n != null) {
                val r = when (unit) { "s" -> d.plusWeeks(n); "m" -> d.plusMonths(n); "a" -> d.plusYears(n); else -> d.plusDays(n) }
                ResultRow("Résultat", longDate(r), big = true)
            }
        }
        Section("Âge & anniversaire") {
            TextIn(birth, { birth = it }, "Date de naissance")
            val b = parseDate(birth)
            if (b != null && b <= today) {
                val y = ChronoUnit.YEARS.between(b, today)
                val m = ChronoUnit.MONTHS.between(b.plusYears(y), today)
                val dd = ChronoUnit.DAYS.between(b.plusYears(y).plusMonths(m), today)
                ResultRow("Âge", "$y ans, $m mois, $dd jours", big = true)
                ResultRow("Jours vécus", fmt(ChronoUnit.DAYS.between(b, today).toDouble(), 0))
                var next = b.withYear(today.year)
                if (next < today) next = next.plusYears(1)
                val until = ChronoUnit.DAYS.between(today, next)
                ResultRow("Prochain anniversaire", if (until == 0L) "Aujourd'hui !" else "dans $until jours")
                ResultRow("Né(e) un", b.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.FRANCE))
                ResultRow("Signe", zodiac(b))
            }
        }
    }
}

private fun zodiac(d: LocalDate): String {
    val md = d.monthValue * 100 + d.dayOfMonth
    return when {
        md >= 1222 || md < 120 -> "Capricorne"
        md < 219 -> "Verseau"
        md < 321 -> "Poissons"
        md < 420 -> "Bélier"
        md < 521 -> "Taureau"
        md < 621 -> "Gémeaux"
        md < 723 -> "Cancer"
        md < 823 -> "Lion"
        md < 923 -> "Vierge"
        md < 1023 -> "Balance"
        md < 1122 -> "Scorpion"
        else -> "Sagittaire"
    }
}

// ------------------------------------------------------------------ Santé

@Composable
fun HealthScreen(onBack: () -> Unit) {
    var weight by rememberSaveable { mutableStateOf("70") }
    var height by rememberSaveable { mutableStateOf("175") }
    var age by rememberSaveable { mutableStateOf("30") }
    var male by rememberSaveable { mutableStateOf(true) }
    var activity by rememberSaveable { mutableStateOf("1.375") }

    ToolScaffold("Santé", onBack) {
        Section("Toi") {
            Row2 { NumField(weight, { weight = it }, "Poids (kg)", Modifier.weight(1f)); NumField(height, { height = it }, "Taille (cm)", Modifier.weight(1f)) }
            NumField(age, { age = it }, "Âge", decimal = false)
            ChoiceChips(listOf(true, false), male, { if (it) "Homme" else "Femme" }) { male = it }
        }
        val w = num(weight); val h = num(height); val a = num(age)
        if (w != null && h != null && h > 0) {
            val m = h / 100
            val bmi = w / (m * m)
            val (label, color) = when {
                bmi < 18.5 -> "Maigreur" to SE.Orange
                bmi < 25 -> "Corpulence normale" to SE.Green
                bmi < 30 -> "Surpoids" to SE.Orange
                else -> "Obésité" to SE.Red
            }
            Section("IMC") {
                ResultRow("IMC", fmt(bmi, 1), big = true, color = color)
                ResultRow("Interprétation", label, color = color)
                ResultRow("Poids « normal » pour ta taille", "${fmt(18.5 * m * m, 0)} – ${fmt(24.9 * m * m, 0)} kg")
            }
            if (a != null) {
                val bmr = 10 * w + 6.25 * h - 5 * a + if (male) 5 else -161
                Section("Calories par jour") {
                    val acts = listOf("1.2" to "Sédentaire", "1.375" to "Peu actif", "1.55" to "Actif", "1.725" to "Très actif")
                    ChoiceChips(acts, acts.firstOrNull { it.first == activity }, { it.second }) { activity = it.first }
                    val tdee = bmr * activity.toDouble()
                    ResultRow("Métabolisme de base", fmt(bmr, 0) + " kcal")
                    ResultRow("Pour garder ton poids", fmt(tdee, 0) + " kcal", big = true)
                    ResultRow("Pour perdre (~0,5 kg/sem.)", fmt(tdee - 500, 0) + " kcal")
                    ResultRow("Pour prendre", fmt(tdee + 300, 0) + " kcal")
                }
                Section("Sport") {
                    val max = 220 - a
                    ResultRow("Fréquence cardiaque max", fmt(max, 0) + " bpm")
                    ResultRow("Endurance (60–70 %)", "${fmt(max * 0.6, 0)} – ${fmt(max * 0.7, 0)} bpm")
                    ResultRow("Cardio (70–85 %)", "${fmt(max * 0.7, 0)} – ${fmt(max * 0.85, 0)} bpm")
                }
            }
            Section("Eau") {
                ResultRow("À boire par jour", fmt(w * 0.033, 1) + " L", big = true)
                Hint("Environ 33 ml par kg, plus si tu fais du sport ou s'il fait chaud.")
            }
        }
        Hint("Ces calculs sont indicatifs et ne remplacent pas l'avis d'un médecin.")
    }
}

// ------------------------------------------------------------------ Petits calculs

@Composable
fun EverydayScreen(onBack: () -> Unit) {
    var km by rememberSaveable { mutableStateOf("350") }
    var cons by rememberSaveable { mutableStateOf("6,5") }
    var fuel by rememberSaveable { mutableStateOf("1,85") }
    var pass by rememberSaveable { mutableStateOf("1") }
    var p1 by rememberSaveable { mutableStateOf("2,49") }
    var q1 by rememberSaveable { mutableStateOf("500") }
    var p2 by rememberSaveable { mutableStateOf("3,99") }
    var q2 by rememberSaveable { mutableStateOf("1000") }
    var grades by rememberSaveable { mutableStateOf("12 x2\n15\n9,5 x3") }
    var on by rememberSaveable { mutableStateOf("20") }
    var servesFrom by rememberSaveable { mutableStateOf("4") }
    var servesTo by rememberSaveable { mutableStateOf("6") }
    var recipe by rememberSaveable { mutableStateOf("250 g farine\n3 oeufs\n50 cl lait") }
    var dist by rememberSaveable { mutableStateOf("10") }
    var time by rememberSaveable { mutableStateOf("55:00") }

    ToolScaffold("Petits calculs", onBack) {
        Section("Trajet en voiture") {
            NumField(km, { km = it }, "Distance (km)")
            Row2 { NumField(cons, { cons = it }, "L / 100 km", Modifier.weight(1f)); NumField(fuel, { fuel = it }, "Prix du litre", Modifier.weight(1f)) }
            NumField(pass, { pass = it }, "Personnes", decimal = false)
            val d = num(km); val c = num(cons); val f = num(fuel); val n = (num(pass) ?: 1.0).coerceAtLeast(1.0)
            if (d != null && c != null && f != null) {
                val l = d * c / 100
                ResultRow("Carburant", fmt(l, 1) + " L")
                ResultRow("Coût", money(l * f), big = true)
                if (n > 1) ResultRow("Par personne", money(l * f / n))
                ResultRow("Aller-retour", money(2 * l * f))
            }
        }
        Section("Quel produit est le moins cher ?") {
            Row2 { NumField(p1, { p1 = it }, "Prix A", Modifier.weight(1f)); NumField(q1, { q1 = it }, "Quantité A (g, ml…)", Modifier.weight(1f)) }
            Row2 { NumField(p2, { p2 = it }, "Prix B", Modifier.weight(1f)); NumField(q2, { q2 = it }, "Quantité B", Modifier.weight(1f)) }
            val a = num(p1)?.let { p -> num(q1)?.takeIf { it > 0 }?.let { p / it * 1000 } }
            val b = num(p2)?.let { p -> num(q2)?.takeIf { it > 0 }?.let { p / it * 1000 } }
            if (a != null) ResultRow("A au kilo / litre", money(a), color = if (b != null && a <= b) SE.Green else SE.Text)
            if (b != null) ResultRow("B au kilo / litre", money(b), color = if (a != null && b <= a) SE.Green else SE.Text)
            if (a != null && b != null && a != b) ResultRow("Économie avec le moins cher", fmt(abs(a - b) / maxOf(a, b) * 100, 0) + " %")
        }
        Section("Moyenne (avec coefficients)") {
            TextIn(grades, { grades = it }, "Une note par ligne, « x2 » pour le coefficient", minLines = 3)
            NumField(on, { on = it }, "Noté sur")
            val parsed = grades.lines().mapNotNull { l ->
                val parts = l.lowercase().split('x', '*', '×').map { it.trim() }
                val g = num(parts.getOrElse(0) { "" }) ?: return@mapNotNull null
                g to (parts.getOrNull(1)?.let { num(it) } ?: 1.0)
            }
            val sw = parsed.sumOf { it.second }
            if (sw > 0) {
                val avg = parsed.sumOf { it.first * it.second } / sw
                ResultRow("Moyenne", fmt(avg) + " / " + on, big = true)
                ResultRow("Notes", parsed.size.toString())
                num(on)?.takeIf { it != 20.0 && it > 0 }?.let { ResultRow("Sur 20", fmt(avg / it * 20)) }
            }
        }
        Section("Adapter une recette") {
            Row2 { NumField(servesFrom, { servesFrom = it }, "Recette pour", Modifier.weight(1f)); NumField(servesTo, { servesTo = it }, "Je veux pour", Modifier.weight(1f)) }
            TextIn(recipe, { recipe = it }, "Ingrédients (un par ligne)", minLines = 3)
            val f = num(servesTo)?.let { t -> num(servesFrom)?.takeIf { it > 0 }?.let { t / it } }
            if (f != null) {
                val out = recipe.lines().filter { it.isNotBlank() }.joinToString("\n") { line ->
                    Regex("^\\s*(\\d+(?:[.,]\\d+)?)").find(line)?.let { m ->
                        val v = num(m.groupValues[1])!! * f
                        line.replaceRange(m.range, fmt(v, 1))
                    } ?: line
                }
                Text(out, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }
        Section("Allure de course") {
            Row2 { NumField(dist, { dist = it }, "Distance (km)", Modifier.weight(1f)); TextIn(time, { time = it }, "Temps (h:mm:ss)", modifier = Modifier.weight(1f)) }
            val d = num(dist)
            val secs = time.split(':').mapNotNull { num(it) }.fold(0.0) { acc, x -> acc * 60 + x }
            if (d != null && d > 0 && secs > 0) {
                val pace = secs / d
                ResultRow("Allure", "%d:%02d / km".format((pace / 60).toInt(), (pace % 60).roundToLong()), big = true)
                ResultRow("Vitesse", fmt(d / (secs / 3600), 1) + " km/h")
                ResultRow("Marathon à cette allure", hms(pace * 42.195))
            }
        }
    }
}

private fun hms(s: Double): String {
    val t = s.roundToLong()
    return "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
}

// ------------------------------------------------------------------ Nombres

@Composable
fun BasesScreen(onBack: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("255") }
    var base by rememberSaveable { mutableStateOf(10) }
    var roman by rememberSaveable { mutableStateOf("MMXXVI") }
    var words by rememberSaveable { mutableStateOf("1984") }
    var gcdA by rememberSaveable { mutableStateOf("12") }
    var gcdB by rememberSaveable { mutableStateOf("18") }
    var prime by rememberSaveable { mutableStateOf("97") }

    ToolScaffold("Nombres", onBack) {
        Section("Changer de base") {
            ChoiceChips(listOf(2, 8, 10, 16), base, { when (it) { 2 -> "Binaire"; 8 -> "Octal"; 16 -> "Hexa"; else -> "Décimal" } }) { base = it }
            TextIn(input, { input = it }, "Nombre")
            val v = input.trim().replace(" ", "").toLongOrNull(base)
            if (v != null) {
                ResultRow("Décimal", v.toString(), big = base != 10)
                ResultRow("Binaire", java.lang.Long.toBinaryString(v))
                ResultRow("Octal", java.lang.Long.toOctalString(v))
                ResultRow("Hexadécimal", java.lang.Long.toHexString(v).uppercase())
                if (v in 32..126) ResultRow("Caractère ASCII", v.toInt().toChar().toString())
            } else if (input.isNotBlank()) Hint("Ce n'est pas un nombre valide dans cette base.")
        }
        Section("Chiffres romains") {
            TextIn(roman, { roman = it }, "Romain ou nombre (1 à 3999)")
            val n = roman.trim().toIntOrNull()
            if (n != null) ResultRow("En romain", Numbers.toRoman(n), big = true)
            else Numbers.fromRoman(roman)?.let { ResultRow("En chiffres", it.toString(), big = true) } ?: Hint("Saisie invalide.")
        }
        Section("En toutes lettres") {
            NumField(words, { words = it }, "Nombre entier", decimal = false)
            words.trim().toLongOrNull()?.let { ResultRow("", Numbers.inWords(it)) }
            Hint("Pratique pour remplir un chèque.")
        }
        Section("PGCD & PPCM") {
            Row2 { NumField(gcdA, { gcdA = it }, "a", Modifier.weight(1f), decimal = false); NumField(gcdB, { gcdB = it }, "b", Modifier.weight(1f), decimal = false) }
            val a = gcdA.trim().toLongOrNull(); val b = gcdB.trim().toLongOrNull()
            if (a != null && b != null && a > 0 && b > 0) {
                var x = a; var y = b
                while (y != 0L) { val t = x % y; x = y; y = t }
                ResultRow("PGCD", x.toString())
                ResultRow("PPCM", (a / x * b).toString())
            }
        }
        Section("Nombre premier ?") {
            NumField(prime, { prime = it }, "Nombre", decimal = false)
            prime.trim().toLongOrNull()?.takeIf { it > 0 }?.let { n ->
                val factors = ArrayList<Long>()
                var x = n; var d = 2L
                while (d * d <= x && factors.size < 64) { while (x % d == 0L) { factors += d; x /= d }; d++ }
                if (x > 1) factors += x
                ResultRow("Premier", if (n > 1 && factors.size == 1) "Oui" else "Non", big = true)
                if (n > 1) ResultRow("Décomposition", factors.joinToString(" × "))
            }
        }
    }
}
