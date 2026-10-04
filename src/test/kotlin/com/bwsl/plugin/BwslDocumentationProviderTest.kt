package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper

class BwslDocumentationProviderTest : BwslAstFixtureTestCase() {

    fun testIntrinsicCallShowsSignatureAndDescription() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float x) -> float {\n" +
                "        return sat<caret>urate(x);\n" +
                "    }\n" +
                "}"
        )

        val doc = generateDocAt(myFixture.caretOffset)
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

        val doc = generateDocAt(myFixture.caretOffset)
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

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'values.cos()'", doc)
        assertTrue("Expected 'cos' signature, got: $doc", doc!!.contains("cos"))
        assertTrue("Expected cosine description, got: $doc", doc.contains("Cosine"))
    }

    fun testLocalVariableUsageShowsDeclaredType() {
        configureAndCache(
            """
            module M {
                f1 :: () -> float2 {
                    float2 normalized = float2(1.0, 2.0);
                    return normali<caret>zed;
                }
            }
            """.trimIndent()
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'normalized'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float2 normalized"))
        assertTrue("Expected 'local variable' label, got: $doc", doc.contains("local variable"))
    }

    fun testParameterUsageShowsDeclaredType() {
        configureAndCache(
            """
            module M {
                rotate :: (float2 pos, float2 center) -> float2 {
                    return po<caret>s - center;
                }
            }
            """.trimIndent()
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'pos'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float2 pos"))
        assertTrue("Expected 'parameter' label, got: $doc", doc.contains("parameter"))
    }

    fun testSameNamedVariablesInDifferentFunctionsEachShowTheirOwnType() {
        // Two functions declare a local `v` with different types; the compiler's reference index
        // says which one a use belongs to.
        val source = """
            module M {
                first :: () -> float {
                    float v = 1.0;
                    return v;
                }
                second :: () -> float2 {
                    float2 v = float2(1.0);
                    return v;
                }
            }
        """.trimIndent()
        configureAndCache(source)

        val inFirst = generateDocAt(source.indexOf("return v;") + "return ".length)
        val inSecond = generateDocAt(source.lastIndexOf("return v;") + "return ".length)

        assertTrue("Expected 'float v' in the first function, got: $inFirst", inFirst!!.contains("float v"))
        assertFalse("First function's v is not a float2, got: $inFirst", inFirst.contains("float2"))
        assertTrue("Expected 'float2 v' in the second function, got: $inSecond", inSecond!!.contains("float2 v"))
    }

    fun testConstantUsageShowsItsType() {
        configureAndCache(
            """
            module M {
                f1 :: () -> float {
                    const float K = 2.0;
                    return <caret>K * 3.0;
                }
            }
            """.trimIndent()
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'K'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float K"))
        assertTrue("Expected 'constant' label, got: $doc", doc.contains("constant"))
    }

    fun testHoveringADeclarationsOwnNameShowsItsType() {
        configureAndCache(
            """
            module M {
                f1 :: () -> float2 {
                    float2 nor<caret>malized = float2(1.0, 2.0);
                    return normalized;
                }
            }
            """.trimIndent()
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the declaration of 'normalized'", doc)
        assertTrue("Expected declared type, got: $doc", doc!!.contains("float2 normalized"))
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

        configureAndCache(source)

        val doc = generateDocAt(myFixture.caretOffset)
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

        configureAndCache(source)

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'attributes.position'", doc)
        assertTrue("Expected type float4, got: $doc", doc!!.contains("float4"))
        assertTrue("Expected member name, got: $doc", doc.contains("position"))
    }

    fun testCustomFunctionCallShowsQualifiedNameAndSignature() {
        val source = "module M {\n" +
            "    rotate :: (float2 pos) -> float2 {\n" +
            "        return pos;\n" +
            "    }\n" +
            "    f1 :: () -> float2 {\n" +
            "        return rotate(pos);\n" +
            "    }\n" +
            "}"

        myFixture.configureByText("test.bwsl", source)
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)

        val caretOffset = source.indexOf("rotate(pos)")
        val doc = generateDocAt(caretOffset)
        assertNotNull("Expected documentation for 'rotate' call", doc)
        assertTrue("Expected qualified name, got: $doc", doc!!.contains("M::rotate()"))
        assertTrue("Expected signature with parameter, got: $doc", doc.contains("float2 rotate(float2 pos)"))
    }

    private val stageIoSource = """
        pipeline P {
            attributes {
                position: float4
                uv: float2
                id: int
            }
            pass "Main" {
                use attributes { position, uv, id }
                outputs {
                    result: float4
                }
                vertex {
                    output.pos = attributes.position;
                    output.n = attributes.uv * 2.0;
                    output.unit = normalize(attributes.uv);
                    @flat output.index = attributes.id;
                }
                fragment {
                    output.result = float4(input.n, input.unit);
                }
            }
        }
    """.trimIndent()

    fun testInputMemberShowsTheTypeTheCompilerInferred() {
        // The type shown is the one on the compiler's stage-interface symbol.
        configureAndCache(stageIoSource.replace("input.n,", "input.<caret>n,"))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'input.n'", doc)
        assertTrue("Expected the inferred type float2, got: $doc", doc!!.contains("float2 n"))
        assertTrue("Expected the input.n name, got: $doc", doc.contains("input.n"))
    }

    fun testStageValueAssignedAnIntrinsicCallShowsTheTypeTheCompilerInferred() {
        // `normalize(float2)` returns float2; the compiler infers that from the intrinsic's signature.
        configureAndCache(stageIoSource.replace("input.unit", "input.un<caret>it"))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'input.unit'", doc)
        assertTrue("Expected the inferred type float2, got: $doc", doc!!.contains("float2 unit"))
        assertFalse("Must not claim the function name as the type, got: $doc", doc.contains("normalize"))
    }

    fun testOutputMemberShowsItsInterpolationQualifier() {
        configureAndCache(stageIoSource.replace("output.index", "output.ind<caret>ex"))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for 'output.index'", doc)
        assertTrue("Expected the int type, got: $doc", doc!!.contains("int index"))
        assertTrue("Expected the @flat qualifier, got: $doc", doc.contains("@flat"))
    }

    fun testFragmentOutputMemberIsDocumentedAsAFragmentOutput() {
        configureAndCache(stageIoSource.replace("output.result", "output.res<caret>ult"))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the fragment output 'result'", doc)
        assertTrue("Expected the declared type, got: $doc", doc!!.contains("float4 result"))
        assertTrue("Expected a fragment-output description, got: $doc", doc.contains("Fragment output"))
    }

    fun testInputQualifierInTheFragmentStageListsTheVertexOutputsWithTheirTypes() {
        configureAndCache(stageIoSource.replace("float4(input.n", "float4(inp<caret>ut.n"))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the 'input' qualifier", doc)
        assertTrue("Expected float2 n listed, got: $doc", doc!!.contains("<b>float2</b> n"))
        assertTrue("Expected int index listed, got: $doc", doc.contains("<b>int</b> index"))
        assertTrue("Expected the @flat qualifier next to index, got: $doc", doc.contains("@flat"))
    }

    fun testOutputQualifierInTheVertexStageListsTheVertexOutputs() {
        configureAndCache(stageIoSource.replace("output.n =", "outp<caret>ut.n ="))

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the 'output' qualifier", doc)
        assertTrue("Expected the vertex stage description, got: $doc", doc!!.contains("Built-in vertex stage qualifier"))
        assertTrue("Expected float2 n listed, got: $doc", doc.contains("<b>float2</b> n"))
    }

    fun testFunctionDeclarationShowsItsQualifiedNameAndSignature() {
        configureAndCache(
            """
            module M {
                struct S {
                    float w;

                    sca<caret>le :: (float k) -> float { return w * k; }
                }
            }
            """.trimIndent()
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the method declaration", doc)
        assertTrue("Expected the qualified name, got: $doc", doc!!.contains("M::S::scale()"))
        assertTrue("Expected the signature, got: $doc", doc.contains("float scale(float k)"))
    }

    fun testCallToAnImportedFunctionShowsItsDocumentation() {
        // The function is declared in another file, which has no cached AST of its own: the
        // documentation comes from the compiled file's index, which includes the imported module.
        myFixture.configureByText(
            "test.bwsl",
            """
            module M {
                import Common

                run :: () -> float { return Common::hel<caret>per(1.0, 2.0); }
            }
            """.trimIndent()
        )
        BwslcAstHelper.parseAndCache(
            myFixture.file.text, myFixture.file.virtualFile.path,
            mapOf("Common" to "module Common {\n    helper :: (float a, float b) -> float { return a + b; }\n}\n")
        )

        val doc = generateDocAt(myFixture.caretOffset)
        assertNotNull("Expected documentation for the imported 'helper'", doc)
        assertTrue("Expected the qualified name, got: $doc", doc!!.contains("Common::helper()"))
        assertTrue("Expected the signature, got: $doc", doc.contains("float helper(float a, float b)"))
    }
}
