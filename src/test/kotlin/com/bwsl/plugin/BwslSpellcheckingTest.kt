package com.bwsl.plugin

import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Spell-checking of comments and strings, and only those. */
class BwslSpellcheckingTest : BasePlatformTestCase() {

    /** The words the checker reports as typos in [source], in the order of the file. */
    private fun collectTypos(source: String): List<String> {
        myFixture.enableInspections(GrazieSpellCheckingInspection::class.java)
        myFixture.configureByText("typos.bwsl", source)
        return myFixture.doHighlighting()
            .filter { it.description?.startsWith("Typo") == true }
            .sortedBy { it.startOffset }
            .map { source.substring(it.startOffset, it.endOffset) }
    }

    fun testATypoInALineCommentIsReported() {
        assertEquals(listOf("functoin"), collectTypos("// This functoin is fine otherwise\nmodule M {\n}"))
    }

    fun testATypoInABlockCommentIsReported() {
        assertEquals(listOf("computtion"), collectTypos("/* a long computtion\n   over two lines */\nmodule M {\n}"))
    }

    fun testATypoInATrailingCommentIsReported() {
        assertEquals(listOf("retrun"), collectTypos("module M {\n    f :: () -> float { return 1.0; } // retrun the constant\n}"))
    }

    fun testATypoInAStringLiteralIsReported() {
        assertEquals(listOf("Fragmnet"), collectTypos("pipeline P {\n    pass \"Fragmnet pass\" {\n    }\n}"))
    }

    fun testNamesAreNotChecked() {
        assertEquals(
            emptyList<String>(),
            collectTypos("module Mispeled {\n    functoinName :: (float valeu) -> float {\n        float resutl = valeu;\n        return resutl;\n    }\n}")
        )
    }

    fun testCorrectEnglishIsNotReported() {
        assertEquals(emptyList<String>(), collectTypos("// Returns the normal of the surface at this point.\nmodule M {\n}"))
    }

    fun testTheTermsOfShadingAreKnown() {
        assertEquals(
            "words from the bundled dictionary are not typos",
            emptyList<String>(),
            collectTypos("// bwslc compiles to SPIRV and GLSL; Fresnel-Schlick, GGX, swizzle, smoothstep and uv.\nmodule M {\n}")
        )
    }

    fun testALinkInACommentIsNotChecked() {
        assertEquals(emptyList<String>(), collectTypos("// Reference: https://exmpl.example.org/pagee/fooo\nmodule M {\n}"))
    }
}
