package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import junit.framework.TestCase

/** Reading a `bwslc -ast-json` payload into a tree that shares its strings, and the typed model from that tree. */
class BwslAstJsonTest : TestCase() {

    private val source = """
        pipeline P {
            attributes { position: float4 }
            pass "Main" {
                use attributes { position }
                outputs { c: float4 }
                vertex { output.pos = attributes.position; }
                fragment { float a = 1.5; int b = 40000; output.c = float4(a, float(b), 0.0, 1.0); }
            }
        }
    """.trimIndent()

    fun testTheTreeIsTheSameAsGsonsOwn() {
        val json = BwslcAstHelper.compileToJson(source)

        assertEquals(JsonParser.parseString(json), readSharedJson(json))
    }

    fun testTheTypedModelIsTheSameAsGsonsOwn() {
        val json = BwslcAstHelper.compileToJson(source)

        val ours = parseAstJson(json)!!.root
        val theirs = com.google.gson.Gson().fromJson(json, AstRoot::class.java)

        assertEquals(theirs, ours)
    }

    fun testANameIsOneStringWhereverItIsWritten() {
        val tree = readSharedJson("""{"a": {"line": 1}, "b": [{"line": 2}, {"line": 3}]}""") as JsonObject
        val first = tree.getAsJsonObject("a").keySet().first()
        val second = tree.getAsJsonArray("b")[0].asJsonObject.keySet().first()

        assertSame(first, second)
    }

    fun testTheSameStringInTwoPayloadsIsOneInstance() {
        val one = readSharedJson("""{"type": "IDENTIFIER"}""") as JsonObject
        val two = readSharedJson("""{"kind": "IDENTIFIER"}""") as JsonObject

        assertSame(one.get("type").asString, two.get("kind").asString)
    }

    fun testNumbersKeepTheirValueAndTheirText() {
        val tree = readSharedJson("""{"i": 12, "n": -3, "z": 0, "big": 12345678901, "f": 1.50, "e": 1e-5, "neg0": -0}""") as JsonObject

        assertEquals("12", tree.get("i").asString)
        assertEquals("-3", tree.get("n").asString)
        assertEquals("0", tree.get("z").asString)
        assertEquals("12345678901", tree.get("big").asString)
        assertEquals("1.50", tree.get("f").asString)
        assertEquals("1e-5", tree.get("e").asString)
        assertEquals("-0", tree.get("neg0").asString)
        assertEquals(12, tree.get("i").asInt)
        assertEquals(1.5, tree.get("f").asDouble, 0.0)
    }

    fun testBooleansNullsAndEmptyContainersSurvive() {
        val tree = readSharedJson("""{"t": true, "f": false, "n": null, "o": {}, "a": []}""") as JsonObject

        assertTrue(tree.get("t").asBoolean)
        assertFalse(tree.get("f").asBoolean)
        assertTrue(tree.get("n").isJsonNull)
        assertEquals(0, tree.getAsJsonObject("o").size())
        assertEquals(0, tree.getAsJsonArray("a").size())
    }

    fun testEmptyTextIsNoPayloadAndBrokenTextIsAnError() {
        assertNull(readSharedJson(""))
        assertNull(parseAstJson("   "))
        assertNull(parseAstJson("[1, 2]"))
        try {
            readSharedJson("{ not json")
            fail("expected a syntax error")
        } catch (expected: JsonSyntaxException) {
        }
    }
}
