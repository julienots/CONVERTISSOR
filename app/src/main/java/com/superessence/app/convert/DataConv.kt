package com.superessence.app.convert

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Conversions entre formats texte structurés : JSON, CSV, XML, Markdown, HTML, Word (DOCX). */
object DataConv {

    // ---------------------------------------------------------------- détection / texte brut

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun plain(raw: String, ext: String): String = when (ext) {
        "html", "htm" -> Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        "md" -> Html.fromHtml(markdownToHtml(raw), Html.FROM_HTML_MODE_LEGACY).toString().trim()
        else -> raw
    }

    /** Texte mis en forme (titres, gras…) pour le PDF. */
    fun forPdf(raw: String, ext: String): CharSequence = when (ext) {
        "html", "htm" -> Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
        "md" -> Html.fromHtml(markdownToHtml(raw), Html.FROM_HTML_MODE_LEGACY)
        "csv" -> plain(csvToHtml(raw), "html")
        else -> raw
    }

    /** Convertit [raw] (format [from]) vers [to] (txt, html, json, csv, xml, md). */
    fun convert(raw: String, from: String, to: String): String = when (to) {
        "txt" -> plain(raw, from)
        "html" -> when (from) {
            "md" -> wrapHtml(markdownToHtml(raw))
            "csv" -> wrapHtml(csvToHtml(raw))
            "json" -> wrapHtml("<pre>" + esc(prettyJson(raw)) + "</pre>")
            "", "txt" -> wrapHtml(raw.lines().filter { it.isNotBlank() }.joinToString("\n") { "<p>${esc(it)}</p>" })
            else -> wrapHtml("<pre>" + esc(raw) + "</pre>")
        }
        "json" -> when (from) {
            "csv" -> csvToJson(raw)
            "xml" -> xmlToJson(raw)
            else -> prettyJson(raw)
        }
        "csv" -> jsonToCsv(raw)
        "xml" -> when (from) {
            "csv" -> jsonToXml(csvToJson(raw))
            else -> jsonToXml(raw)
        }
        "md" -> plain(raw, from)
        else -> raw
    }

    private fun wrapHtml(body: String) =
        "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{font-family:sans-serif;max-width:800px;margin:2em auto;padding:0 1em;line-height:1.5}" +
            "table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}code,pre{background:#eee}pre{padding:8px;overflow:auto}</style>" +
            "</head><body>\n$body\n</body></html>\n"

    // ---------------------------------------------------------------- Markdown

