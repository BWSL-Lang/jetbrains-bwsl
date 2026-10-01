package com.bwsl.plugin.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation

/**
 * Completion of the names a function or stage body can use: its parameters, the locals declared so
 * far in the blocks around the caret, loop variables, and consts. Driven by the cached bwslc AST.
 */
class BwslCompletionLocalsTest : BwslCompletionScopeTestCase() {

    fun testParametersAndEarlierLocalsAreSuggestedButNotLaterOnesOrOtherFunctions() {
        checkCompletions(
            """
            module M {
                helper :: (float alpha, float2 beta) -> float {
                    float first = alpha;
                    <caret>float second = first;
                    float third = second;
                    return third;
                }
                other :: (float gamma) -> float { return gamma; }
            }
            """.trimIndent(),
            present = setOf("alpha", "beta", "first"),
            absent = setOf("second", "third", "gamma")
        )
    }

    fun testLocalsOfAnEnclosingBlockAreVisibleInsideANestedBlock() {
        checkCompletions(
            """
            module M {
                f :: (float x) -> float {
                    float outer = x;
                    if (x > 0.0) {
                        float inner = outer;
                        <caret>outer = inner;
                    }
                    float after = outer;
                    return after;
                }
            }
            """.trimIndent(),
            present = setOf("x", "outer", "inner"),
            absent = setOf("after")
        )
    }

    fun testLocalsOfAClosedBlockAreNotSuggestedAfterIt() {
        checkCompletions(
            """
            module M {
                f :: (float x) -> float {
                    float outer = x;
                    if (x > 0.0) {
                        float inner = outer;
                        outer = inner;
                    }
                    <caret>float after = outer;
                    return after;
                }
            }
            """.trimIndent(),
            present = setOf("x", "outer"),
            absent = setOf("inner", "after")
        )
    }

    fun testCStyleLoopVariableIsVisibleInTheLoopButNotAfterIt() {
        val source = """
            module M {
                f :: (float x) -> float {
                    float outer = x;
                    for (int i = 0; i < 3; i++) {
                        float inLoop = outer;
                        <caret>outer = inLoop + float(i);
                    }
                    float after = outer;
                    return after;
                }
            }
        """.trimIndent()
        checkCompletions(source, present = setOf("i", "inLoop", "outer"), absent = setOf("after"))
        checkCompletions(
            source.replace("<caret>outer = inLoop", "outer = inLoop").replace("float after", "<caret>float after"),
            present = setOf("outer"),
            absent = setOf("i", "inLoop")
        )
    }

    fun testRangeLoopVariableIsVisibleInTheLoopButNotAfterIt() {
        val source = """
            module M {
                f :: (float x) -> float {
                    float outer = x;
                    for (n in 0..4) {
                        <caret>outer = outer + float(n);
                    }
                    float after = outer;
                    return after;
                }
            }
        """.trimIndent()
        checkCompletions(source, present = setOf("n", "outer"), absent = setOf("after"))
        checkCompletions(
            source.replace("<caret>outer = outer", "outer = outer").replace("float after", "<caret>float after"),
            present = setOf("outer"),
            absent = setOf("n")
        )
    }

    fun testCollectionLoopVariableIsVisibleInTheLoop() {
        checkCompletions(
            """
            module M {
                f :: (float[4] values) -> float {
                    float outer = 0.0;
                    for (item in values) {
                        <caret>outer = outer + item;
                    }
                    return outer;
                }
            }
            """.trimIndent(),
            present = setOf("item", "values", "outer")
        )
    }

    fun testEveryVariableOfAMultiRangeForeachIsVisibleInItsBody() {
        checkCompletions(
            """
            module M {
                f :: () -> float {
                    float outer = 0.0;
                    foreach (p in 0..2, q in 0..2) {
                        <caret>outer = outer + float(p + q);
                    }
                    return outer;
                }
            }
            """.trimIndent(),
            present = setOf("p", "q", "outer")
        )
    }

