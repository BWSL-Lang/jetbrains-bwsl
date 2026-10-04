package com.bwsl.plugin

/** Parameters, locals, constants and fields coloured by what they resolve to. */
class BwslSemanticHighlightingTest : BwslAstFixtureTestCase() {

    private fun collect(source: String, modules: Map<String, String> = emptyMap()): List<String> {
        val text = configureAndCache(source, modules)
        return collectSemanticHighlights(myFixture.file).sortedBy { it.first.startOffset }.map { (range, key) ->
            "${range.substring(text)}=${key.externalName.removePrefix("BWSL_")}"
        }
    }

    fun testEachKindOfSymbolGetsItsOwnColourAtTheDeclarationAndAtEveryUse() {
        val highlights = collect(
            """
            module M {
                const float K = 2.0;
                struct S { float w; }
                f :: (float p, S s) -> float {
                    float local = p;
                    const float c = 3.0;
                    for (n in 0..3) { local = local + n; }
                    return local + s.w + c + K;
                }
            }
            """.trimIndent()
        )

        assertTrue(highlights.contains("p=PARAMETER"))
        assertTrue(highlights.contains("s=PARAMETER"))
        assertTrue(highlights.contains("local=LOCAL_VARIABLE"))
        assertTrue(highlights.contains("n=LOCAL_VARIABLE"))
        assertTrue(highlights.contains("c=CONSTANT"))
        assertTrue(highlights.contains("K=CONSTANT"))
        assertTrue(highlights.contains("w=FIELD"))
        assertEquals("w is coloured at its declaration and at its use", 2, highlights.count { it == "w=FIELD" })
        assertEquals(4, highlights.count { it == "local=LOCAL_VARIABLE" })
    }

    fun testFunctionsAndTypesAreLeftToTheirOwnColours() {
        val highlights = collect("module M {\n    g :: () -> float { return 1.0; }\n    f :: () -> float { return g(); }\n}")

        assertTrue(highlights.isEmpty())
    }

    fun testNothingIsColouredOnceTheTextHasChanged() {
        configureAndCache("module M {\n    f :: (float p) -> float { return p; }\n}")
        assertTrue(collectSemanticHighlights(myFixture.file).isNotEmpty())

        myFixture.type("\n")

        assertTrue(collectSemanticHighlights(myFixture.file).isEmpty())
    }

    fun testTheColoursAreListedInTheColorSettings() {
        val names = BwslColorSettingsPage().attributeDescriptors.map { it.displayName }

        assertTrue(names.containsAll(listOf("Semantic//Parameter", "Semantic//Local variable", "Semantic//Struct field", "Semantic//Constant")))
    }
}
