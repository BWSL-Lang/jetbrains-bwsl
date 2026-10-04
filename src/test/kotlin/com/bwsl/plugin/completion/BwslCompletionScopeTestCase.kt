package com.bwsl.plugin.completion

import com.bwsl.plugin.*

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base class for completion tests that drive [BwslAstCache] from a real bwslc AST: each test
 * method supplies only a `<caret>`-annotated source snippet, and [assertCompletions] compiles the
 * caret-free source via [BwslcAstHelper], caches the resulting AST, and asserts which lookup
 * strings are present/absent at the caret position.
 */
abstract class BwslCompletionScopeTestCase : BasePlatformTestCase() {

    protected val pipelineBodyKeywords = setOf("attributes", "resources", "variants", "pass")
    protected val structuralKeywords = pipelineBodyKeywords + setOf("module", "pipeline", "vertex", "fragment", "compute")

    private var fileCounter = 0

    protected fun assertCompletions(
        sourceWithCaret: String,
        present: Set<String> = emptySet(),
        absent: Set<String> = emptySet(),
        modules: Map<String, String> = emptyMap()
    ) {
        val cleanSource = sourceWithCaret.replace("<caret>", "")
        myFixture.configureByText("scope_test_${fileCounter++}.bwsl", sourceWithCaret)
        BwslcAstHelper.parseAndCache(cleanSource, myFixture.file.virtualFile.path, modules)

        val strings = myFixture.completeBasic().map { it.lookupString }

        for (expected in present) {
            assertTrue("expected '$expected' to be suggested, got: $strings", strings.contains(expected))
        }
        for (unexpected in absent) {
            assertFalse("expected '$unexpected' NOT to be suggested, got: $strings", strings.contains(unexpected))
        }
    }
}
