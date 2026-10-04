package com.bwsl.plugin

class BwslParameterInfoHandlerTest : BwslAstFixtureTestCase() {

    private val handler = BwslParameterInfoHandler()

    fun testIntrinsicCallShowsBuiltinSignature() {
        myFixture.configureByText("test.bwsl", "module M { f1 :: () -> float { return sin(<caret>1.0); } }")

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals("sin", signatures[0].name)
        assertEquals(BwslIntrinsics.ALL.first { it.name == "sin" }.params.size, signatures[0].params.size)
    }

    fun testCustomFunctionCallShowsAstSignature() {
        configureAndCache(
            """
            module M {
                rotate :: (float2 pos, float2 center, float angle) -> float2 { return pos; }
                f1 :: (float2 pos, float2 center, float angle) -> float2 { return rotate(<caret>pos, center, angle); }
            }
            """.trimIndent()
        )

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals("rotate", signatures[0].name)
        assertEquals(listOf("float2 pos", "float2 center", "float angle"), signatures[0].params)
        assertEquals("float2", signatures[0].returnType)
    }

    fun testSameNamedFunctionsInDifferentModulesEachShowTheirOwnSignature() {
        // The call is qualified, so the compiler resolves it to exactly one of the two `scale`s.
        configureAndCache(
            """
            module A {
                scale :: (float x) -> float { return x; }
            }
            module B {
                scale :: (float2 v, float k) -> float2 { return v * k; }
            }
            module C {
                run :: () -> float2 { return B::scale(<caret>float2(1.0), 2.0); }
            }
            """.trimIndent()
        )

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals(listOf("float2 v", "float k"), signatures[0].params)
    }

    fun testSameNamedFunctionsInDifferentPassesEachShowTheirOwnSignature() {
        configureAndCache(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                pass "A" {
                    tonemap :: (float x) -> float { return x; }
                }
                pass "B" {
                    use attributes { position }
                    tonemap :: (float3 color, float exposure) -> float3 { return color * exposure; }
                    vertex {
                        output.pos = attributes.position;
                        float3 c = tonemap(<caret>float3(1.0), 2.0);
                    }
                }
            }
            """.trimIndent()
        )

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals(listOf("float3 color", "float exposure"), signatures[0].params)
    }

    fun testImportedFunctionShowsItsSignature() {
        configureAndCache(
            """
            module M {
                import Common

                run :: () -> float { return Common::helper(<caret>1.0, 2.0); }
            }
            """.trimIndent(),
            mapOf("Common" to "module Common {\n    helper :: (float a, float b) -> float { return a + b; }\n}\n")
        )

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertEquals(1, signatures.size)
        assertEquals("helper", signatures[0].name)
        assertEquals(listOf("float a", "float b"), signatures[0].params)
    }

    fun testNoCallExpressionAtCursorYieldsNoSignatures() {
        myFixture.configureByText("test.bwsl", "module M { f1 :: () -> float { float x = <caret>1.0; return x; } }")

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertTrue(signatures.isEmpty())
    }

    fun testUnknownFunctionYieldsNoSignatures() {
        myFixture.configureByText(
            "test.bwsl",
            "module M { f1 :: () -> float2 { return doesNotExist(<caret>1.0); } }"
        )

        val signatures = handler.collectSignaturesAt(myFixture.file, myFixture.caretOffset)

        assertTrue(signatures.isEmpty())
    }
}
