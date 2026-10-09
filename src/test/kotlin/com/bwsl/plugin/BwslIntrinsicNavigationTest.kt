package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Ctrl+click on an intrinsic opens its page in the official documentation. */
class BwslIntrinsicNavigationTest : BasePlatformTestCase() {

    private lateinit var originalOpen: (String) -> Unit
    private val opened = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        originalOpen = BwslBrowser.open
        BwslBrowser.open = { opened += it }
    }

    override fun tearDown() {
        try {
            BwslBrowser.open = originalOpen
        } finally {
            super.tearDown()
        }
    }

    private fun body(call: String) = "module M {\n    f :: (float a, float b) -> float {\n        return $call;\n    }\n}"

    private fun findTarget(sourceWithCaret: String): BwslDocumentationTarget? {
        myFixture.configureByText("nav.bwsl", sourceWithCaret)
        return findDocumentationTargetFor(myFixture.file.findElementAt(myFixture.caretOffset)!!)
    }

    fun testAnIntrinsicLeadsToItsPageInTheDocumentation() {
        val target = findTarget(body("le<caret>rp(a, b, 0.5)"))!!

        assertEquals("lerp", target.subject)
        assertEquals("https://www.bwsl.dev/docs/intrinsics/lerp", target.url)
    }

    fun testAnIntrinsicWithoutAPageHasNoTarget() {
        for (name in listOf("fmod", "memoryBarrier")) {
            assertNull(name, findTarget(body("${name.take(2)}<caret>${name.drop(2)}(a, b)")))
        }
    }

    fun testTheLengthOfAnArrayIsNotTheVectorLengthIntrinsic() {
        assertNull(findTarget("module M {\n    f :: (float[3] values) -> int {\n        return values.len<caret>gth();\n    }\n}"))
        assertEquals("length", findTarget(body("len<caret>gth(a)"))!!.subject)
    }

    fun testTheDiscardKeywordLeadsToItsPage() {
        val target = findTarget("module M {\n    f :: (float a) -> float {\n        if (a < 0.0) { dis<caret>card; }\n        return a;\n    }\n}")

        assertEquals("https://www.bwsl.dev/docs/intrinsics/discard", target?.url)
    }

    fun testAnOrdinaryNameAndAFunctionOfTheUsersHaveNoTarget() {
        assertNull(findTarget(body("g<caret>oo(a)")))
        assertNull(findTarget("module M {\n    f<caret>oo :: () -> float { return 1.0; }\n}"))
    }

    fun testTheTargetShowsWhereItLeadsAndOpensThePage() {
        val target = findTarget(body("mo<caret>d(a, b)"))!!

        assertEquals("mod", target.presentation.presentableText)
        assertEquals("BWSL documentation", target.presentation.locationString)
        assertTrue(target.canNavigate())

        target.navigate(true)

        assertEquals(listOf("https://www.bwsl.dev/docs/intrinsics/mod"), opened)
    }

    fun testTheGotoDeclarationActionOpensThePage() {
        myFixture.configureByText("nav.bwsl", body("le<caret>rp(a, b, 0.5)"))

        myFixture.performEditorAction("GotoDeclaration")

        assertEquals(listOf("https://www.bwsl.dev/docs/intrinsics/lerp"), opened)
    }
}
