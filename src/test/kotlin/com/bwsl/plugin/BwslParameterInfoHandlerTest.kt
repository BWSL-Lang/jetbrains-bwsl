package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BwslParameterInfoHandlerTest : BasePlatformTestCase() {

    private val handler = BwslParameterInfoHandler()

    fun testIntrinsicCallShowsBuiltinSignature() {
        myFixture.configureByText("test.bwsl", "module M { f1 :: () -> float { return sin(<caret>1.0); } }")

        val signatures = handler.signaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals("sin", signatures[0].name)
        assertEquals(BwslIntrinsics.ALL.first { it.name == "sin" }.params.size, signatures[0].params.size)
    }

    fun testCustomFunctionCallShowsAstSignature() {
        myFixture.configureByText(
            "test.bwsl",
            "module M { rotate :: (float2 pos, float2 center, float angle) -> float2 { return pos; } " +
                "f1 :: () -> float2 { return rotate(<caret>pos, center, angle); } }"
        )

        BwslAstCache.update(
            myFixture.file.virtualFile.path,
            listOf(AstFunction("rotate", listOf(AstParam("pos", "float2"), AstParam("center", "float2"), AstParam("angle", "float")), "FLOAT2"))
        )

        val signatures = handler.signaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals("rotate", signatures[0].name)
        assertEquals(listOf("float2 pos", "float2 center", "float angle"), signatures[0].params)
    }

    fun testNoCallExpressionAtCursorYieldsNoSignatures() {
        myFixture.configureByText("test.bwsl", "module M { f1 :: () -> float { float x = <caret>1.0; return x; } }")

        val signatures = handler.signaturesAt(myFixture.file, myFixture.caretOffset)

        assertTrue(signatures.isEmpty())
    }

    fun testUnknownFunctionYieldsNoSignatures() {
        myFixture.configureByText(
            "test.bwsl",
            "module M { f1 :: () -> float2 { return doesNotExist(<caret>1.0); } }"
        )

        val signatures = handler.signaturesAt(myFixture.file, myFixture.caretOffset)

        assertTrue(signatures.isEmpty())
    }
}
