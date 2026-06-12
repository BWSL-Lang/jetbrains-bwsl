package com.bwsl.plugin

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
