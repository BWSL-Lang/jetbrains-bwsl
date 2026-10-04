package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The File Structure view and breadcrumbs, from the tokens of the file. */
class BwslStructureTest : BasePlatformTestCase() {

    private val module = """
        module Shapes {
            import Lib
            const float K = 2.0;
            struct Circle {
                float radius;
                area :: () -> float { return radius; }
            }
            enum Mode { A, B }
            scale :: (float x) -> float {
                if (x > 0.0) { return x; }
                return K;
            }
        }
    """.trimIndent()

    private val pipeline = """
        pipeline P {
            attributes { position: float4 }
            pass "Main" {
                vertex { output.pos = attributes.position; }
                fragment { output.color = float4(1.0); }
            }
        }
    """.trimIndent()

    private fun describe(nodes: List<OutlineNode>, indent: String = ""): List<String> =
        nodes.flatMap { listOf("$indent${it.kind} ${it.name}") + describe(it.children, "$indent  ") }

    fun testAModuleListsItsDeclarationsAndTheirMembers() {
        val outline = collectOutline(myFixture.configureByText("shapes.bwsl", module))

        assertEquals(
            listOf(
                "MODULE Shapes",
                "  CONSTANT K",
                "  STRUCT Circle",
                "    FIELD radius",
                "    METHOD area",
                "  ENUM Mode",
                "  FUNCTION scale"
            ),
            describe(outline)
        )
    }

    fun testAPipelineListsItsPassesAndStages() {
        val outline = collectOutline(myFixture.configureByText("p.bwsl", pipeline))

        assertEquals(listOf("PIPELINE P", "  PASS Main", "    STAGE vertex", "    STAGE fragment"), describe(outline))
    }

    fun testANodeKnowsWhereItsNameAndItsWholeDeclarationAre() {
        val file = myFixture.configureByText("shapes.bwsl", module)
        val scale = collectOutline(file).single().children.single { it.name == "scale" }

        assertEquals("scale", file.text.substring(scale.nameOffset, scale.nameOffset + 5))
        assertTrue(file.text.substring(scale.range.startOffset, scale.range.endOffset).endsWith("return K;\n    }"))
    }

    fun testHalfTypedCodeStillHasAnOutline() {
        val outline = collectOutline(myFixture.configureByText("typing.bwsl", "module M {\n    f :: (float x) -> float {\n        return x +\n"))

        assertEquals(listOf("MODULE M", "  FUNCTION f"), describe(outline))
    }

    fun testTheStructureViewShowsTheOutlineInSourceOrder() {
        val file = myFixture.configureByText("shapes.bwsl", module)
        val builder = BwslStructureViewFactory().getStructureViewBuilder(file)!!
        val model = (builder as com.intellij.ide.structureView.TreeBasedStructureViewBuilder).createStructureViewModel(myFixture.editor)
        try {
            val top = model.root.children.map { it.presentation.presentableText }
            val inner = model.root.children.single().children.map { it.presentation.presentableText }

            assertEquals(listOf("Shapes"), top)
            assertEquals(listOf("K", "Circle", "Mode", "scale()"), inner)
        } finally {
            model.dispose()
        }
    }

    fun testBreadcrumbsNameTheDeclarationsAroundTheCaret() {
        myFixture.configureByText("shapes.bwsl", module.replace("return radius;", "return rad<caret>ius;"))

        assertEquals(listOf("Shapes", "Circle", "area()"), myFixture.breadcrumbsAtCaret.map { it.text })
    }

    fun testBreadcrumbsOutsideAnyDeclarationAreEmpty() {
        myFixture.configureByText("shapes.bwsl", "import <caret>Lib\nmodule M {\n}")

        assertTrue(myFixture.breadcrumbsAtCaret.isEmpty())
    }
}
