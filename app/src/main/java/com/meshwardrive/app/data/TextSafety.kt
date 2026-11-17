package com.meshwardrive.app.data

/**
 * Limpieza de texto que llega de fuera (nombres de nodo por radio, archivos importados, ajustes).
 * Quita caracteres de control y de cambio de dirección (que permiten disfrazar texto),
 * y limita la longitud. Kotlin puro.
 */
object TextSafety {

    private fun isUnsafe(c: Char): Boolean {
        val code = c.code
        return code < 0x20 || code in 0x7F..0x9F ||
            code in 0x200B..0x200F ||   // espacios de anchura cero y marcas de dirección
            code in 0x202A..0x202E ||   // incrustaciones y anulaciones de dirección
            code in 0x2066..0x2069 ||   // aislamientos de dirección
            code == 0xFEFF               // BOM
    }

    fun clean(s: String?, maxLen: Int): String? {
        if (s == null) return null
        val sb = StringBuilder(minOf(s.length, maxLen))
        for (c in s) {
            if (sb.length >= maxLen) break
            if (!isUnsafe(c)) sb.append(c)
        }
        // No dejar medio par sustituto al cortar
        if (sb.isNotEmpty() && Character.isHighSurrogate(sb[sb.length - 1])) sb.setLength(sb.length - 1)
        return sb.toString().trim().ifEmpty { null }
    }

    /** Texto del ping: una palabra sin espacios, solo letras, números y algunos signos. */
    fun pingPrefix(s: String?): String {
        val cleaned = clean(s, 10)?.filter { it.isLetterOrDigit() || it in "-_#!*+." }.orEmpty()
        return cleaned.ifEmpty { "WD" }
    }

    /**
     * Celda segura para CSV: si un texto empieza por =, +, -, @, tabulador o retorno de carro,
     * Excel y LibreOffice pueden ejecutarlo como fórmula. Se antepone un apóstrofo.
     * Los números (incluidos los negativos) se dejan tal cual.
     */
    fun csvCell(s: String): String {
        if (s.isEmpty()) return s
        val first = s[0]
        val risky = first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
        if (risky && s.toDoubleOrNull() == null) return "'$s"
        return s
    }

    /** Texto para globos del mapa de osmdroid, que interpretan HTML: se escapa y se respetan los saltos de línea. */
    fun htmlText(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                '\n' -> sb.append("<br>")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
