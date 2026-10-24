package com.bwsl.plugin.completion

/**
 * Completion in an index (`values[...]`): the integer values in scope, followed through arrays, integer vectors and
 * structs down to every integer in them, the functions that return one; and nothing while a number is typed.
 */
class BwslCompletionIndexTest : BwslCompletionScopeTestCase() {

    private fun shader(index: String) = """
        module M {
            const int COUNT = 4;
            const float SCALE = 2.0;
            struct Inner {
                int count;
                float w;
            }
            struct Outer {
                int count;
                float f;
                Inner one;
                Inner[2] nested;
                size :: () -> int { return self.count; }
                scaled :: (int k) -> int { return self.count * k; }
                weight :: () -> float { return self.f; }
            }
            pick :: () -> int { return 1; }
            ratio :: () -> float { return 0.5; }
            f :: (float[4] values, int i, uint u, float weight, Outer top, int2 p) -> float {
                int j = 2;
                float half = 0.5;
                Outer[3] many;
                return values[$index] * weight;
            }
        }
    """.trimIndent()

    fun testAnIndexOffersTheIntegerVariablesConstantsAndFunctions() {
        assertCompletions(
            shader("<caret>0"),
            present = setOf("i", "u", "j", "COUNT", "pick"),
            absent = setOf("weight", "half", "SCALE", "values", "ratio", "f", "float", "int", "if", "return", "cos", "length")
        )
    }

    fun testAnIndexOffersEveryIntegerReachableThroughStructsArraysAndVectors() {
        assertCompletions(
            shader("<caret>0"),
            present = setOf(
                "top.count", "top.size()", "top.scaled()", "top.one.count", "top.nested[0].count",
                "many[0].count", "many[0].nested[0].count", "many[0].size()", "p.x", "p.y"
            ),
            absent = setOf("top", "many", "p", "top.f", "top.one.w", "top.weight()", "top.one", "p.z", "values[0]")
        )
    }

    fun testAMethodWithParametersPutsTheCaretBetweenItsParentheses() {
        val source = shader("top<caret>0")
        myFixture.configureByText("index_method.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("top<caret>", ""), myFixture.file.virtualFile.path)

        myFixture.completeBasic()?.firstOrNull { it.lookupString == "top.scaled()" }?.let { myFixture.lookup.currentItem = it; myFixture.finishLookup('\n') }

        assertTrue(myFixture.file.text, myFixture.file.text.contains("values[top.scaled()0]"))
        assertEquals(myFixture.file.text.indexOf("top.scaled()") + "top.scaled(".length, myFixture.caretOffset)
    }

    fun testANumberBeingTypedGetsNoSuggestions() {
        val source = shader("4<caret>")
        myFixture.configureByText("index_number.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val suggestions = myFixture.completeBasic()

        assertTrue("got ${suggestions?.map { it.lookupString }}", suggestions.isNullOrEmpty())
        assertTrue("nothing was inserted: ${myFixture.file.text}", myFixture.file.text.contains("values[4] * weight"))
    }

    fun testANumberBeingTypedAnywhereGetsNoSuggestions() {
        val source = shader("0").replace("float half = 0.5;", "float half = 4<caret>;")
        myFixture.configureByText("number.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val suggestions = myFixture.completeBasic()

        assertTrue("got ${suggestions?.map { it.lookupString }}", suggestions.isNullOrEmpty())
    }
}
