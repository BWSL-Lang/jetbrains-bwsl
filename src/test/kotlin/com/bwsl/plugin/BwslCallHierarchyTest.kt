package com.bwsl.plugin

import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.LanguageCallHierarchy
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

/** Call Hierarchy: who calls a function (grouped by the function or stage they are written in) and what it calls. */
class BwslCallHierarchyTest : BwslAstFixtureTestCase() {

    private val lib = mapOf("Lib" to "module Lib {\n    scale :: (float x) -> float { return x * 2.0; }\n}")

    private val shader = """
        pipeline P {
            import Lib
            square :: (float x) -> float { return x * x; }
            twice :: (float x) -> float { return square(x) + square(x); }
            shade :: (float x) -> float { return cos(twice(x)) + Lib::scale(x); }
            countdown :: (int n) -> int {
                if (n <= 0) { return 0; }
                return countdown(n - 1);
            }
            attributes { position: float4 }
            pass "Main" {
                use attributes { position }
                outputs { color: float4 }
                vertex { output.pos = attributes.position; }
                fragment { output.color = float4(shade(1.0) + square(2.0) + float(countdown(3))); }
            }
        }
    """.trimIndent()

    private fun targetAt(text: String, marker: String): com.intellij.psi.PsiElement {
        val offset = text.indexOf(marker)
        assertTrue("no '$marker' in the source", offset >= 0)
        return findCallHierarchyTarget(myFixture.file, offset) ?: error("no target at '$marker'")
    }

    private fun describeChildren(structure: HierarchyTreeStructure, of: Any = structure.rootElement): List<String> =
        structure.getChildElements(of).map { child ->
            val descriptor = child as BwslCallHierarchyNodeDescriptor
            descriptor.update()
            descriptor.highlightedText.text
        }

    private fun findChild(structure: HierarchyTreeStructure, prefix: String, of: Any = structure.rootElement): Any =
        structure.getChildElements(of).first { child ->
            (child as BwslCallHierarchyNodeDescriptor).also { it.update() }.highlightedText.text.startsWith(prefix)
        }

    fun testTheTargetIsTheCalledFunctionTheNamedOneOrTheOneTheCaretIsIn() {
        val text = configureAndCache(shader, lib)
        val square = text.indexOf("square :: ")

        assertEquals("on a call", square, targetAt(text, "square(x) +").textOffset)
        assertEquals("on its own name", square, targetAt(text, "square :: ").textOffset)
        assertEquals("inside its body", square, targetAt(text, "x * x").textOffset)
        assertNull("in a stage, not in a function", findCallHierarchyTarget(myFixture.file, text.indexOf("attributes.position;")))
    }

    fun testTheCallersAreGroupedByTheFunctionOrStageEachCallIsIn() {
        val text = configureAndCache(shader, lib)
        val structure = BwslCallerTreeStructure(project, targetAt(text, "square :: "))

        assertEquals(
            listOf("twice(float x)  (2 usages)  test.bwsl", "fragment stage of pass \"Main\"  test.bwsl"),
            describeChildren(structure)
        )
    }

    fun testACallerExpandsToItsOwnCallersAndAStageDoesNot() {
        val text = configureAndCache(shader, lib)
        val structure = BwslCallerTreeStructure(project, targetAt(text, "square :: "))

        val twice = findChild(structure, "twice")
        assertEquals(listOf("shade(float x)  test.bwsl"), describeChildren(structure, twice))
        assertEquals(emptyList<String>(), describeChildren(structure, findChild(structure, "fragment")))
    }

    fun testTheCalleesAreTheFunctionsTheIntrinsicsAndTheModuleFunctionsCalled() {
        val text = configureAndCache(shader, lib)
        val structure = BwslCalleeTreeStructure(project, targetAt(text, "shade :: "))

        assertEquals(
            listOf("cos (intrinsic)  test.bwsl", "twice(float x)  test.bwsl", "scale(float x)  Lib.bwsl"),
            describeChildren(structure)
        )
        assertEquals(listOf("square(float x)  (2 usages)  test.bwsl"), describeChildren(structure, findChild(structure, "twice")))
        assertEquals(emptyList<String>(), describeChildren(structure, findChild(structure, "cos")))
    }

    fun testARecursiveFunctionIsItsOwnCallerOnceAndStopsThere() {
        val text = configureAndCache(shader, lib)
        val callers = BwslCallerTreeStructure(project, targetAt(text, "countdown :: "))

        val self = findChild(callers, "countdown")
        assertEquals(emptyList<String>(), describeChildren(callers, self))
        val callees = BwslCalleeTreeStructure(project, targetAt(text, "countdown :: "))
        assertEquals(emptyList<String>(), describeChildren(callees, findChild(callees, "countdown")))
    }

    fun testOpeningARowGoesToTheCall() {
        val text = configureAndCache(shader, lib)
        val structure = BwslCallerTreeStructure(project, targetAt(text, "square :: "))

        val twice = findChild(structure, "twice") as BwslCallHierarchyNodeDescriptor
        assertEquals(text.indexOf("square(x) +"), twice.callSites.first().textOffset)
    }

    fun testTheIdeAsksThisProviderForTheFunctionAtTheCaret() {
        val text = configureAndCache(shader, lib)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("square(x) +"))
        val provider = LanguageCallHierarchy.INSTANCE.forLanguage(BwslLanguage)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, myFixture.file)
            .build()

        assertTrue(provider is BwslCallHierarchyProvider)
        assertEquals(text.indexOf("square :: "), provider.getTarget(context)?.textOffset)
    }
}
