package com.daymark.synccrypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [StrictJson] takes what the web's `JSON.parse` takes and refuses what it refuses (RFC 8259), so a
 * key document reads on the phone exactly when it reads on the web. Every text in [ACCEPTED] and
 * [REFUSED] was run through Node 22's `JSON.parse` when this list was written, with the verdict
 * given here. The one deliberate difference is depth, which the phone bounds as the server does.
 */
class StrictJsonTest {

    @Test
    fun acceptsWhatJsonParseAccepts() {
        for (text in ACCEPTED) {
            try {
                StrictJson.parse(text)
            } catch (_: StrictJson.JsonException) {
                fail("refused, where JSON.parse accepts: ${visible(text)}")
            }
        }
    }

    @Test
    fun refusesWhatJsonParseRefuses_andTheRefusalSaysNothingOfTheText() {
        for (text in REFUSED) {
            try {
                StrictJson.parse(text)
                fail("accepted, where JSON.parse refuses: ${visible(text)}")
            } catch (e: StrictJson.JsonException) {
                assertEquals("not a JSON text", e.message)
            }
        }
    }

    @Test
    fun readsValuesAsJsonParseDoes() {
        @Suppress("UNCHECKED_CAST")
        val obj = StrictJson.parse(
            """{"s":"\u0041\/\b\f\n\r\t\"\\","pair":"\uD83C\uDF3F","lone":"\uD800","n":-0.0e+10,"t":true,"f":false,"z":null,"a":[1,[]]}""",
        ) as Map<String, Any?>
        assertEquals("A/\b\u000C\n\r\t\"\\", obj["s"])
        assertEquals("\uD83C\uDF3F", obj["pair"])
        assertEquals("\uD800", obj["lone"])
        assertEquals("-0.0e+10", (obj["n"] as StrictJson.Number).lexeme)
        assertEquals(true, obj["t"])
        assertEquals(false, obj["f"])
        assertTrue(obj.containsKey("z") && obj["z"] == null)
        assertEquals(2, (obj["a"] as List<*>).size)
        assertEquals(listOf("s", "pair", "lone", "n", "t", "f", "z", "a"), obj.keys.toList())
    }

    @Test
    fun aNameGivenTwiceKeepsItsLastValue() {
        val obj = StrictJson.parse("""{"a":1,"b":0,"a":2}""") as Map<*, *>
        assertEquals("2", (obj["a"] as StrictJson.Number).lexeme)
        assertEquals(2, obj.size)
    }

    @Test
    fun nestingStopsAtTheServersDepth() {
        fun nested(levels: Int) = "[".repeat(levels) + "]".repeat(levels)
        StrictJson.parse(nested(StrictJson.MAX_DEPTH))
        StrictJson.parse("{\"a\":${nested(StrictJson.MAX_DEPTH - 1)}}")
        for (text in listOf(nested(StrictJson.MAX_DEPTH + 1), "{\"a\":${nested(StrictJson.MAX_DEPTH)}}", nested(100_000))) {
            try {
                StrictJson.parse(text)
                fail("accepted ${text.length} characters nested past the limit")
            } catch (_: StrictJson.JsonException) {
                // expected, and without a StackOverflowError at 100,000 levels
            }
        }
    }

    private fun visible(text: String): String =
        text.map { if (it.code < 0x20 || it.code > 0x7e) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    internal companion object {
        val ACCEPTED = listOf(
            "{}", "[]", " \t\n\r{} \t\n\r", "{\"a\":1}", "{\"\":0}", "\"s\"", "1", "-0", "true", "null",
            "{\"a\":[1,-1,0,-0,0.5,1e5,1E+5,1e-5,-0.0e+10,true,false,null,\"x\"]}",
            "{\"a\":\"\\u0041\"}", "{\"a\":\"\\uD83C\\uDF3F\"}", "{\"a\":\"\\uD800\"}", "{\"a\":\"\\uDC00x\"}",
            "{\"a\":\"\\/\\b\\f\\n\\r\\t\\\"\\\\\"}",
            "{\"a\":\"\u2028\u2029\u007f\u00a0\uFEFF\"}", "{\"a\":\"\uD83C\uDF3F\"}", "{\"a\":\"\uD800\"}",
            "{\"a\":{\"b\":{\"c\":[{}]}}}", "[[],[[]],{}]", "{\"a\" : 1 , \"b\" :\n[ 1 , 2 ] }",
        )

        val REFUSED = listOf(
            "", " ", "{", "}", "[", "]", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "{,}", "[1 2]",
            "{'a':1}", "{a:1}", "{\"a\" 1}", "{\"a\":1 \"b\":2}",
            "{\"a\":01}", "{\"a\":-01}", "{\"a\":+1}", "{\"a\":.5}", "{\"a\":5.}", "{\"a\":1e}", "{\"a\":1e+}",
            "{\"a\":0x10}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":-Infinity}", "{\"a\":-}", "{\"a\":--1}",
            "{\"a\":tru}", "{\"a\":True}", "{\"a\":nul}", "{\"a\":undefined}", "{\"a\":truex}",
            "{\"a\":\"\\x41\"}", "{\"a\":\"\\u004\"}", "{\"a\":\"\\u00G1\"}", "{\"a\":\"\\U0041\"}",
            "{\"a\":\"\\u\uFF10\uFF10\uFF14\uFF11\"}", "{\"a\":\"\\u\u0660\u0660\u0664\u0661\"}", "{\"a\":\"\\'\"}",
            "{\"a\":\"\t\"}", "{\"a\":\"\n\"}", "{\"a\":\"\u0000\"}", "{\"a\":\"\u001f\"}",
            "{\"a\":\"open}", "\"\\", "\"",
            "{\"a\":1}x", "{\"a\":1}{}", "{\"a\":1}\u0000", "\uFEFF{}", "{\u00a0}", "{\u000b}", "{\u000c}", "{\u2028}",
            "/* c */{}", "{\"a\":1 /* c */}", "// c\n{}",
        )
    }
}
