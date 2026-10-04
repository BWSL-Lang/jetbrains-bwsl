package com.bwsl.plugin.completion

import com.bwsl.plugin.doesTypeMatch
import com.intellij.codeInsight.completion.CompletionType

/**
 * Ranking by what the caret expects, and smart completion (Ctrl+Shift+Space). What is expected comes
 * from the position: the parameter being filled in, the declared type being initialised, the target of
 * an assignment, the return type. A suggestion of that type sorts ahead of the rest.
 */
class BwslCompletionExpectedTypeTest : BwslCompletionScopeTestCase() {

    private fun module(body: String): String = """
        module M {
            const float K = 1.0;

            struct Light {
                float intensity;
                float3 color;
                scaled :: (float k, float2 p) -> float { return k; }
            }

            half :: (float v) -> float { return v * 0.5; }
            blend :: (float a, float2 b, int c) -> float { return a; }

            f :: (float x, float2 y, int n, float3 v, Light l) -> float {
                $body
                return x;
            }
        }
    """.trimIndent()

    private var fileCounter = 0

    /** The lookup strings in the order they are shown for basic completion at the caret of [sourceWithCaret]. */
    private fun completeInOrder(sourceWithCaret: String, type: CompletionType = CompletionType.BASIC): List<String> {
        myFixture.configureByText("expected_${fileCounter++}.bwsl", sourceWithCaret)
        BwslcAstHelper.parseAndCache(sourceWithCaret.replace("<caret>", ""), myFixture.file.virtualFile.path)
        return myFixture.complete(type).orEmpty().map { it.lookupString }
    }

    private fun assertRankedAhead(order: List<String>, first: String, vararg later: String) {
        val firstIndex = order.indexOf(first)
        assertTrue("$first is suggested, got: $order", firstIndex >= 0)
        for (name in later) {
            val index = order.indexOf(name)
            assertTrue("$name is suggested, got: $order", index >= 0)
            assertTrue("$first should come before $name, got: $order", firstIndex < index)
        }
    }

    // --- ranking ---------------------------------------------------------------------------------------

    fun testTheFirstArgumentOfACallPrefersWhatTheFirstParameterTakes() {
        assertRankedAhead(completeInOrder(module("float t = blend(<caret>y, y, n);")), "x", "y", "n", "v")
    }

    fun testTheSecondArgumentPrefersTheSecondParametersType() {
        assertRankedAhead(completeInOrder(module("float t = blend(x, <caret>x, n);")), "y", "x", "n", "v")
    }

    fun testTheThirdArgumentPrefersTheThirdParametersType() {
        assertRankedAhead(completeInOrder(module("float t = blend(x, y, <caret>x);")), "n", "x", "y", "v")
    }

    fun testTheInitialiserOfADeclarationPrefersTheDeclaredType() {
        assertRankedAhead(completeInOrder(module("float2 q = <caret>x;")), "y", "x", "n", "v")
        assertRankedAhead(completeInOrder(module("int k = <caret>x;")), "n", "x", "y", "v")
    }

    fun testTheRightSideOfAnAssignmentPrefersTheTypeOfTheTarget() {
        assertRankedAhead(completeInOrder(module("float2 q;\n                q = <caret>x;")), "y", "x", "n")
        assertRankedAhead(completeInOrder(module("l.intensity = <caret>y;")), "x", "y", "n", "v")
    }

    fun testAReturnPrefersTheFunctionsReturnType() {
        val source = """
            module M {
                g :: (float x, float2 y, int n) -> float2 {
                    return <caret>x;
                }
            }
        """.trimIndent()

        assertRankedAhead(completeInOrder(source), "y", "x", "n")
    }

    fun testAMethodsArgumentPrefersItsParametersType() {
        assertRankedAhead(completeInOrder(module("float t = l.scaled(<caret>y, y);")), "x", "y", "n")
        assertRankedAhead(completeInOrder(module("float t = l.scaled(x, <caret>x);")), "y", "x", "n")
    }

    fun testAnIntrinsicsArgumentPrefersTheTypesItsClassAllows() {
        // dot takes vectors: the float2 and float3 come before the float and the int.
        val order = completeInOrder(module("float t = dot(<caret>v, v);"))
        assertRankedAhead(order, "y", "x", "n")
        assertRankedAhead(order, "v", "x", "n")
        // saturate takes any float type, so all three float values beat the int.
        val saturated = completeInOrder(module("float t = saturate(<caret>x);"))
        assertRankedAhead(saturated, "x", "n")
        assertRankedAhead(saturated, "y", "n")
        assertRankedAhead(saturated, "v", "n")
    }

    fun testTheTypeMatchAlsoRanksFunctionsConstantsAndFields() {
        val order = completeInOrder(module("float2 q = <caret>x;"))
        // Nothing here returns a float2, but for a float the constant and the functions that return one lead.
        val floats = completeInOrder(module("float t = <caret>x;"))

        assertRankedAhead(floats, "K", "n", "v")
        assertRankedAhead(floats, "half", "n", "v")
        assertTrue(order.isNotEmpty())
    }

    fun testNothingIsRankedWhereNothingIsExpected() {
        val order = completeInOrder(module("float t = x + <caret>y;"))

        assertTrue("every name is still suggested, got: $order", order.containsAll(listOf("x", "y", "n", "v", "K", "half")))
    }

    // --- smart completion ---------------------------------------------------------------------------------

    fun testSmartCompletionOffersOnlyValuesOfTheDeclaredType() {
        val strings = completeInOrder(module("float t = <caret>x;"), CompletionType.SMART)

        assertTrue("floats and what returns one, got: $strings", strings.containsAll(listOf("x", "K", "half", "blend", "f")))
        for (excluded in listOf("y", "n", "v", "l", "return", "float", "abs")) {
            assertFalse("$excluded is not a float, got: $strings", strings.contains(excluded))
        }
    }

    fun testSmartCompletionInAnArgumentOffersOnlyWhatTheParameterTakes() {
        val strings = completeInOrder(module("float t = half(<caret>x);"), CompletionType.SMART)

        assertTrue(strings.containsAll(listOf("x", "K")))
        assertTrue("only floats, got: $strings", strings.none { it == "y" || it == "n" || it == "v" })
    }

    fun testSmartCompletionAfterADotOffersTheMembersOfTheExpectedType() {
        val strings = completeInOrder(module("float t = l.<caret>intensity;"), CompletionType.SMART)

        assertTrue("a float field and a float method, got: $strings", strings.containsAll(listOf("intensity", "scaled")))
        assertFalse("color is a float3, got: $strings", strings.contains("color"))
    }

    fun testSmartCompletionOffersEveryTypedValueWhenNothingIsExpected() {
        val strings = completeInOrder(module("float t = x + <caret>y;"), CompletionType.SMART)

        assertTrue("got: $strings", strings.containsAll(listOf("x", "y", "n", "v", "K", "half")))
        assertFalse("keywords are not values, got: $strings", strings.contains("return"))
    }

    // --- matching ---------------------------------------------------------------------------------------------

    fun testTypesMatchIgnoringAModuleQualifierAndNothingMatchesNothing() {
        assertTrue(doesTypeMatch("Light", setOf("Lighting::Light")))
        assertTrue(doesTypeMatch("Shapes::Circle", setOf("Circle", "float")))
        assertFalse(doesTypeMatch("float2", setOf("float", "float3")))
        assertFalse(doesTypeMatch(null, setOf("float")))
        assertFalse(doesTypeMatch("float", emptySet()))
    }
}
