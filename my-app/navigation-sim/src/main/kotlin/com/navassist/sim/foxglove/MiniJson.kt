package com.navassist.sim.foxglove

/**
 * A tiny JSON reader for the handful of small control messages a Foxglove client sends
 * (subscribe, unsubscribe, advertise, a Twist). Objects become maps, arrays lists, numbers Double.
 */
object MiniJson {

    fun parse(text: String): Any? = Parser(text).run { value().also { skipWhitespace() } }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWhitespace()
            require(i < s.length) { "unexpected end of JSON" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                expect(':')
                out[key] = value()
                skipWhitespace()
                if (s[i] == ',') { i++; continue }
                expect('}')
                return out
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out += value()
                skipWhitespace()
                if (s[i] == ',') { i++; continue }
                expect(']')
                return out
            }
        }

        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { sb.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
            }
            i++
            return sb.toString()
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad JSON literal at $i" }
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            require(i < s.length && s[i] == c) { "expected '$c' at $i" }
            i++
        }
    }
}