    private fun inline(s: String): String {
        var t = esc(s)
        t = Regex("`([^`]+)`").replace(t) { "<code>${it.groupValues[1]}</code>" }
        t = Regex("\\*\\*([^*]+)\\*\\*").replace(t) { "<b>${it.groupValues[1]}</b>" }
        t = Regex("__([^_]+)__").replace(t) { "<b>${it.groupValues[1]}</b>" }
        t = Regex("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?!\\*)").replace(t) { "<i>${it.groupValues[1]}</i>" }
        t = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)").replace(t) { "<img alt=\"${it.groupValues[1]}\" src=\"${it.groupValues[2]}\">" }
        t = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(t) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return t
    }

    fun markdownToHtml(md: String): String {
        val out = StringBuilder()
        var list: String? = null
        var inCode = false
        val para = ArrayList<String>()
        fun flushPara() {
            if (para.isNotEmpty()) {
                out.append("<p>").append(para.joinToString("<br>") { inline(it) }).append("</p>\n")
                para.clear()
            }
        }
        fun closeList() {
            list?.let { out.append("</$it>\n") }
            list = null
        }
        for (line in md.lines()) {
            if (line.trimStart().startsWith("```")) {
                flushPara(); closeList()
                out.append(if (inCode) "</pre>\n" else "<pre>")
                inCode = !inCode
                continue
            }
            if (inCode) { out.append(esc(line)).append('\n'); continue }
            val t = line.trim()
            val h = Regex("^(#{1,6})\\s+(.*)$").find(t)
            val ul = Regex("^[-*+]\\s+(.*)$").find(t)
            val ol = Regex("^\\d+[.)]\\s+(.*)$").find(t)
            when {
                t.isEmpty() -> { flushPara(); closeList() }
                h != null -> {
                    flushPara(); closeList()
                    val n = h.groupValues[1].length
                    out.append("<h$n>").append(inline(h.groupValues[2])).append("</h$n>\n")
                }
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flushPara(); closeList(); out.append("<hr>\n") }
                t.startsWith(">") -> { flushPara(); closeList(); out.append("<blockquote>").append(inline(t.removePrefix(">").trim())).append("</blockquote>\n") }
                ul != null || ol != null -> {
                    flushPara()
                    val kind = if (ul != null) "ul" else "ol"
                    if (list != kind) { closeList(); out.append("<$kind>\n"); list = kind }
                    out.append("<li>").append(inline((ul ?: ol)!!.groupValues[1])).append("</li>\n")
                }
                else -> { closeList(); para += t }
            }
        }
        flushPara(); closeList()
        if (inCode) out.append("</pre>\n")
        return out.toString()
    }

    // ---------------------------------------------------------------- CSV

    fun csvParse(text: String): List<List<String>> {
        val src = text.removePrefix("﻿")
        val first = src.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t').maxBy { d -> first.count { it == d } }
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                quoted -> if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
                c == '"' -> quoted = true
                c == delim -> { row.add(cell.toString()); cell.clear() }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < src.length && src[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.clear()
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }

    private fun csvCell(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun csvToHtml(raw: String): String {
        val rows = csvParse(raw)
        val sb = StringBuilder("<table>\n")
        rows.forEachIndexed { i, r ->
            sb.append("<tr>")
            r.forEach { sb.append(if (i == 0) "<th>" else "<td>").append(esc(it)).append(if (i == 0) "</th>" else "</td>") }
            sb.append("</tr>\n")
        }
        return sb.append("</table>").toString()
    }

    fun csvToJson(raw: String): String {
        val rows = csvParse(raw)
        if (rows.isEmpty()) return "[]"
        val head = rows[0].mapIndexed { i, h -> h.ifBlank { "col${i + 1}" } }
        val arr = JSONArray()
        rows.drop(1).forEach { r ->
            val o = JSONObject()
            head.forEachIndexed { i, h -> o.put(h, r.getOrElse(i) { "" }) }
            arr.put(o)
        }
        return arr.toString(2)
    }

    // ---------------------------------------------------------------- JSON

    private fun parseJson(raw: String): Any = JSONTokener(raw.trim().removePrefix("﻿")).nextValue()

    fun prettyJson(raw: String): String = when (val v = parseJson(raw)) {
        is JSONObject -> v.toString(2)
        is JSONArray -> v.toString(2)
        else -> v.toString()
    }

    private fun flat(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> v.toString()
        else -> v.toString()
    }

    fun jsonToCsv(raw: String): String {
        val root = parseJson(raw)
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> {
                // Un objet contenant une seule liste : on prend la liste
                val lists = root.keys().asSequence().filter { root.get(it) is JSONArray }.toList()
                if (lists.size == 1) root.getJSONArray(lists[0]) else JSONArray().put(root)
            }
            else -> error("JSON sans tableau d'objets")
        }
        val sb = StringBuilder()
        if (arr.length() > 0 && arr.get(0) is JSONArray) {
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                sb.append((0 until r.length()).joinToString(",") { csvCell(flat(r.get(it))) }).append('\n')
            }
            return sb.toString()
        }
        val keys = LinkedHashSet<String>()
        for (i in 0 until arr.length()) (arr.opt(i) as? JSONObject)?.keys()?.forEach { keys.add(it) }
        if (keys.isEmpty()) {
            for (i in 0 until arr.length()) sb.append(csvCell(flat(arr.get(i)))).append('\n')
            return sb.toString()
        }
        sb.append(keys.joinToString(",") { csvCell(it) }).append('\n')
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(keys.joinToString(",") { csvCell(flat(o.opt(it))) }).append('\n')
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- XML

    private fun tag(s: String): String {
        val t = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return if (t.isEmpty() || !(t[0].isLetter() || t[0] == '_')) "_$t" else t
    }

    private fun xmlNode(sb: StringBuilder, name: String, v: Any?, indent: String) {
        val n = tag(name)
        when (v) {
            is JSONObject -> {
                sb.append(indent).append('<').append(n).append(">\n")
                v.keys().forEach { xmlNode(sb, it, v.get(it), "$indent  ") }
                sb.append(indent).append("</").append(n).append(">\n")
            }
            is JSONArray -> for (i in 0 until v.length()) xmlNode(sb, name, v.get(i), indent)
            else -> sb.append(indent).append('<').append(n).append('>').append(esc(flat(v))).append("</").append(n).append(">\n")
        }
    }

    fun jsonToXml(raw: String): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        when (val root = parseJson(raw)) {
            is JSONObject -> xmlNode(sb, "root", root, "")
            is JSONArray -> {
                sb.append("<root>\n")
                for (i in 0 until root.length()) xmlNode(sb, "item", root.get(i), "  ")
                sb.append("</root>\n")
            }
            else -> xmlNode(sb, "root", root, "")
        }
        return sb.toString()
    }

    private fun elementToJson(e: Element): Any {
        val attrs = e.attributes
        val children = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()
        val text = (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filter { it.nodeType == Node.TEXT_NODE || it.nodeType == Node.CDATA_SECTION_NODE }
            .joinToString("") { it.nodeValue }.trim()
        if (children.isEmpty() && attrs.length == 0) return text
        val o = JSONObject()
        for (i in 0 until attrs.length) o.put("@" + attrs.item(i).nodeName, attrs.item(i).nodeValue)
        children.groupBy { it.nodeName }.forEach { (k, list) ->
            if (list.size == 1) o.put(k, elementToJson(list[0]))
            else o.put(k, JSONArray().also { a -> list.forEach { a.put(elementToJson(it)) } })
        }
        if (text.isNotEmpty()) o.put("#text", text)
        return o
    }

    fun xmlToJson(raw: String): String {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))
        val root = doc.documentElement
        val o = JSONObject()
        o.put(root.nodeName, elementToJson(root))
        return o.toString(2)
    }

    // ---------------------------------------------------------------- Word (DOCX)

    /** Écrit un .docx minimal : un paragraphe par ligne. */
    fun textToDocx(text: String, out: OutputStream) {
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "</Types>",
            )
            put(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>",
            )
            val body = StringBuilder()
            text.replace("\r", "").split('\n').forEach { line ->
                val clean = line.filter { it == '\t' || it.code >= 0x20 }
                body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(clean).replace("&quot;", "\"")).append("</w:t></w:r></w:p>")
            }
            put(
                "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>",
            )
        }
    }
}
