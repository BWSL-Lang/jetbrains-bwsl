package com.bwsl.plugin.references

import com.bwsl.plugin.*
import com.bwsl.plugin.completion.BwslcAstHelper

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises the real [BwslReferenceContributor] dispatch end-to-end (via `element.parent.references`,
 * exactly as the IDE would), against real bwslc output rather than hand-built AstRoot literals
 * (which have no reference index to resolve against).
 */
class BwslReferenceTest : BasePlatformTestCase() {

    private fun configureAndCache(text: String): String {
        myFixture.configureByText("test.bwsl", text)
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)
        return source
    }

    fun testReceiverMethodCallResolvesViaVariableType() {
        // s1 and s2 both call test(), but their declared types point to different structs
        // (in different modules) - only the compiler's own receiver-type resolution (surfaced via
        // the reference index) can disambiguate.
        val source = configureAndCache(
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

        val s1TestOffset = source.indexOf("s1.test();") + "s1.".length
        val s2TestOffset = source.indexOf("s2.test();") + "s2.".length

        val s1Element = myFixture.file.findElementAt(s1TestOffset)!!
        val s1Resolved = s1Element.parent.references.firstNotNullOfOrNull { it.resolve() }
        assertNotNull("Expected s1.test() to resolve", s1Resolved)
        assertEquals(source.indexOf("test :: () -> float"), s1Resolved!!.textOffset)

        val s2Element = myFixture.file.findElementAt(s2TestOffset)!!
        val s2Resolved = s2Element.parent.references.firstNotNullOfOrNull { it.resolve() }
        assertNotNull("Expected s2.test() to resolve", s2Resolved)
        assertEquals(source.indexOf("test :: () -> int"), s2Resolved!!.textOffset)

        assertTrue(
            "s1.test() and s2.test() should resolve to different declarations",
            s1Resolved.textOffset != s2Resolved.textOffset
        )
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
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'LengthMethodTest' qualifier to resolve", resolved)
        assertEquals("LengthMethodTest", resolved!!.text)
        assertEquals(source.indexOf("LengthMethodTest"), resolved.textOffset)
    }

    fun testFunctionCallResolvesViaAstScopeNotTextProximity() {
        // Two functions named "tonemap" in different pipeline passes - the parser's flat token
        // tree has no notion of "pass" scoping, so only the compiler's own resolution (via the
        // reference index) can tell which one is in scope at the call site.
        val source = configureAndCache(
            "pipeline P {\n" +
                "    pass \"A\" {\n" +
                "        tonemap :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    pass \"B\" {\n" +
                "        tonemap :: () -> float { return 2.0; }\n" +
                "        other :: () -> float { return tonemap(); }\n" +
                "    }\n" +
                "}"
        )

        val callOffset = source.indexOf("return tonemap();") + "return ".length
        val element = myFixture.file.findElementAt(callOffset)!!
        assertEquals(BwslTokenTypes.FUNCTION_CALL, element.node.elementType)
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'tonemap' call to resolve to a declaration", resolved)
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, resolved!!.node.elementType)
        assertEquals("tonemap", resolved.text)
        // The pass "B" tonemap, not the pass "A" one.
        assertEquals(source.lastIndexOf("tonemap :: () -> float { return 2.0; }"), resolved.textOffset)
    }

    fun testQualifiedFunctionCallNavigatesToModuleLevelDeclarationOnly() {
        val source = configureAndCache(
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
                "        return LengthMethodTest::test(values);\n" +
                "    }\n" +
                "}"
        )

        val callOffset = source.indexOf("LengthMethodTest::test(values)") + "LengthMethodTest::".length
        val element = myFixture.file.findElementAt(callOffset)!!
        val references = element.parent.references
        val resolved = references.firstNotNullOfOrNull { (it as? com.intellij.psi.PsiPolyVariantReference)?.multiResolve(false) }

        assertNotNull("Expected exactly one resolve target", resolved)
        assertEquals(1, resolved!!.size)
        val target = resolved[0].element!!
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, target.node.elementType)
        assertEquals("test", target.text)
        assertTrue(
            "Resolved declaration should be the module-level 'test', not the struct method",
            target.textOffset < source.indexOf("struct testStruct")
        )
    }

    fun testFunctionCallNavigatesToDeclaration() {
        val source = configureAndCache(
            "module M {\n" +
                "    rotate :: (float2 pos) -> float2 {\n" +
                "        return pos;\n" +
                "    }\n" +
                "    f1 :: () -> float2 {\n" +
                "        return rotate(pos);\n" +
                "    }\n" +
                "}"
        )

        val callOffset = source.indexOf("return rotate(pos);") + "return ".length
        val element = myFixture.file.findElementAt(callOffset)!!
        assertEquals(BwslTokenTypes.FUNCTION_CALL, element.node.elementType)
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected 'rotate' call to resolve to its declaration", resolved)
        assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, resolved!!.node.elementType)
        assertEquals("rotate", resolved.text)
        assertTrue(resolved.textOffset < callOffset)
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
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

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
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

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
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertTrue(element.parent.references.firstNotNullOfOrNull { it.resolve() } == null)
    }

    fun testImportNavigatesToTheModuleDeclarationInItsFile() {
        // A decoy Common.bwsl in the project must not be picked: navigation follows the sourceFile
        // bwslc reports for the module, not a lookup by file name.
        myFixture.addFileToProject("decoy/Common.bwsl", "module Common { decoy :: () -> float { return 0.0; } }")
        val source = configureImportingCommon()

        val element = myFixture.file.findElementAt(source.indexOf("import Common") + "import ".length)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected the Common in `import Common` to resolve", resolved)
        assertEquals(commonModule, resolved!!.containingFile.text)
        assertEquals("Common", resolved.text)
        assertEquals(commonModule.indexOf("Common"), resolved.textOffset)
    }

    fun testFragmentInputResolvesToVertexOutputAssignment() {
        val source = configureAndCache(
            "pipeline ShaderIoTest {\n" +
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
        )

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
            "            output.result = float4(1.0, 0.0, 0.0, 1.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        myFixture.configureByText("attr_ref_test.bwsl", source)
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

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

    private val commonModule =
        "module Common {\n" +
            "    helper :: () -> float {\n" +
            "        return 1.0;\n" +
            "    }\n" +
            "}\n"

    private fun configureImportingCommon(): String {
        myFixture.addFileToProject("Common.bwsl", commonModule)
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    import Common\n" +
                "\n" +
                "    run :: () -> float {\n" +
                "        return Common::helper();\n" +
                "    }\n" +
                "}\n"
        )
        val source = myFixture.file.text
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path, mapOf("Common" to commonModule))
        return source
    }

    fun testCallIntoImportedModuleNavigatesIntoThatModulesFile() {
        // The imported module's AST nodes carry line/column relative to Common.bwsl, so this must
        // land in Common.bwsl at helper's real position - not at the same line/column of the
        // importing file. The module is deliberately not in the project: it is found via the sourceFile
        // bwslc reports.
        val source = configureImportingCommon()

        val callOffset = source.indexOf("Common::helper()") + "Common::".length
        val element = myFixture.file.findElementAt(callOffset)!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected Common::helper() to resolve", resolved)
        assertEquals("Common.bwsl", resolved!!.containingFile.name)
        assertEquals("helper", resolved.text)
        assertEquals(commonModule.indexOf("helper ::"), resolved.textOffset)
    }

    fun testModuleQualifierOfImportedModuleNavigatesToItsDeclarationInItsFile() {
        val source = configureImportingCommon()

        val element = myFixture.file.findElementAt(source.indexOf("Common::helper()"))!!
        val resolved = element.parent.references.firstNotNullOfOrNull { it.resolve() }

        assertNotNull("Expected the Common qualifier to resolve", resolved)
        assertEquals(commonModule, resolved!!.containingFile.text)
        assertEquals(commonModule.indexOf("Common"), resolved.textOffset)
    }
}
