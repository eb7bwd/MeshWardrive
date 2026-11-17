package com.meshwardrive.app.data

/**
 * Lector JSON estricto (RFC 8259) para archivos que vienen de otras personas.
 * Rechaza comentarios, comas sobrantes, comillas simples, claves repetidas, NaN/Infinity
 * y cualquier texto tras el valor principal. Limita profundidad, longitud de textos y
 * número total de elementos para que un archivo malicioso no agote la memoria. Kotlin puro.
 */
class MiniJson private constructor(
    private val s: String,
    private val maxDepth: Int,
    private val maxString: Int,
    private val maxElements: Int,
) {
    class JsonException(msg: String) : Exception(msg)

    private var i = 0
    private var elements = 0

    companion object {
        fun parse(
            text: String,
            maxDepth: Int = 8,
            maxString: Int = 4_096,
            maxElements: Int = 5_000_000,
        ): Any? {
            val p = MiniJson(text, maxDepth, maxString, maxElements)
            p.ws()
            val v = p.value(0)
            p.ws()
            if (p.i != text.length) p.fail("unexpected content after the JSON value")
            return v
        }
    }

    private fun fail(msg: String): Nothing = throw JsonException("$msg at position $i")

    private fun count() {
        if (++elements > maxElements) fail("too many elements")
    }

    private fun ws() {
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else return
        }
    }

    private fun value(depth: Int): Any? {
        if (depth > maxDepth) fail("nesting too deep")
        if (i >= s.length) fail("unexpected end")
        count()
        return when (val c = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected character")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, i)) fail("invalid literal")
        i += word.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        i++ // {
        val m = LinkedHashMap<String, Any?>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return m }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected a key")
            val k = str()
            if (m.containsKey(k)) fail("duplicate key")
            ws()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            ws()
            m[k] = value(depth + 1)
            ws()
            if (i >= s.length) fail("unexpected end")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return m }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        i++ // [
        val l = ArrayList<Any?>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return l }
        while (true) {
            ws()
            l.add(value(depth + 1))
            ws()
            if (i >= s.length) fail("unexpected end")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return l }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun str(): String {
        i++ // "
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("unterminated escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad unicode escape")
                            val h = s.substring(i, i + 4)
                            if (!h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad unicode escape")
                            sb.append(h.toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                c.code < 0x20 -> fail("control character in string")
                else -> sb.append(c)
            }
            if (sb.length > maxString) fail("string too long")
        }
    }

    private fun num(): Number {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("bad number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else fail("bad number")
        var isInt = true
        if (i < s.length && s[i] == '.') {
            isInt = false
            i++
            if (i >= s.length || s[i] !in '0'..'9') fail("bad number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            isInt = false
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || s[i] !in '0'..'9') fail("bad number")
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val t = s.substring(start, i)
        if (t.length > 32) fail("number too long")
        if (isInt) t.toLongOrNull()?.let { return it }
        val d = t.toDouble()
        if (d.isNaN() || d.isInfinite()) fail("number out of range")
        return d
    }
}
