package com.daymark.synccrypto

/**
 * The JSON reader the key documents are read with ([KeyDocument]): RFC 8259 and nothing more, which
 * is what the web's `JSON.parse` accepts, so the phone reads a document exactly when the web can.
 *
 * It is written out here instead of borrowed, for three things a key document needs and the JSON
 * library the app already ships (kotlinx-serialization-json 1.7.3) does not keep:
 *  - It refuses what `JSON.parse` refuses. That library's tree reader takes a bare word, `+256`,
 *    `0x10`, `01` or `'x'` as a value, where the web refuses the whole document.
 *  - A refusal says nothing about what was read: no position and no excerpt. That library's error
 *    message ends with the input itself ("JSON input: …"), and a key document belongs in no log.
 *  - It stops at [MAX_DEPTH] levels of nesting, the limit the server keeps for the wrapped key
 *    (docs/SYNC_PROTOCOL.md §1.2), so a document nested thousands deep cannot exhaust a stack.
 *
 * A name that appears twice in one object keeps its last value, as it does in `JSON.parse`, so both
 * sides read the same value from the same bytes. A number is kept as its text ([Number]), so the
 * reader of each field decides which forms it takes.
 */
internal object StrictJson {

    /** The whole refusal. It carries nothing about the input, by design. */
    class JsonException : Exception("not a JSON text")

    /** A JSON number, as written: `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. */
    class Number(val lexeme: String)

    /** Containers deeper than this are refused. The top-level object is depth 1. */
    const val MAX_DEPTH = 32

    /**
     * One JSON text to its value: a `Map<String, Any?>` for an object (in document order), a
     * `List<Any?>` for an array, a `String`, a [Number], a `Boolean`, or null.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.value(depth = 0)
        reader.skipWhitespace()
        if (!reader.atEnd()) throw JsonException()
        return value
    }

    private class Reader(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i == s.length

        /** RFC 8259 whitespace is these four characters and no others. */
        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (atEnd()) throw JsonException()
            return when (s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            if (depth > MAX_DEPTH) throw JsonException()
            i++ // '{'
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (take('}')) return members
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') throw JsonException()
                val name = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                members[name] = value(depth)
                skipWhitespace()
                if (take(',')) continue
                expect('}')
                return members
            }
        }

        private fun array(depth: Int): List<Any?> {
            if (depth > MAX_DEPTH) throw JsonException()
            i++ // '['
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (take(']')) return items
            while (true) {
                skipWhitespace()
                items.add(value(depth))
                skipWhitespace()
                if (take(',')) continue
                expect(']')
                return items
            }
        }

        private fun string(): String {
            i++ // the opening '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException()
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) throw JsonException()
                        when (s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                var unit = 0
                                repeat(4) {
                                    if (atEnd()) throw JsonException()
                                    unit = unit * 16 + hexDigit(s[i++])
                                }
                                // One UTF-16 unit, paired or not, exactly as JSON.parse keeps it.
                                out.append(unit.toChar())
                            }
                            else -> throw JsonException()
                        }
                    }
                    // U+0000..U+001F must be escaped inside a string.
                    c < ' ' -> throw JsonException()
                    else -> out.append(c)
                }
            }
        }

        /** ASCII hex only: Character.digit would also take Arabic-Indic and fullwidth digits. */
        private fun hexDigit(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw JsonException()
        }

        private fun number(): Number {
            val start = i
            take('-')
            when {
                take('0') -> Unit
                !atEnd() && s[i] in '1'..'9' -> digits()
                else -> throw JsonException()
            }
            if (take('.')) {
                if (atEnd() || s[i] !in '0'..'9') throw JsonException()
                digits()
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                if (atEnd() || s[i] !in '0'..'9') throw JsonException()
                digits()
            }
            return Number(s.substring(start, i))
        }

        private fun digits() {
            while (!atEnd() && s[i] in '0'..'9') i++
        }

        private fun literal(word: String, value: Boolean?): Boolean? {
            if (!s.startsWith(word, i)) throw JsonException()
            i += word.length
            return value
        }

        private fun take(c: Char): Boolean {
            if (atEnd() || s[i] != c) return false
            i++
            return true
        }

        private fun expect(c: Char) {
            if (!take(c)) throw JsonException()
        }
    }
}
