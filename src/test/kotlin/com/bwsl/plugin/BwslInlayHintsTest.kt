package com.bwsl.plugin

/** Parameter names in front of call arguments, from the compiler's resolution of each call. */
class BwslInlayHintsTest : BwslAstFixtureTestCase() {

    /** "name: first token of the argument" for each hint. */
    private fun collect(source: String, modules: Map<String, String> = emptyMap()): List<String> {
        val text = configureAndCache(source, modules)
        return collectParameterNameHints(myFixture.file)!!.map { hint ->
            "${hint.name}: ${text.substring(hint.offset).takeWhile { it != ' ' && it != ',' && it != ')' }}"
        }
    }

    fun testEachArgumentGetsTheNameOfItsParameter() {
        val hints = collect(
            "module M {\n    blend3 :: (float a, float b, float t) -> float { return a; }\n" +
                "    f :: (float x) -> float { return blend3(x + 1.0, 2.0, x * 0.5); }\n}"
        )

        assertEquals(listOf("a: x", "b: 2.0", "t: x"), hints)
    }

    fun testAnArgumentThatIsTheParametersOwnNameGetsNoHint() {
        val hints = collect(
            "module M {\n    g :: (float a, float b) -> float { return a; }\n    f :: (float a) -> float { return g(a, 1.0); }\n}"
        )

        assertEquals(listOf("b: 1.0"), hints)
    }

    fun testNestedCallsAndModuleQualifiedCallsAreHinted() {
        val hints = collect(
            "module M {\n    import Lib\n    f :: (float x) -> float { return Lib::helper(Lib::helper(x + 1.0)); }\n}",
            mapOf("Lib" to "module Lib {\n    helper :: (float v) -> float { return v; }\n}")
        )

        assertEquals(2, hints.size)
        assertTrue(hints.all { it.startsWith("v:") })
    }

    fun testIntrinsicCallsAndCallsWithoutArgumentsGetNone() {
        val hints = collect("module M {\n    g :: () -> float { return 1.0; }\n    f :: (float x) -> float { return g() + cos(x); }\n}")

        assertTrue(hints.isEmpty())
    }

    private val arrays = "module M {\n    const int N = 3;\n    struct S {\n        float[N] w;\n    }\n" +
        "    f :: (float[N] values, float[2][N] grid, float[4] fixed) -> float {\n        float[N] local;\n        float[4] lit;\n        return values[0];\n    }\n}"

    /** For each hint, the type as written before it, then the hint, in source order. */
    private fun collectArrayHints(source: String): List<String> {
        val text = configureAndCache(source)
        val written = Regex("""[\w:]+(\[[^\]]*])+$""")
        return collectArrayLengthHints(myFixture.file)!!.sortedBy { it.offset }.map { hint ->
            "${written.find(text.substring(0, hint.offset))!!.value} ${hint.text}"
        }
    }

    fun testASizeWrittenAsAConstantShowsWhatItResolvesTo() {
        // The struct field, the two parameters and the local; `float[4]` already says 4.
        assertEquals(listOf("float[N] = 3", "float[N] = 3", "float[2][N] = 2×3", "float[N] = 3"), collectArrayHints(arrays))
    }

    fun testNoLengthIsShownOnceTheTextHasChanged() {
        configureAndCache(arrays)
        assertNotNull(collectArrayLengthHints(myFixture.file))

        myFixture.type("\n")

        assertNull(collectArrayLengthHints(myFixture.file))
    }

    fun testNothingIsShownOnceTheTextHasChanged() {
        configureAndCache("module M {\n    g :: (float a) -> float { return a; }\n    f :: () -> float { return g(1.0); }\n}")
        assertNotNull(collectParameterNameHints(myFixture.file))

        myFixture.type("\n")

        assertNull(collectParameterNameHints(myFixture.file))
    }
}
