package com.superessence.app.tools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.text.Normalizer

/** Transformations de texte supplémentaires (écran « Texte »). */
object TextOps {

    private fun words(s: String): List<String> =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }

    private val morse = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..",
        'J' to ".---", 'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-", '5' to ".....", '6' to "-....", '7' to "--...",
        '8' to "---..", '9' to "----.", '.' to ".-.-.-", ',' to "--..--", '?' to "..--..", '!' to "-.-.--", '\'' to ".----.", '/' to "-..-.",
        '(' to "-.--.", ')' to "-.--.-", ':' to "---...", '=' to "-...-", '+' to ".-.-.", '-' to "-....-", '@' to ".--.-.",
    )
    private val unmorse = morse.entries.associate { (k, v) -> v to k }

    private fun digest(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun rot13(s: String) = s.map { c ->
        when (c) {
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            else -> c
        }
    }.joinToString("")

    private val flip = mapOf(
        'a' to 'ɐ', 'b' to 'q', 'c' to 'ɔ', 'd' to 'p', 'e' to 'ǝ', 'f' to 'ɟ', 'g' to 'ƃ', 'h' to 'ɥ', 'i' to 'ᴉ', 'j' to 'ɾ', 'k' to 'ʞ',
        'l' to 'l', 'm' to 'ɯ', 'n' to 'u', 'o' to 'o', 'p' to 'd', 'q' to 'b', 'r' to 'ɹ', 's' to 's', 't' to 'ʇ', 'u' to 'n', 'v' to 'ʌ',
        'w' to 'ʍ', 'x' to 'x', 'y' to 'ʎ', 'z' to 'z', '.' to '˙', ',' to '\'', '?' to '¿', '!' to '¡', '(' to ')', ')' to '(',
    )

    val all: List<Pair<String, (String) -> String>> = listOf(
        "Phrase normale" to { s -> s.lowercase().split(Regex("(?<=[.!?]\\s)")).joinToString("") { it.replaceFirstChar { c -> c.titlecase() } } },
        "aLtErNé" to { s -> s.mapIndexed { i, c -> if (i % 2 == 0) c.lowercaseChar() else c.uppercaseChar() }.joinToString("") },
        "camelCase" to { s -> words(s).mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar { it.uppercase() } }.joinToString("") },
        "snake_case" to { s -> words(s).joinToString("_") { it.lowercase() } },
        "kebab-case / slug" to { s -> words(s).joinToString("-") { it.lowercase() } },
        "Inverser les mots" to { s -> s.lines().joinToString("\n") { it.split(" ").reversed().joinToString(" ") } },
        "Mélanger les lignes" to { s -> s.lines().shuffled().joinToString("\n") },
        "Inverser les lignes" to { s -> s.lines().reversed().joinToString("\n") },
        "Trier par longueur" to { s -> s.lines().sortedBy { it.length }.joinToString("\n") },
        "Trier Z → A" to { s -> s.lines().sortedDescending().joinToString("\n") },
        "Numéroter les lignes" to { s -> s.lines().mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n") },
        "Retirer lignes vides" to { s -> s.lines().filter { it.isNotBlank() }.joinToString("\n") },
        "Une seule ligne" to { s -> s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") },
        "Une ligne par mot" to { s -> s.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("\n") },
        "Mots les plus fréquents" to { s ->
            s.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length > 2 }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(30).joinToString("\n") { "${it.value}× ${it.key}" }
        },
        "Somme des nombres" to { s ->
            val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(s).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
            if (nums.isEmpty()) "Aucun nombre" else "Nombres : ${nums.size}\nSomme : ${nums.sum()}\nMoyenne : ${nums.average()}\nMin : ${nums.min()}\nMax : ${nums.max()}"
        },
        "Extraire les nombres" to { s -> Regex("-?\\d+(?:[.,]\\d+)?").findAll(s).joinToString("\n") { it.value } },
        "Extraire les numéros de tél." to { s -> Regex("(\\+?\\d[\\d .-]{7,}\\d)").findAll(s).joinToString("\n") { it.value.trim() } },
        "Retirer les balises HTML" to { s -> s.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">") },
        "Échapper en HTML" to { s -> s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;") },
        "JSON lisible" to { s ->
            when (val v = JSONTokener(s.trim()).nextValue()) {
                is JSONObject -> v.toString(2)
                is JSONArray -> v.toString(2)
                else -> "JSON invalide"
            }
        },
        "JSON compact" to { s ->
            when (val v = JSONTokener(s.trim()).nextValue()) {
                is JSONObject -> v.toString()
                is JSONArray -> v.toString()
                else -> "JSON invalide"
            }
        },
        "Texte → morse" to { s -> s.uppercase().split(Regex("\\s+")).joinToString(" / ") { w -> w.mapNotNull { morse[it] }.joinToString(" ") } },
        "Morse → texte" to { s -> s.trim().split(Regex("\\s*/\\s*|\\s{3,}")).joinToString(" ") { w -> w.trim().split(Regex("\\s+")).mapNotNull { unmorse[it] }.joinToString("") } },
        "Texte → binaire" to { s -> s.toByteArray().joinToString(" ") { String.format("%8s", Integer.toBinaryString(it.toInt() and 255)).replace(' ', '0') } },
        "Binaire → texte" to { s -> String(s.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(2)?.toByte() }.toByteArray()) },
        "Texte → hexadécimal" to { s -> s.toByteArray().joinToString(" ") { "%02x".format(it) } },
        "Hexadécimal → texte" to { s -> String(s.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()) },
        "ROT13" to { s -> rot13(s) },
        "À l'envers (ʇxǝʇ)" to { s -> s.lowercase().reversed().map { flip[it] ?: it }.joinToString("") },
        "MD5" to { s -> digest("MD5", s) },
        "SHA-256" to { s -> digest("SHA-256", s) },
        "Compter les caractères" to { s ->
            "Caractères : ${s.length}\nSans espaces : ${s.count { !it.isWhitespace() }}\nLettres : ${s.count { it.isLetter() }}\nChiffres : ${s.count { it.isDigit() }}\n" +
                "Mots : ${s.split(Regex("\\s+")).count { it.isNotBlank() }}\nPhrases : ${s.split(Regex("[.!?]+")).count { it.isNotBlank() }}\n" +
                "Paragraphes : ${s.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }}"
        },
        "Supprimer les emojis" to { s -> s.replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "") },
        "Guillemets français « »" to { s -> s.replace(Regex("\"([^\"]*)\""), "« $1 »") },
    )

    private val loremWords = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore et dolore magna aliqua " +
        "enim ad minim veniam quis nostrud exercitation ullamco laboris nisi aliquip ex ea commodo consequat duis aute irure in reprehenderit " +
        "voluptate velit esse cillum fugiat nulla pariatur excepteur sint occaecat cupidatat non proident sunt culpa qui officia deserunt mollit anim id est laborum").split(' ')

    fun lorem(paragraphs: Int): String = (1..paragraphs).joinToString("\n\n") { p ->
        (1..(4 + p % 3)).joinToString(" ") {
            val n = (8..16).random()
            (1..n).map { loremWords.random() }.joinToString(" ").replaceFirstChar { c -> c.uppercase() } + "."
        }.let { if (p == 1) "Lorem ipsum dolor sit amet, " + it.replaceFirstChar { c -> c.lowercase() } else it }
    }
}
