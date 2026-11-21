package com.superessence.app.tools

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** Évalue une expression : + − × ÷ ^ % √ ( ), sin cos tan ln log abs, pi, e, ! */
object Calc {
    fun eval(expr: String, degrees: Boolean = true): Double = Parser(normalize(expr), degrees).parse()

    private fun normalize(s: String) = s.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.')
        .replace("π", "pi").replace(" ", "").lowercase()

    private class Parser(val s: String, val deg: Boolean) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            if (i < s.length) error("Caractère inattendu : ${s[i]}")
            return v
        }

        fun peek() = if (i < s.length) s[i] else '\u0000'

        fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        fun term(): Double {
            var v = power()
            while (true) {
                when (peek()) {
                    '*' -> { i++; v *= power() }
                    '/' -> { i++; v /= power() }
                    '(' , 'p', 's', 'c', 't', 'l', 'a', 'e', '√' -> v *= power() // multiplication implicite : 2(3), 2pi
                    else -> return v
                }
            }
        }

        fun power(): Double {
            val b = unary()
            return if (peek() == '^') { i++; b.pow(power()) } else b
        }

        fun unary(): Double = when (peek()) {
            '-' -> { i++; -unary() }
            '+' -> { i++; unary() }
            else -> postfix(atom())
        }

        fun postfix(x: Double): Double {
            var v = x
            while (true) {
                when (peek()) {
                    '%' -> { i++; v /= 100 }
                    '!' -> { i++; v = fact(v) }
                    else -> return v
                }
            }
        }

        fun fact(n: Double): Double {
            if (n < 0 || n != floor(n) || n > 170) error("Factorielle impossible")
            var r = 1.0
            for (k in 2..n.toInt()) r *= k
            return r
        }

        fun atom(): Double {
            val c = peek()
            if (c == '(') {
                i++
                val v = expr()
                if (peek() == ')') i++
                return v
            }
            if (c == '√') { i++; return kotlin.math.sqrt(unary()) }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                if (i < s.length && s[i] == 'e' && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-')) {
                    i += 2
                    while (i < s.length && s[i].isDigit()) i++
                }
                return s.substring(start, i).toDoubleOrNull() ?: error("Nombre invalide")
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && s[i].isLetter()) i++
                val name = s.substring(start, i)
                val toRad = { x: Double -> if (deg) x * PI / 180 else x }
                return when (name) {
                    "pi" -> PI
                    "e" -> E
                    "sqrt", "racine" -> kotlin.math.sqrt(unary())
                    "sin" -> clean(kotlin.math.sin(toRad(unary())))
                    "cos" -> clean(kotlin.math.cos(toRad(unary())))
                    "tan" -> clean(kotlin.math.tan(toRad(unary())))
                    "ln" -> kotlin.math.ln(unary())
                    "log" -> kotlin.math.log10(unary())
                    "abs" -> abs(unary())
                    else -> error("Fonction inconnue : $name")
                }
            }
            error("Expression incomplète")
        }

        fun clean(v: Double) = if (abs(v) < 1e-12) 0.0 else v
    }
}

/** Nombres : bases, chiffres romains, écriture en lettres. */
object Numbers {
    fun toRoman(n: Int): String {
        if (n !in 1..3999) return "—"
        val vals = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n
        return buildString { for ((v, r) in vals) while (x >= v) { append(r); x -= v } }
    }

    fun fromRoman(s: String): Int? {
        val m = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        val u = s.trim().uppercase()
        if (u.isEmpty() || u.any { it !in m }) return null
        var total = 0
        for (k in u.indices) {
            val v = m[u[k]]!!
            val next = if (k + 1 < u.length) m[u[k + 1]]!! else 0
            total += if (v < next) -v else v
        }
        return if (toRoman(total) == u) total else null
    }

    private val units = listOf("zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize")
    private val tens = listOf("", "dix", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante", "quatre-vingt", "quatre-vingt")

    private fun below100(n: Int): String {
        if (n <= 16) return units[n]
        if (n < 20) return "dix-" + units[n - 10]
        val t = n / 10
        val u = n % 10
        return when {
            t == 7 || t == 9 -> tens[t] + (if (t == 7 && u == 1) " et " else "-") + below100(10 + u)
            u == 0 -> if (t == 8) "quatre-vingts" else tens[t]
            u == 1 && t != 8 -> tens[t] + " et un"
            else -> tens[t] + "-" + units[u]
        }
    }

    private fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        val head = when (h) {
            0 -> ""
            1 -> "cent"
            else -> units[h] + (if (r == 0) " cents" else " cent")
        }
        return listOf(head, if (r > 0 || h == 0) below100(r) else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Écrit un entier en toutes lettres (français). */
    fun inWords(n: Long): String {
        if (n < 0) return "moins " + inWords(-n)
        if (n < 1000) return below1000(n.toInt())
        val parts = ArrayList<String>()
        var x = n
        val scales = listOf("" to "", "mille" to "mille", "million" to "millions", "milliard" to "milliards", "billion" to "billions")
        var k = 0
        while (x > 0 && k < scales.size) {
            val chunk = (x % 1000).toInt()
            if (chunk > 0) {
                val (one, many) = scales[k]
                parts += when {
                    k == 0 -> below1000(chunk)
                    k == 1 -> if (chunk == 1) "mille" else below1000(chunk).removeSuffix("s").let { if (it.endsWith("cent") || it.endsWith("vingt")) it else it } + " mille"
                    else -> below1000(chunk) + " " + if (chunk == 1) one else many
                }
            }
            x /= 1000; k++
        }
        return parts.reversed().joinToString(" ")
    }
}
