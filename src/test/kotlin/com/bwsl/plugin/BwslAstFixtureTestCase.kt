package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for tests that need a configured file with a real bwslc AST cached for it, as the AST
 * annotator would have left it.
 */
abstract class BwslAstFixtureTestCase : BasePlatformTestCase() {

    private lateinit var originalCompilerPath: String
    private lateinit var originalFetchText: (String) -> String?
    private lateinit var originalIntrinsicFetch: (String) -> String?
    private lateinit var originalIntrinsicRoot: java.nio.file.Path

    /** Points the plugin at the real bwslc, for the features that run it themselves (the project index, rename's conflict check). */
    override fun setUp() {
        super.setUp()
        // The cache is global: what an earlier test cached must not be seen by this one.
        BwslAstCache.clear()
        // Compiling a file that uses a standard module starts a download of its sources in the background:
        // a test never reaches the network (and a stray download must not land in another test's cache).
        originalFetchText = BwslStdlibSources.fetchText
        // The same for the intrinsic documentation: a hover asks the site for a page in the background.
        originalIntrinsicFetch = BwslIntrinsicDocs.fetchText
        originalIntrinsicRoot = BwslIntrinsicDocs.cacheRoot
        BwslIntrinsicDocs.fetchText = { null }
        BwslIntrinsicDocs.cacheRoot = java.nio.file.Files.createTempDirectory("bwsl_intrinsic_docs_")
        BwslIntrinsicDocs.reset()
        BwslStdlibSources.fetchText = { null }
        originalCompilerPath = BwslSettings.getInstance().compilerPath
        BwslSettings.getInstance().compilerPath = System.getProperty("bwslc.path")
            ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")
    }

    override fun tearDown() {
        try {
            BwslStdlibSources.fetchText = originalFetchText
            BwslIntrinsicDocs.cacheRoot.toFile().deleteRecursively()
            BwslIntrinsicDocs.cacheRoot = originalIntrinsicRoot
            BwslIntrinsicDocs.fetchText = originalIntrinsicFetch
            BwslIntrinsicDocs.reset()
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

    private val documentationProvider = BwslDocumentationProvider()

    /** The documentation popup the IDE would show for the element at [caretOffset] of the configured file, resolving what is hovered as the IDE does. */
    protected fun generateDocAt(caretOffset: Int): String? {
        val file = myFixture.file
        val original = file.findElementAt(caretOffset)!!
        var element = original
        val custom = documentationProvider.getCustomDocumentationElement(myFixture.editor, file, element, caretOffset)
        if (custom != null) {
            element = custom
        } else {
            element.parent?.references?.firstNotNullOfOrNull { it.resolve() }?.let { element = it }
        }
        return documentationProvider.generateDoc(element, original)
    }

    /** The failure's message and its causes' messages, so a refusal wrapped by the refactoring framework is still recognisable. */
    protected fun describeFailure(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { it.message.orEmpty() }.ifEmpty { "no failure" }
}
