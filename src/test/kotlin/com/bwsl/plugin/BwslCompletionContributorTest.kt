package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BwslCompletionContributorTest : BasePlatformTestCase() {

    fun testSuggestsKeywordsTypesAndIntrinsicsInExpression() {
        myFixture.configureByText(
            "completion_test.bwsl",
            "module M { f1 :: () -> float { return <caret>; } }"
        )

        val lookups = myFixture.completeBasic()
        val strings = lookups.map { it.lookupString }

        assertTrue("expected 'return' keyword in completions", strings.contains("return"))
        assertTrue("expected 'float3' type keyword in completions", strings.contains("float3"))
        assertTrue("expected 'normalize' intrinsic in completions", strings.contains("normalize"))
    }

    fun testIntrinsicCompletionHasTailAndTypeText() {
        myFixture.configureByText(
            "completion_test.bwsl",
            "module M { f1 :: () -> float { return <caret>; } }"
        )

        val lookups = myFixture.completeBasic()
        val sinElement = lookups.first { it.lookupString == "sin" }

        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        sinElement.renderElement(presentation)

        assertEquals("(...)", presentation.tailText)
        assertEquals("intrinsic", presentation.typeText)
    }

    fun testTypeKeywordCompletionHasTypeText() {
        myFixture.configureByText(
            "completion_test.bwsl",
            "module M { f1 :: () -> float { return <caret>; } }"
        )

        val lookups = myFixture.completeBasic()
        val float2Element = lookups.first { it.lookupString == "float2" }

        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation()
        float2Element.renderElement(presentation)

        assertEquals("type", presentation.typeText)
    }
}
