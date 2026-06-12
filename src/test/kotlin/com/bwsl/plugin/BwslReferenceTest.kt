package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BwslReferenceTest : BasePlatformTestCase() {

    fun testQualifiedFunctionCallNavigatesToModuleLevelDeclarationOnly() {
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    test :: (float[5] values) -> float {\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "    struct testStruct {\n" +
                "        test :: () -> float {\n" +
                "            return 2.0;\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test2 :: () -> float {\n" +
                "        float[5] values;\n" +
                "        return LengthMethodTest::tes<caret>t(values);\n" +
                "    }\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val references = element.parent.references
        val resolved = references.firstNotNullOfOrNull { (it as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false) }

        assertNotNull("Expected exactly one resolve target", resolved)
        assertEquals(1, resolved!!.size)
        val target = resolved[0].element!!
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, target.node.elementType)
        assertEquals("test", target.text)
        assertTrue("Resolved declaration should be the module-level 'test', not the struct method",
            target.textOffset < myFixture.file.text.indexOf("struct testStruct"))
    }

    fun testFunctionCallNavigatesToDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    rotate :: (float2 pos) -> float2 {\n" +
                "        return pos;\n" +
                "    }\n" +
                "    f1 :: () -> float2 {\n" +
                "        return rota<caret>te(pos);\n" +
                "    }\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertEquals(BwslTokenTypes.FUNCTION_CALL, element.node.elementType)
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'rotate' call to resolve to its declaration", resolved)
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, resolved!!.node.elementType)
        assertEquals("rotate", resolved.text)
        assertTrue(resolved.textOffset < myFixture.caretOffset)
    }

    fun testVariableUsageNavigatesToLocalDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normalized = float2(1.0, 2.0);\n" +
                "        return normali<caret>zed;\n" +
                "    }\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'normalized' usage to resolve to its declaration", resolved)
        assertEquals(BwslTokenTypes.REFERENCE, resolved!!.node.elementType)
        assertEquals("normalized", resolved.text)
        assertTrue(resolved.textOffset < element.textOffset)
        val prevType = previousNonWhitespace(resolved)?.node?.elementType
        assertEquals(BwslTokenTypes.KW_FLOAT2, prevType)
    }

    fun testParameterUsageNavigatesToParameterDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    rotate :: (float2 pos, float2 center) -> float2 {\n" +
                "        return po<caret>s - center;\n" +
                "    }\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'pos' usage to resolve to its parameter declaration", resolved)
        assertEquals("pos", resolved!!.text)
        val prevType = previousNonWhitespace(resolved)?.node?.elementType
        assertEquals(BwslTokenTypes.KW_FLOAT2, prevType)
        assertTrue(resolved.textOffset < element.textOffset)
    }

    fun testDeclarationItselfHasNoReference() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normali<caret>zed = float2(1.0, 2.0);\n" +
                "        return normalized;\n" +
                "    }\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(element.parent.references.isEmpty())
    }

    fun testImportNavigatesToModuleFile() {
        myFixture.addFileToProject("Common.bwsl", "module Common {\n    helper :: () -> float { return 1.0; }\n}")
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    import Comm<caret>on\n" +
                "}"
        )

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'Common' import to resolve to Common.bwsl", resolved)
        assertEquals("Common.bwsl", (resolved as com.intellij.psi.PsiFile).name)
    }
}
