package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What can be folded: multi-line blocks and comments. */
class BwslFoldingTest : BasePlatformTestCase() {

    /** The text each fold region covers and what it shows when folded, in the order of the file. */
    private fun collectFolds(source: String): List<Pair<String, String>> {
        myFixture.configureByText("fold.bwsl", source)
        return BwslFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
            .sortedBy { it.range.startOffset }
            .map { source.substring(it.range.startOffset, it.range.endOffset) to it.placeholderText.orEmpty() }
    }

    fun testAMultiLineFunctionBodyFoldsToBracesAndASingleLineBlockDoesNot() {
        val source = "module M {\n    f :: () -> float {\n        return 1.0;\n    }\n    g :: () -> float { return 2.0; }\n}"

        val folds = collectFolds(source)

        assertEquals(
            listOf(
                source.substring(source.indexOf('{')) to "{...}",
                "{\n        return 1.0;\n    }" to "{...}"
            ),
            folds
        )
    }

    fun testNestedBlocksFoldSeparately() {
        val folds = collectFolds("pipeline P {\n    pass \"Main\" {\n        vertex {\n            x = 1;\n        }\n    }\n}")

        assertEquals(3, folds.size)
        assertEquals(listOf("{...}", "{...}", "{...}"), folds.map { it.second })
    }

    fun testABlockCommentFoldsOnlyWhenItSpansLines() {
        val folds = collectFolds("/* one line */\n/* first\n   second */\nmodule M {}")

        assertEquals(listOf("/* first\n   second */" to "/*...*/"), folds)
    }

    fun testARunOfLineCommentsFoldsToItsFirstLine() {
        val folds = collectFolds("// Fresnel-Schlick approximation\n// of the specular term\n// (see the paper)\nmodule M {}")

        assertEquals(
            listOf("// Fresnel-Schlick approximation\n// of the specular term\n// (see the paper)" to "// Fresnel-Schlick approximation..."),
            folds
        )
    }

    fun testSeparateOrTrailingLineCommentsAreNotARun() {
        val folds = collectFolds(
            "// alone\n\n// after a blank line\nmodule M {\n    x = 1; // trailing\n    y = 2; // trailing too\n}"
        )

        assertEquals(listOf("{\n    x = 1; // trailing\n    y = 2; // trailing too\n}" to "{...}"), folds)
    }

    fun testBracesInsideStringsAndCommentsAreNotBlocks() {
        val folds = collectFolds("module M {\n    s = \"{\n\";\n    // }\n}")

        assertEquals(1, folds.size)
    }

    fun testAnUnbalancedFileStillFoldsWhatIsBalanced() {
        val folds = collectFolds("module M {\n    f :: () -> float {\n        return 1.0;\n    }\n")

        assertEquals(listOf("{\n        return 1.0;\n    }" to "{...}"), folds)
    }
}
