package com.bwsl.plugin

import java.io.File
import java.nio.file.Files

/**
 * Documentation comments (`///` lines and `/** */` blocks) above a declaration, found from the text
 * and shown in the documentation popup of the function, constant, struct or enum they document.
 */
class BwslDocCommentsTest : BwslAstFixtureTestCase() {

    /** The doc comment above the declaration whose name is at the caret of [sourceWithCaret]. */
    private fun findDocAtCaret(sourceWithCaret: String): String? {
        myFixture.configureByText("doc.bwsl", sourceWithCaret)
        return findDocCommentAbove(myFixture.file, myFixture.editor.caretModel.offset)
    }

    // --- finding the comment ----------------------------------------------------------------------------

    fun testLineDocCommentsDirectlyAboveAFunctionAreItsDocumentation() {
        assertEquals(
            "Returns the interpolation factor.\nOf value in [a, b].",
            findDocAtCaret("module M {\n    /// Returns the interpolation factor.\n    /// Of value in [a, b].\n    inverse<caret>_lerp :: (float a) -> float { return a; }\n}")
        )
    }

    fun testABlockDocCommentIsAlsoDocumentation() {
        assertEquals(
            "Scales a value.\n\nBy a factor.",
            findDocAtCaret("module M {\n    /**\n     * Scales a value.\n     *\n     * By a factor.\n     */\n    sc<caret>ale :: (float a) -> float { return a; }\n}")
        )
    }

    fun testADocCommentOfADeclarationThatStartsWithAKeywordIsFound() {
        assertEquals(
            "Circle constant pi.",
            findDocAtCaret("module M {\n    /// Circle constant pi.\n    const float P<caret>I = 3.14;\n}")
        )
    }

    fun testABlankLineOrAnOrdinaryCommentBetweenBreaksTheAssociation() {
        assertNull(findDocAtCaret("module M {\n    /// Not about f.\n\n    f<caret> :: () -> float { return 1.0; }\n}"))
        assertNull(findDocAtCaret("module M {\n    /// About something else.\n    // a note\n    f<caret> :: () -> float { return 1.0; }\n}"))
    }

    fun testOrdinaryCommentsAreNotDocumentation() {
        assertNull(findDocAtCaret("module M {\n    // Just a comment.\n    f<caret> :: () -> float { return 1.0; }\n}"))
        assertNull("four slashes is a divider, not documentation", findDocAtCaret("module M {\n    //// ---- section ----\n    f<caret> :: () -> float { return 1.0; }\n}"))
    }

    fun testADeclarationWithoutAnyCommentHasNoDocumentation() {
        assertNull(findDocAtCaret("module M {\n    f<caret> :: () -> float { return 1.0; }\n}"))
    }

    fun testOnlyTheCommentDirectlyAboveItsOwnDeclarationCounts() {
        assertEquals(
            "About g.",
            findDocAtCaret("module M {\n    /// About f.\n    f :: () -> float { return 1.0; }\n    /// About g.\n    g<caret>g :: () -> float { return 2.0; }\n}")
        )
    }

    // --- turning it into HTML ----------------------------------------------------------------------------

    fun testTheTextIsEscapedAndRunsTogetherInParagraphs() {
        assertEquals("Returns a &lt; b and c &amp; d.<br/><br/>Second paragraph.", renderDocCommentHtml("Returns a < b\nand c & d.\n\nSecond paragraph."))
    }

    fun testCodeInBackticksAndWebAddressesAreMarkedUp() {
        assertEquals(
            "Like <code>lerp</code>. Reference: <a href=\"https://example.org/a_b\">https://example.org/a_b</a>.",
            renderDocCommentHtml("Like `lerp`. Reference: https://example.org/a_b.")
        )
    }

    // --- in the popup -------------------------------------------------------------------------------------

    private val documented = """
        module M {
            /// Returns the half of a value.
            ///
            /// Reference: https://example.org/half
            half :: (float v) -> float { return v * 0.5; }

            /// Circle constant pi.
            const float PI = 3.14;

            /// A point in the plane.
            struct Point {
                /// The horizontal position.
                float x;
                float y;
            }

            plain :: (float v) -> float { return v; }

            f :: (float x) -> float {
                Point p;
                return half(x) + PI + plain(p.x);
            }
        }
    """.trimIndent()

    private fun docAt(needle: String, occurrence: Int = 0): String? {
        var index = -1
        repeat(occurrence + 1) { index = myFixture.file.text.indexOf(needle, index + 1) }
        return generateDocAt(index)
    }

    fun testHoveringACallShowsTheFunctionsDocComment() {
        configureAndCache(documented)

        val doc = docAt("half(x)")

        assertNotNull(doc)
        assertTrue("the signature is still there, got: $doc", doc!!.contains("float half(float v)"))
        assertTrue("the first paragraph, got: $doc", doc.contains("Returns the half of a value."))
        assertTrue("a link, got: $doc", doc.contains("<a href=\"https://example.org/half\">https://example.org/half</a>"))
    }

    fun testHoveringTheDeclarationShowsItsDocCommentToo() {
        configureAndCache(documented)

        assertTrue(docAt("half ::")!!.contains("Returns the half of a value."))
    }

    fun testHoveringAConstantShowsItsDocComment() {
        configureAndCache(documented)

        val doc = docAt("PI + plain")

        assertNotNull("a use of the constant", doc)
        assertTrue(doc!!.contains("Circle constant pi."))
        assertTrue("the type is still shown, got: $doc", doc.contains("float PI"))
    }

    fun testHoveringAStructOrAFieldShowsItsDocComment() {
        configureAndCache(documented)

        val structDoc = docAt("Point p")
        assertNotNull("hovering the type of 'Point p;'", structDoc)
        assertTrue("got: $structDoc", structDoc!!.contains("A point in the plane."))

        val fieldDoc = docAt("x);")
        assertNotNull("hovering the field in p.x", fieldDoc)
        assertTrue("got: $fieldDoc", fieldDoc!!.contains("The horizontal position."))
    }

    fun testAFunctionWithoutADocCommentShowsOnlyItsSignature() {
        configureAndCache(documented)

        val doc = docAt("plain(p.x)")

        assertNotNull(doc)
        assertTrue(doc!!.contains("float plain(float v)"))
        assertFalse("nothing was written about it, got: $doc", doc.contains("Returns the half") || doc.contains("Circle constant"))
    }

    fun testEachOverloadShowsItsOwnDocComment() {
        configureAndCache(
            """
            module M {
                /// For a scalar.
                twice :: (float v) -> float { return v * 2.0; }

                /// For a vector.
                twice :: (float2 v) -> float2 { return v * 2.0; }

                f :: (float x, float2 y) -> float {
                    float a = twice(x);
                    float2 b = twice(y);
                    return a + b.x;
                }
            }
            """.trimIndent()
        )

        assertTrue(docAt("twice(x)")!!.contains("For a scalar."))
        assertTrue(docAt("twice(y)")!!.contains("For a vector."))
    }

    fun testADocCommentInAnotherFileIsShownForACallIntoIt() {
        configureAndCache(
            "module M {\n    import Other\n    f :: (float x) -> float {\n        return Other::helper(x);\n    }\n}",
            modules = mapOf("Other" to "module Other {\n    /// Helps with x, from the other file.\n    helper :: (float v) -> float { return v; }\n}\n")
        )

        val doc = docAt("helper(x)")

        assertNotNull(doc)
        assertTrue("got: $doc", doc!!.contains("Helps with x, from the other file."))
    }

    fun testADocCommentOfAStandardModuleIsShownOnceItsSourceHasBeenFetched() {
        val modules = System.getProperty("bwslc.path")?.let { File(it).parentFile?.parentFile?.resolve("modules") }
            ?.takeIf { it.resolve("math.bwsl").isFile } ?: return
        val originalRoot = BwslStdlibSources.cacheRoot
        val originalFetch = BwslStdlibSources.fetchText
        val cache = Files.createTempDirectory("bwsl_doc_stdlib_").toFile()
        try {
            BwslStdlibSources.cacheRoot = cache.toPath()
            BwslStdlibSources.forgetSession()
            BwslStdlibSources.fetchText = { url ->
                if (url.startsWith("https://api.github.com/")) "[]"
                else modules.resolve(url.substringAfterLast('/')).takeIf { it.isFile }?.readText()?.replace("\r\n", "\n")
            }
            configureAndCache("module M {\n    import Math\n    f :: (float x) -> float {\n        return Math::PI * x;\n    }\n}")
            assertFalse("not fetched yet, so no documentation", docAt("PI *")!!.contains("Circle constant"))

            BwslStdlibSources.download(collectStringFields(BwslAstCache.findRawRoot(myFixture.file.virtualFile.path)!!, "sourceUrl"))

            assertTrue(docAt("PI *")!!.contains("Circle constant pi."))
        } finally {
            BwslStdlibSources.cacheRoot = originalRoot
            BwslStdlibSources.fetchText = originalFetch
            BwslStdlibSources.forgetSession()
            cache.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }
}
