package com.bwsl.plugin

/** Ctrl+click on a call goes to the overload the compiler picked, not to the first function with that name. */
class BwslOverloadNavigationTest : BwslAstFixtureTestCase() {

    private val lib = mapOf(
        "Lib" to "module Lib {\n    scale :: (float x) -> float { return x; }\n    scale :: (float2 x) -> float2 { return x; }\n}"
    )

    private fun pipeline(vararg calls: String, imports: Boolean = false) =
        "pipeline P {\n" + (if (imports) "    import Lib\n" else "") + "    f :: (float x) -> float { return x; }\n    f :: (float2 x) -> float2 { return x; }\n" +
            "    f :: (float x, float y) -> float { return x + y; }\n" +
            "    attributes { position: float4 }\n    pass \"Main\" {\n        use attributes { position }\n        outputs { c: float4 }\n" +
            "        vertex {\n" + calls.joinToString("") { "            $it\n" } + "            output.pos = attributes.position;\n        }\n" +
            "        fragment { output.c = float4(1.0); }\n    }\n}"

    private fun resolveAt(offset: Int): List<Pair<String, Int>> {
        val index = buildAstIndex(myFixture.file)!!
        return resolveSymbolAt(myFixture.file, index, offset).map { it.containingFile.name to it.textOffset }
    }

    private fun offsetOfName(text: String, within: String, name: String) = text.indexOf(within) + within.indexOf(name)

    fun testEachCallGoesToItsOwnOverloadInTheSameFile() {
        val text = configureAndCache(pipeline("float a = f(1.0);", "float2 b = f(float2(1.0));", "float c = f(1.0, 2.0);"))

        val one = offsetOfName(text, "f :: (float x) ->", "f")
        val two = offsetOfName(text, "f :: (float2 x) ->", "f")
        val three = offsetOfName(text, "f :: (float x, float y) ->", "f")

        assertEquals(one, resolveAt(offsetOfName(text, "a = f(1.0)", "f")).single().second)
        assertEquals(two, resolveAt(offsetOfName(text, "b = f(float2", "f")).single().second)
        assertEquals(three, resolveAt(offsetOfName(text, "c = f(1.0, 2.0)", "f")).single().second)
    }

    fun testEachCallGoesToItsOwnOverloadInAnImportedModule() {
        val text = configureAndCache(pipeline("float a = Lib::scale(1.0);", "float2 b = Lib::scale(float2(1.0));", imports = true), lib)
        val source = lib.getValue("Lib")

        val one = source.indexOf("scale :: (float x)")
        val two = source.indexOf("scale :: (float2 x)")

        val first = resolveAt(offsetOfName(text, "a = Lib::scale(1.0)", "scale")).single()
        val second = resolveAt(offsetOfName(text, "b = Lib::scale(float2", "scale")).single()

        assertEquals("Lib.bwsl", first.first)
        assertEquals(one, first.second)
        assertEquals(two, second.second)
    }
}
