package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Ctrl+click on a keyword goes to the page of the official documentation that explains it. */
class BwslKeywordNavigationTest : BasePlatformTestCase() {

    private fun findUrl(sourceWithCaret: String): String? {
        myFixture.configureByText("keyword.bwsl", sourceWithCaret)
        return findDocumentationTargetFor(myFixture.file.findElementAt(myFixture.caretOffset)!!)?.url
    }

    private fun docs(page: String) = "https://www.bwsl.dev/docs/$page"

    private val pipeline = """
        pipeline P {
            attributes { position: float4 }
            resources { tex: texture2d }
            variants { QUALITY: int }
            pass "Main" {
                use attributes { position }
                outputs { color: float4 }
                vertex { output.pos = attributes.position; }
                fragment { output.color = float4(1.0); }
            }
        }
    """.trimIndent()

    private fun inPipeline(word: String, occurrence: String = word) =
        pipeline.replaceFirst(occurrence, occurrence.replaceFirst(word, "${word.take(1)}<caret>${word.drop(1)}"))

    fun testModulesImportsAndSubmodulesLeadToTheModulesPage() {
        for (word in listOf("module", "import", "submodule", "using")) {
            // The caret goes inside the keyword.
            assertEquals(word, docs("language/modules"), findUrl("${word.take(1)}<caret>${word.drop(1)} X"))
        }
    }

    fun testPipelinePassAndTheirBlocksLeadToTheirPages() {
        assertEquals(docs("language/pipeline"), findUrl(inPipeline("pipeline")))
        assertEquals(docs("language/pass"), findUrl(inPipeline("pass", "pass \"Main\"")))
        assertEquals(docs("language/pipeline"), findUrl(inPipeline("vertex")))
        assertEquals(docs("language/pipeline"), findUrl(inPipeline("fragment")))
    }

    fun testAttributesAreToldApartByWhatFollowsThem() {
        assertEquals(docs("language/vertex-attributes"), findUrl(inPipeline("attributes", "attributes {")))
        assertEquals(docs("language/vertex-attributes"), findUrl(inPipeline("use")))
        assertEquals(docs("language/shader-io"), findUrl(inPipeline("attributes", "attributes.position")))
    }

    fun testInputAndOutputLeadToShaderIoOnlyAsANamespace() {
        assertEquals(docs("language/shader-io"), findUrl(inPipeline("output", "output.pos")))
        assertEquals(docs("language/shader-io"), findUrl(inPipeline("outputs")))
        assertNull("a variable that happens to be called output", findUrl("module M {\n    f :: (float outp<caret>ut) -> float { return output; }\n}"))
    }

    fun testResourcesAndVariantsLeadToTheirPages() {
        assertEquals(docs("language/resources"), findUrl(inPipeline("resources")))
        assertEquals(docs("language/shader-variants"), findUrl(inPipeline("variants")))
    }

    fun testLoopKeywordsLeadToTheLoopsPageAndEvalToItsOwn() {
        val body = "module M {\n    f :: () -> float {\n        float s = 0.0;\n        KEYWORD\n        return s;\n    }\n}"
        val loops = mapOf(
            "for (n in 0..3) { s = s + 1.0; }" to "for", "while (s < 3.0) { s = s + 1.0; }" to "while",
            "loop (4) { s = s + 1.0; }" to "loop", "foreach (v in values) { s = s + v; }" to "foreach"
        )
        for ((statement, word) in loops) {
            val marked = statement.replaceFirst(word, "${word.take(1)}<caret>${word.drop(1)}")
            assertEquals(word, docs("language/loops"), findUrl(body.replace("KEYWORD", marked)))
        }
        assertEquals(docs("language/eval"), findUrl(body.replace("KEYWORD", "e<caret>val int i = 0;")))
    }

    fun testStructsAndEnumsLeadToTheirTypePages() {
        assertEquals(docs("types/structs"), findUrl("module M {\n    s<caret>truct S { float a; }\n}"))
        assertEquals(docs("types/enums"), findUrl("module M {\n    e<caret>num E { A, B }\n}"))
    }

    fun testComputeLeadsToTheComputePage() {
        assertEquals(docs("language/compute-shaders"), findUrl("pipeline P {\n    pass \"A\" {\n        c<caret>ompute \"Main\" [8, 1, 1] { }\n    }\n}"))
    }

    fun testKeywordsWithoutAPageAndNamesHaveNoTarget() {
        assertNull(findUrl("module M {\n    f :: (float a) -> float {\n        i<caret>f (a > 0.0) { return a; }\n        return 0.0;\n    }\n}"))
        assertNull(findUrl("module M {\n    f :: (float a) -> fl<caret>oat { return a; }\n}"))
        assertNull(findUrl("module M {\n    f :: (float a) -> float { return a<caret>; }\n}"))
    }

}
