package com.bwsl.plugin

/** Go to Type Declaration: from a variable, parameter, field or function to the struct its type names. */
class BwslTypeDeclarationTest : BwslAstFixtureTestCase() {

    private val lib = mapOf("Lib" to "module Lib {\n    struct Item { float w; }\n}")

    /** "file:text at the target" for each type declaration of the symbol at the caret of [sourceWithCaret]. */
    private fun findTypes(sourceWithCaret: String, modules: Map<String, String> = emptyMap()): List<String> {
        configureAndCache(sourceWithCaret, modules)
        return findTypeDeclarationsAt(myFixture.file, myFixture.caretOffset).map {
            "${it.containingFile.name}:${it.containingFile.text.substring(it.textRange.startOffset).take(6)}"
        }
    }

    fun testAVariableLeadsToItsStruct() {
        val types = findTypes(
            "module M {\n    struct Particle { float mass; }\n    f :: () -> float {\n        Particle <caret>p;\n        return p.mass;\n    }\n}"
        )

        assertEquals(listOf("test.bwsl:Partic"), types)
    }

    fun testAUseOfAParameterLeadsToTheStructToo() {
        val types = findTypes(
            "module M {\n    struct Particle { float mass; }\n    f :: (Particle p) -> float { return <caret>p.mass; }\n}"
        )

        assertEquals(listOf("test.bwsl:Partic"), types)
    }

    fun testAFunctionReturnLeadsToItsStruct() {
        val types = findTypes("module M {\n    struct A { float x; }\n    ma<caret>ke :: () -> A { A a; return a; }\n}")

        assertEquals(listOf("test.bwsl:A { fl"), types.map { it.take(16) })
    }

    fun testATypeFromAnotherModuleOpensItsFile() {
        val types = findTypes(
            "module M {\n    import Lib\n    f :: (Lib::Item <caret>it) -> float { return it.w; }\n}",
            lib
        )

        assertEquals(listOf("Lib.bwsl:Item {"), types.map { it.take(15) })
    }

    fun testABuiltInTypeHasNoDeclaration() {
        assertTrue(findTypes("module M {\n    f :: (float <caret>x) -> float { return x; }\n}").isEmpty())
    }

    fun testTheActionNavigatesThere() {
        configureAndCache(
            "module M {\n    struct Particle { float mass; }\n    f :: (Particle <caret>p) -> float { return p.mass; }\n}"
        )

        myFixture.performEditorAction("GotoTypeDeclaration")

        assertEquals(myFixture.file.text.indexOf("Particle"), myFixture.caretOffset)
    }
}
