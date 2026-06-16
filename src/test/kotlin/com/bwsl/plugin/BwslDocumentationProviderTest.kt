package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BwslDocumentationProviderTest : BasePlatformTestCase() {

    private val provider = BwslDocumentationProvider()

    private fun docAt(caretOffset: Int): String? {
        val file = myFixture.file
        var element = file.findElementAt(caretOffset)!!
        val custom = provider.getCustomDocumentationElement(myFixture.editor, file, element, caretOffset)
        if (custom != null) {
            element = custom
        } else {
            element.parent?.references?.firstNotNullOfOrNull { it.resolve() }?.let { element = it }
        }
        return provider.generateDoc(element, element)
    }

    fun testIntrinsicCallShowsSignatureAndDescription() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float x) -> float {\n" +
                "        return sat<caret>urate(x);\n" +
                "    }\n" +
                "}"
        )

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'saturate'", doc)
        assertTrue(doc!!.contains("saturate"))
        assertTrue("Expected return type in signature", doc.contains("floatN"))
        assertTrue("Expected description", doc.contains("Clamp to"))
    }

    fun testArrayLengthShowsIntSignatureNotGenericIntrinsic() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float[5] values) -> int {\n" +
                "        return values.len<caret>gth();\n" +
                "    }\n" +
                "}"
        )

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'values.length()'", doc)
        assertTrue("Expected 'int length()' signature, got: $doc", doc!!.contains("int length()"))
        assertTrue("Expected array-length description", doc.contains("Number of elements"))
    }

    fun testIntrinsicMethodCallOnReceiverShowsDescription() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float[5] values) -> float {\n" +
                "        return values.c<caret>os();\n" +
                "    }\n" +
                "}"
        )

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'values.cos()'", doc)
        assertTrue("Expected 'cos' signature, got: $doc", doc!!.contains("cos"))
        assertTrue("Expected cosine description, got: $doc", doc.contains("Cosine"))
    }

    fun testLocalVariableUsageShowsDeclaredType() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normalized = float2(1.0, 2.0);\n" +
                "        return normali<caret>zed;\n" +
                "    }\n" +
                "}"
        )

        val filePath = myFixture.file.virtualFile.path
        val fn = AstFunction(
            "f1", emptyList(), "float2", line = 2, column = 5, endLine = 5, endColumn = 6,
            body = AstBlock(listOf(
                AstStatement(type = "VARIABLE_DECL", name = "normalized", declaredType = "float2", line = 3, column = 9)
            ))
        )
        BwslAstCache.update(filePath, AstRoot(
            modules = listOf(AstModule(name = "M", line = 1, column = 1, endLine = 6, endColumn = 2, functions = listOf(fn)))
        ))

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'normalized'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float2 normalized"))
        assertTrue("Expected 'local variable' label, got: $doc", doc.contains("local variable"))
    }

    fun testParameterUsageShowsDeclaredType() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    rotate :: (float2 pos, float2 center) -> float2 {\n" +
                "        return po<caret>s - center;\n" +
                "    }\n" +
                "}"
        )

        val filePath = myFixture.file.virtualFile.path
        val fn = AstFunction(
            "rotate",
            listOf(AstParam("pos", "float2"), AstParam("center", "float2")),
            "float2", line = 2, column = 5, endLine = 4, endColumn = 6
        )
        BwslAstCache.update(filePath, AstRoot(
            modules = listOf(AstModule(name = "M", line = 1, column = 1, endLine = 5, endColumn = 2, functions = listOf(fn)))
        ))

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'pos'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float2 pos"))
        assertTrue("Expected 'parameter' label, got: $doc", doc.contains("parameter"))
    }

    fun testAttributesQualifierShowsUsedAttributeList() {
        val source = "pipeline P {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "        color: float4\n" +
            "        uv: float2\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position, color }\n" +
            "        vertex {\n" +
            "            output.pos = attribu<caret>tes.position;\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        myFixture.configureByText("test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, BwslcAstHelper.parse(source.replace("<caret>", "")))

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'attributes'", doc)
        assertTrue("Expected position in list, got: $doc", doc!!.contains("position"))
        assertTrue("Expected color in list, got: $doc", doc.contains("color"))
        assertFalse("Expected uv NOT in list (not in use block), got: $doc", doc.contains("uv"))
        assertTrue("Expected float4 type shown, got: $doc", doc.contains("float4"))
    }

    fun testAttributesMemberShowsType() {
        val source = "pipeline P {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "        uv: float2\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position, uv }\n" +
            "        vertex {\n" +
            "            output.pos = attributes.posi<caret>tion;\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        myFixture.configureByText("test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, BwslcAstHelper.parse(source.replace("<caret>", "")))

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'attributes.position'", doc)
        assertTrue("Expected type float4, got: $doc", doc!!.contains("float4"))
        assertTrue("Expected member name, got: $doc", doc.contains("position"))
    }

    fun testCustomFunctionCallShowsQualifiedNameAndSignature() {
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

        val filePath = myFixture.file.virtualFile.path
        BwslAstCache.update(filePath, AstRoot(
            modules = listOf(
                AstModule(
                    name = "M", line = 1, column = 1, endLine = 7, endColumn = 2,
                    functions = listOf(
                        AstFunction("rotate", listOf(AstParam("pos", "float2")), "float2", line = 2, column = 5, endLine = 4, endColumn = 6),
                        AstFunction("f1", emptyList(), "float2", line = 5, column = 5, endLine = 7, endColumn = 6)
                    )
                )
            )
        ))

        val doc = docAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'rotate' call", doc)
        assertTrue("Expected qualified name, got: $doc", doc!!.contains("M::rotate()"))
        assertTrue("Expected signature with parameter, got: $doc", doc.contains("float2 rotate(float2 pos)"))
    }
}
