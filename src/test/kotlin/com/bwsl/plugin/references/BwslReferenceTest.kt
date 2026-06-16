package com.bwsl.plugin.references

import com.bwsl.plugin.*

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BwslReferenceTest : BasePlatformTestCase() {

    fun testReceiverMethodCallResolvesViaVariableType() {
        // s1 and s2 both call test(), but their declared types point to different structs
        // (in different modules) - only the AST's variable-type info can disambiguate.
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
                "    struct testStruct {\n" +
                "        test :: () -> int {\n" +
                "            return 2;\n" +
                "        }\n" +
                "    }\n" +
                "    test3 :: () -> void {\n" +
                "        LengthMethodTest::testStruct s1;\n" +
                "        testStruct s2;\n" +
                "        s1.test();\n" +
                "        s2.test();\n" +
                "    }\n" +
                "}"
        )

        val test3 = AstFunction(
            "test3", emptyList(), "void", line = 14, column = 5, endLine = 19, endColumn = 6,
            body = AstBlock(listOf(
                AstStatement(type = "VARIABLE_DECL", name = "s1", declaredType = "LengthMethodTest::testStruct", line = 15, column = 9),
                AstStatement(type = "VARIABLE_DECL", name = "s2", declaredType = "testStruct", line = 16, column = 9)
            ))
        )

        val filePath = myFixture.file.virtualFile.path
        BwslAstCache.update(filePath, AstRoot(
            modules = listOf(
                AstModule(
                    name = "LengthMethodTest", line = 1, column = 1, endLine = 7, endColumn = 2,
                    structs = listOf(AstStruct(
                        name = "testStruct", line = 2, column = 5, endLine = 6, endColumn = 6,
                        methods = listOf(AstFunction("test", emptyList(), "float", line = 3, column = 9, endLine = 5, endColumn = 10))
                    ))
                ),
                AstModule(
                    name = "LengthTest2", line = 8, column = 1, endLine = 20, endColumn = 2,
                    structs = listOf(AstStruct(
                        name = "testStruct", line = 9, column = 5, endLine = 13, endColumn = 6,
                        methods = listOf(AstFunction("test", emptyList(), "int", line = 10, column = 9, endLine = 12, endColumn = 10))
                    )),
                    functions = listOf(test3)
                )
            )
        ))

        val s1TestOffset = myFixture.file.text.indexOf("s1.test();") + "s1.".length
        val s2TestOffset = myFixture.file.text.indexOf("s2.test();") + "s2.".length

        val s1Element = myFixture.file.findElementAt(s1TestOffset)!!
        val s1Resolved = s1Element.parent.references.firstNotNullOfOrNull { it.resolve() }
        assertNotNull("Expected s1.test() to resolve", s1Resolved)
        assertEquals(offsetAt(myFixture.file, 3, 9), s1Resolved!!.textOffset)

        val s2Element = myFixture.file.findElementAt(s2TestOffset)!!
        val s2Resolved = s2Element.parent.references.firstNotNullOfOrNull { it.resolve() }
        assertNotNull("Expected s2.test() to resolve", s2Resolved)
        assertEquals(offsetAt(myFixture.file, 10, 9), s2Resolved!!.textOffset)

        assertTrue("s1.test() and s2.test() should resolve to different declarations",
            s1Resolved.textOffset != s2Resolved.textOffset)
    }

    fun testModuleQualifierNavigatesToModuleDeclaration() {
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
                "        Length<caret>MethodTest::testStruct s1;\n" +
                "    }\n" +
                "}"
        )

        val test3 = AstFunction(
            "test3", emptyList(), "void", line = 9, column = 5, endLine = 11, endColumn = 6,
            body = AstBlock(listOf(
                AstStatement(type = "VARIABLE_DECL", name = "s1", declaredType = "LengthMethodTest::testStruct", line = 10, column = 9)
            ))
        )
        val lengthMethodTestModule = AstModule(
            name = "LengthMethodTest", line = 1, column = 1, endLine = 7, endColumn = 2,
            structs = listOf(AstStruct(
                name = "testStruct", line = 2, column = 5, endLine = 6, endColumn = 6,
                methods = listOf(AstFunction("test", emptyList(), "float", line = 3, column = 9, endLine = 5, endColumn = 10))
            ))
        )

        val filePath = myFixture.file.virtualFile.path
        BwslAstCache.update(filePath, AstRoot(
            modules = listOf(
                lengthMethodTestModule,
                AstModule(name = "LengthTest2", line = 8, column = 1, endLine = 12, endColumn = 2, functions = listOf(test3))
            )
        ))

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'LengthMethodTest' qualifier to resolve", resolved)
        assertEquals("LengthMethodTest", resolved!!.text)
        assertEquals(offsetAt(myFixture.file, 1, 8), resolved.textOffset)
    }

    fun testFunctionCallResolvesViaAstScopeNotTextProximity() {
        // Two functions named "tonemap" in different pipeline passes - the parser's flat token
        // tree has no notion of "pass" scoping, so only the AST (with its line/column ranges)
        // can tell which one is in scope at the call site.
        myFixture.configureByText(
            "test.bwsl",
            "pipeline P {\n" +
                "    pass \"A\" {\n" +
                "        tonemap :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    pass \"B\" {\n" +
                "        tonemap :: () -> float { return 2.0; }\n" +
                "        other :: () -> float { return tone<caret>map(); }\n" +
                "    }\n" +
                "}"
        )

        val filePath = myFixture.file.virtualFile.path
        BwslAstCache.update(filePath, AstRoot(
            root = AstRootNode(
                line = 1, column = 1, endLine = 9, endColumn = 2,
                passes = listOf(
                    AstPass(
                        name = "A", line = 2, column = 5, endLine = 4, endColumn = 6,
                        functions = listOf(AstFunction("tonemap", emptyList(), "float", line = 3, column = 9, endLine = 3, endColumn = 47))
                    ),
                    AstPass(
                        name = "B", line = 5, column = 5, endLine = 8, endColumn = 6,
                        functions = listOf(
                            AstFunction("tonemap", emptyList(), "float", line = 6, column = 9, endLine = 6, endColumn = 47),
                            AstFunction("other", emptyList(), "float", line = 7, column = 9, endLine = 7, endColumn = 53)
                        )
                    )
                )
            )
        ))

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertEquals(BwslTokenTypes.FUNCTION_CALL, element.node.elementType)
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'tonemap' call to resolve to a declaration", resolved)
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, resolved!!.node.elementType)
        assertEquals("tonemap", resolved.text)
        assertEquals(offsetAt(myFixture.file, 6, 9), resolved.textOffset)
    }

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

    fun testFragmentInputResolvesToVertexOutputAssignment() {
        val source = "pipeline ShaderIoTest {\n" +
            "    pass \"Main\" {\n" +
            "        vertex {\n" +
            "            output.position = float4(0, 0, 0, 1);\n" +
            "            output.uv = float2(0, 0);\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.color = float4(input.uv, 0.0, 1.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        myFixture.configureByText("test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, com.bwsl.plugin.completion.BwslcAstHelper.parse(source))

        val inputUvOffset = source.indexOf("input.uv") + "input.".length
        val element = myFixture.file.findElementAt(inputUvOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected input.uv to resolve to the vertex stage's output.uv assignment", resolved)
        val expectedOffset = source.indexOf("output.uv") + "output.".length
        assertEquals(expectedOffset, resolved!!.textOffset)
    }

    fun testUseAttributesNamesResolveToAttributeDeclarations() {
        val source = "pipeline AttrRefTest {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "        lastTransform: float4\n" +
            "        color: float4\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position, lastTransform, color }\n" +
            "        vertex {\n" +
            "            output.pos = attributes.position;\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.result = attributes.color;\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        myFixture.configureByText("attr_ref_test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, com.bwsl.plugin.completion.BwslcAstHelper.parse(source))

        fun resolveAttrName(name: String): Int? {
            val useBlock = source.indexOf("use attributes {")
            val nameOffset = source.indexOf(name, useBlock)
            val element = myFixture.file.findElementAt(nameOffset) ?: return null
            return element.parent.references.firstNotNullOfOrNull { it.resolve() }?.textOffset
        }

        // Each name in the use block should navigate to its declaration in the attributes block.
        assertEquals(
            "position in use block should resolve to its declaration",
            source.indexOf("position: float4"),
            resolveAttrName("position")
        )
        assertEquals(
            "lastTransform in use block should resolve to its declaration",
            source.indexOf("lastTransform: float4"),
            resolveAttrName("lastTransform")
        )
        assertEquals(
            "color in use block should resolve to its declaration",
            source.indexOf("color: float4"),
            resolveAttrName("color")
        )
    }
}
