package com.bwsl.plugin

import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.elementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** A ```bwsl code block in a Markdown file is BWSL: the Markdown plugin injects the language whose id the fence names. */
class BwslMarkdownFenceTest : BasePlatformTestCase() {

    private fun findInjectedAt(markdown: String, word: String): com.intellij.psi.PsiElement? {
        val file = myFixture.configureByText("notes.md", markdown)
        return InjectedLanguageManager.getInstance(project).findInjectedElementAt(file, markdown.indexOf(word))
    }

    private fun fence(info: String) = "# Shaders\n\n$info\npipeline P {\n    pass \"Main\" { }\n}\n```\n\nText after.\n"

    fun testABwslFenceIsLexedAsBwsl() {
        val element = findInjectedAt(fence("```bwsl"), "pipeline")

        assertNotNull("the fence is injected", element)
        assertEquals(BwslLanguage, element!!.language)
        assertEquals(BwslTokenTypes.KW_PIPELINE, element.elementType)
    }

    fun testTheInfoStringIsMatchedWhateverItsCase() {
        assertEquals(BwslLanguage, findInjectedAt(fence("```BWSL"), "pipeline")?.language)
    }

    fun testAnotherLanguagesFenceIsNotBwsl() {
        val element = findInjectedAt(fence("```glsl"), "pipeline")

        assertTrue("got ${element?.language}", element == null || element.language != BwslLanguage)
    }

    fun testTextOutsideTheFenceIsNotBwsl() {
        assertNull(findInjectedAt(fence("```bwsl"), "Text after"))
    }

    fun testTheFenceIsColouredTokenByTokenByTheBwslHighlighter() {
        val markdown = fence("```bwsl")
        myFixture.configureByText("notes.md", markdown)
        val start = markdown.indexOf("pipeline")

        // An injected fragment is coloured by its own language's highlighter, one piece per token of its lexer.
        val syntax = myFixture.doHighlighting().filter { it.severity == HighlightInfoType.INJECTED_FRAGMENT_SYNTAX_SEVERITY && it.forcedTextAttributes?.isEmpty == false }

        assertTrue("no piece for the pipeline keyword: " + syntax.map { it.startOffset to it.endOffset }, syntax.any { it.startOffset == start && it.endOffset == start + "pipeline".length })
    }
}