    fun testShaderStageSeesItsOwnLocalsAndTheConstsAroundItButNotOtherStagesOrFunctions() {
        checkCompletions(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                const float PIPE_K = 2.0;
                pass "Main" {
                    use attributes { position }
                    const float PASS_K = 3.0;
                    outputs {
                        result: float4
                    }
                    helper :: (float hp) -> float { return hp; }
                    vertex {
                        float v = PIPE_K;
                        output.pos = attributes.position * v;
                        <caret>float after = v;
                    }
                    fragment {
                        float f = PASS_K;
                        output.result = float4(f);
                    }
                }
            }
            """.trimIndent(),
            present = setOf("v", "PIPE_K", "PASS_K"),
            absent = setOf("hp", "f", "after")
        )
    }

    fun testModuleLevelConstIsSuggestedEvenWhenDeclaredAfterTheFunction() {
        checkCompletions(
            """
            module M {
                f :: () -> float { return <caret>LIMIT; }
                const float LIMIT = 4.0;
            }
            """.trimIndent(),
            present = setOf("LIMIT")
        )
    }

    fun testStructMethodSeesItsParametersAndLocals() {
        checkCompletions(
            """
            module M {
                struct S {
                    float w;

                    scale :: (float k) -> float {
                        float a = w * k;
                        return <caret>a;
                    }
                }
            }
            """.trimIndent(),
            present = setOf("k", "a")
        )
    }

    fun testNothingIsSuggestedAsALocalAfterADot() {
        checkCompletions(
            """
            module M {
                f :: (float2 v) -> float {
                    float s = v.<caret>x;
                    return s;
                }
            }
            """.trimIndent(),
            absent = setOf("v", "s")
        )
    }

    fun testNothingIsSuggestedAsALocalAfterAModuleQualifier() {
        checkCompletions(
            """
            module M {
                g :: () -> float { return 1.0; }
                f :: (float p) -> float { return M::<caret>g(); }
            }
            """.trimIndent(),
            absent = setOf("p")
        )
    }

    fun testNoLocalsAreSuggestedOutsideAFunctionBody() {
        checkCompletions(
            """
            module M {
                <caret>
                f :: (float p) -> float { return p; }
            }
            """.trimIndent(),
            absent = setOf("p")
        )
    }

    fun testLocalsShowTheirTypeAndKind() {
        val source = """
            module M {
                f :: (float alpha) -> float {
                    const float K = 2.0;
                    <caret>float z = alpha * K;
                    return z;
                }
            }
        """.trimIndent()
        myFixture.configureByText("locals_presentation.bwsl", source)
        com.bwsl.plugin.completion.BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val items = myFixture.completeBasic().associateBy { it.lookupString }

        fun render(name: String): LookupElementPresentation =
            LookupElementPresentation().also { items.getValue(name).renderElement(it) }

        assertEquals("float", render("alpha").typeText)
        assertEquals(" parameter", render("alpha").tailText)
        assertEquals("float", render("K").typeText)
        assertEquals(" constant", render("K").tailText)
    }

    fun testLocalsSortAheadOfKeywordsTypesAndIntrinsics() {
        val source = """
            module M {
                f :: (float alpha, float2 beta) -> float {
                    float first = alpha;
                    <caret>float second = first;
                    return second;
                }
            }
        """.trimIndent()
        myFixture.configureByText("locals_order.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val firstThree = myFixture.completeBasic().map { it.lookupString }.take(3).toSet()

        assertEquals(setOf("alpha", "beta", "first"), firstThree)
    }

    fun testNoLocalsAreSuggestedWithoutACachedAst() {
        // No fallback to scanning the text: with no AST there is nothing to suggest.
        myFixture.configureByText(
            "locals_no_ast.bwsl",
            "module M { f :: (float alpha) -> float { return <caret>alpha; } }"
        )

        val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertFalse("alpha must not be suggested without a cached AST, got: $strings", strings.contains("alpha"))
    }
}
