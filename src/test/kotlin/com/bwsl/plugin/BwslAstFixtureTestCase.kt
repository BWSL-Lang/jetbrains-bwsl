package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for tests that need a configured file with a real bwslc AST cached for it, as the AST
 * annotator would have left it.
 */
abstract class BwslAstFixtureTestCase : BasePlatformTestCase() {

    private lateinit var originalCompilerPath: String

    /** Points the plugin at the real bwslc, for the features that run it themselves (the project index, rename's conflict check). */
    override fun setUp() {
        super.setUp()
        // The cache is global: what an earlier test cached must not be seen by this one.
        BwslAstCache.clear()
        originalCompilerPath = BwslSettings.getInstance().compilerPath
        BwslSettings.getInstance().compilerPath = System.getProperty("bwslc.path")
            ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")
    }

    override fun tearDown() {
        try {
            BwslSettings.getInstance().compilerPath = originalCompilerPath
        } finally {
            super.tearDown()
        }
    }

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

    /** The failure's message and its causes' messages, so a refusal wrapped by the refactoring framework is still recognisable. */
    protected fun describeFailure(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }.ifEmpty { "no failure" }
}
