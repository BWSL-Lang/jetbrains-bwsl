package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [resolveSymbolAt] directly (not yet wired into a PsiReference - that's Phase 4)
 * against real bwslc output. Covers each of Phase 3's resolution paths from FUCK_THE_LEXER.md §6:
 * plain identifier reads, the VARIABLE_DECL name-vs-type role-awareness split, synthetic
 * declarations with no position of their own (parameters, struct fields, stage interfaces), and
 * `builtin:*` targets resolving to nothing.
 */
class BwslAstResolverTest : BasePlatformTestCase() {

    private fun buildIndex(source: String): BwslAstIndex {
        val root = BwslcAstHelper.parse(source)
        val raw = BwslcAstHelper.parseRaw(source)
        return BwslAstIndex(root, raw, source)
    }

    fun testVariableUsageResolvesToDeclarationName() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normalized = float2(1.0, 2.0);\n" +
                "        return normali<caret>zed;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("normalized", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testDeclarationsOwnNameHasNoReference() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normali<caret>zed = float2(1.0, 2.0);\n" +
                "        return normalized;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        assertTrue(
            "A caret on a VARIABLE_DECL's own name must not resolve anywhere",
            resolveSymbolAt(myFixture.file, index, myFixture.caretOffset).isEmpty()
        )
    }

    fun testDeclaredTypeResolvesToStructDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    struct Foo {\n" +
                "        method :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    f1 :: () -> void {\n" +
                "        F<caret>oo x;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("Foo", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testParameterUsageResolvesToParameterDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    rotate :: (float2 pos) -> float2 {\n" +
                "        return po<caret>s;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("pos", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testQualifiedStructFieldUsageResolvesToFieldDeclaration() {
        // Field access must be receiver-qualified ("c.radius") to appear in the reference index at
        // all - an *unqualified* self-reference to a struct's own field from inside one of its own
        // methods (e.g. `return radius;`) produces no reference edge whatsoever (verified against
        // real bwslc output; not yet a supported resolution path, and not a bug in the resolver).
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    struct Foo {\n" +
                "        float radius;\n" +
                "        area :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    f :: () -> void {\n" +
                "        Foo c;\n" +
                "        c.rad<caret>ius = 2.0;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("radius", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testFragmentInputResolvesToVertexOutputAssignment() {
        myFixture.configureByText(
            "test.bwsl",
            "pipeline P {\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.uv = float2(0, 0);\n" +
                "        }\n" +
                "        fragment {\n" +
                "            output.color = float4(input.u<caret>v, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("uv", resolved[0].text)
        val expectedOffset = myFixture.file.text.indexOf("output.uv") + "output.".length
        assertEquals(expectedOffset, resolved[0].textOffset)
    }

    fun testBuiltinIntrinsicCallResolvesToNothing() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float angle) -> float { return co<caret>s(angle); }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        assertTrue(
            "A call to a builtin intrinsic has no source declaration to navigate to",
            resolveSymbolAt(myFixture.file, index, myFixture.caretOffset).isEmpty()
        )
    }

    fun testReceiverBasedCallResolvesToMethodDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    struct testStruct {\n" +
                "        test :: () -> float {\n" +
                "            return 1.0;\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test3 :: () -> void {\n" +
                "        LengthMethodTest::testStruct s1;\n" +
                "        s1.te<caret>st();\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("test", resolved[0].text)
    }

    fun testQualifiedCallResolvesToModuleLevelFunction() {
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    test :: (float[5] values) -> float {\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test2 :: () -> float {\n" +
                "        float[5] values;\n" +
                "        return LengthMethodTest::tes<caret>t(values);\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("test", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.file.text.indexOf("test2"))
    }

    fun testModuleQualifierOnFunctionCallIsNotCurrentlyResolvable() {
        // referenceIndex.references DOES contain a "qualifier" edge from the module-name
        // identifier to the module for a call like "LengthMethodTest::test(...)" - but that
        // identifier is never materialized as an actual positioned node anywhere in the AST tree
        // (grepping the full -ast-json output for its id finds only the edge itself). There is
        // currently no way to get its line/column, so a caret on the qualifier text can't be
        // resolved via the index at all. Verified against real bwslc output; see
        // FUCK_THE_LEXER.md gap #9. (The callee name after "::" resolves fine - see
        // testQualifiedCallResolvesToModuleLevelFunction.)
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    test :: (float[5] values) -> float {\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test2 :: () -> float {\n" +
                "        float[5] values;\n" +
                "        return Length<caret>MethodTest::test(values);\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        assertTrue(resolveSymbolAt(myFixture.file, index, myFixture.caretOffset).isEmpty())
    }

    fun testModuleQualifierOnVariableDeclTypeResolvesToTheTypeNotTheModule() {
        // A VARIABLE_DECL's declaredType ("LengthMethodTest::testStruct") produces exactly one
        // "type" edge for the whole qualified name, straight to the struct - there is no separate
        // edge for the module-qualifier portion the way there is for a qualified function call
        // (see the test above). So a caret anywhere in that text, including on "LengthMethodTest"
        // itself, resolves to the struct. Verified against real bwslc output; document this
        // asymmetry rather than assume the two qualifier forms behave the same.
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    struct testStruct {\n" +
                "        test :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test3 :: () -> void {\n" +
                "        Length<caret>MethodTest::testStruct s1;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("testStruct", resolved[0].text)
    }
}
