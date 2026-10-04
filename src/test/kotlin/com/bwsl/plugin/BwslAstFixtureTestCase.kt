package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for tests that need a configured file with a real bwslc AST cached for it, as the AST
 * annotator would have left it.
 */
abstract class BwslAstFixtureTestCase : BasePlatformTestCase() {

    /**
     * Configures the fixture file with [textWithCaret] (which may contain `<caret>`), compiles its
     * caret-free text with real bwslc and caches the AST. [modules] maps module names to sources and
     * is written to `-modules` paths. Returns the file's text without the caret marker.
     */
    protected fun configureAndCache(textWithCaret: String, modules: Map<String, String> = emptyMap()): String {
        myFixture.configureByText("test.bwsl", textWithCaret)
        val text = myFixture.file.text
        BwslcAstHelper.parseAndCache(text, myFixture.file.virtualFile.path, modules)
        return text
    }
}
