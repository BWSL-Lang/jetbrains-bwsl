package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper

/** Unused parameters, locals and imports, and a used module that is not imported, read from the compiler's reference index. */
class BwslInspectionsTest : BwslAstFixtureTestCase() {

    private val lib = mapOf("Lib" to "module Lib {\n    helper :: (float v) -> float { return v; }\n    struct Item { float w; }\n}")

    private fun configure(source: String, modules: Map<String, String> = lib): InspectionInput {
        configureAndCache(source, modules)
        return findInspectionInput(myFixture.file)!!
    }

    private fun describe(input: InspectionInput, declarations: List<UnusedDeclaration>) =
        declarations.map { "${it.kind.label} ${it.name}${if (it.isWrittenOnly) " (written only)" else ""}: '${input.text.substring(it.range.startOffset, it.range.endOffset)}'" }

    fun testUnusedParametersLocalsAndConstantsAreFound() {
        val input = configure(
            """
            module M {
                const float K = 2.0;
                f :: (float used, float unusedParam, float writtenOnly) -> float {
                    float a = 1.0;
                    float neverTouched = 2.0;
                    float onlyWritten = 0.0;
                    onlyWritten = used;
                    float compound = 1.0;
                    compound += used;
                    const float localConst = 3.0;
                    writtenOnly = 5.0;
                    float2 vec = float2(1.0);
                    vec.x = 2.0;
                    for (n in 0..3) {
                        a = a + 1.0;
                    }
                    return a + compound;
                }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "parameter unusedParam: 'unusedParam'",
                "parameter writtenOnly (written only): 'writtenOnly'",
                "variable neverTouched: 'neverTouched'",
                "variable onlyWritten (written only): 'onlyWritten'",
                "constant localConst: 'localConst'"
            ),
            describe(input, collectUnusedDeclarations(input)).sortedBy { input.text.indexOf(it.substringAfter(": '").trimEnd('\'')) }
        )
    }

    fun testAModuleLevelConstIsNotReportedAsItCanBeUsedElsewhere() {
        val input = configure("module M {\n    const float K = 2.0;\n    f :: () -> float { return 1.0; }\n}")

        assertTrue(collectUnusedDeclarations(input).isEmpty())
    }

    fun testAnUnusedImportIsFoundAndAUsedOneIsNot() {
        val input = configure(
            "module M {\n    import Lib\n    import Extra\n    f :: (float x) -> float { return Lib::helper(x); }\n}",
            lib + mapOf("Extra" to "module Extra { const float PI = 3.14; }")
        )

        assertEquals(listOf("Extra"), collectUnusedImports(input).map { it.module })
    }

    fun testAModuleWrittenAsAResourceTypeIsNotCalledUnused() {
        // The compiler records no edge for `Lib.Item` in a resources block, so the index alone says "unused".
        val input = configure(
            "pipeline P {\n    import Lib\n    resources {\n        item: Lib.Item\n    }\n    attributes { position: float4 }\n" +
                "    pass \"Main\" {\n        use attributes { position }\n        use resources { item }\n        outputs { c: float4 }\n" +
                "        vertex { output.pos = attributes.position; }\n        fragment { output.c = float4(resources.item.w); }\n    }\n}"
        )

        assertTrue(collectUnusedImports(input).isEmpty())
    }

    fun testAUsingThatOnlyBringsInNamesIsUsedWhenOneIsCalled() {
        val input = configure(
            "module M {\n    import Lib\n    using Lib\n    f :: (float x) -> float { return helper(x); }\n}"
        )

        assertTrue(collectUnusedImports(input).isEmpty())
    }

    fun testAModuleUsedOnlyInATypeCountsAsUsed() {
        val input = configure(
            "module M {\n    import Lib\n    f :: (Lib::Item it) -> float { return it.w; }\n}"
        )

        assertTrue(collectUnusedImports(input).isEmpty())
    }

    fun testAQualifierOfAModuleThatIsNotImportedIsFoundWhenTheModuleIsKnown() {
        val input = configure("module M {\n    f :: (float x) -> float { return Other::thing(x); }\n}")

        assertEquals(listOf("Other"), collectMissingImports(input, setOf("Other")).map { it.module })
        assertTrue("a name nothing knows is left to the compiler", collectMissingImports(input, setOf("Else")).isEmpty())
    }

    fun testAnImportedModuleIsNotMissing() {
        val input = configure("module M {\n    import Lib\n    f :: (float x) -> float { return Lib::helper(x); }\n}")

        assertTrue(collectMissingImports(input, setOf("Lib")).isEmpty())
    }

    fun testTheInspectionsReportNothingOnceTheTextHasChanged() {
        configure("module M {\n    f :: (float x, float y) -> float { return x; }\n}")
        assertNotNull(findInspectionInput(myFixture.file))

        myFixture.type("\n")

        assertNull(findInspectionInput(myFixture.file))
    }

    fun testRemovingAnUnusedImportDeletesItsLine() {
        configureAndCache(
            "module M {\n    import Lib\n    import <caret>Extra\n    f :: (float x) -> float { return Lib::helper(x); }\n}",
            lib + mapOf("Extra" to "module Extra { const float PI = 3.14; }")
        )
        myFixture.enableInspections(BwslUnusedImportInspection::class.java)

        myFixture.launchAction(myFixture.findSingleIntention("Remove unused import"))

        myFixture.checkResult("module M {\n    import Lib\n    f :: (float x) -> float { return Lib::helper(x); }\n}")
    }

    fun testRemovingAnUnusedVariableDeletesItsStatement() {
        configureAndCache("module M {\n    f :: (float x) -> float {\n        float <caret>unused = 2.0;\n        return x;\n    }\n}")
        myFixture.enableInspections(BwslUnusedDeclarationInspection::class.java)

        myFixture.launchAction(myFixture.findSingleIntention("Remove unused declaration"))

        myFixture.checkResult("module M {\n    f :: (float x) -> float {\n        return x;\n    }\n}")
    }

    fun testAVariableWhoseInitialiserCallsSomethingIsKept() {
        configureAndCache("module M {\n    g :: () -> float { return 1.0; }\n    f :: (float x) -> float {\n        float <caret>unused = g();\n        return x;\n    }\n}")
        myFixture.enableInspections(BwslUnusedDeclarationInspection::class.java)

        myFixture.launchAction(myFixture.findSingleIntention("Remove unused declaration"))

        assertTrue(myFixture.editor.document.text.contains("float unused = g();"))
    }

    fun testImportingAMissingModuleAddsTheImport() {
        BwslcAstHelper.parseAndCache("module Other {\n    thing :: (float v) -> float { return v; }\n}", "/known/Other.bwsl")
        configureAndCache("module M {\n    f :: (float x) -> float { return <caret>Other::thing(x); }\n}")
        myFixture.enableInspections(BwslMissingImportInspection::class.java)

        myFixture.launchAction(myFixture.findSingleIntention("Import 'Other'"))

        myFixture.checkResult("module M {\n    import Other\n    f :: (float x) -> float { return Other::thing(x); }\n}")
    }
}
