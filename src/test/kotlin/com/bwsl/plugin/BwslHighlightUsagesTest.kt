package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Highlight Usages (what the editor marks when the caret is on a name): every usage of the name in the file and
 * its declaration, for variables, parameters, fields (also inside struct methods), constants, types and functions.
 */
class BwslHighlightUsagesTest : BasePlatformTestCase() {

    private val source = """
        module M {
            const float K = 2.0;
            struct S {
                float intensity;
                float3 color;
                scaled :: (float k) -> float {
                    float a = intensity * k;
                    intensity = a + K;
                    return a + color.x;
                }
                twice :: () -> float { return intensity * 2.0 + color.z; }
            }
            helper :: (float x) -> float { return x; }
            consume :: (S l, float p) -> float {
                float t = l.intensity + l.color.x + helper(p);
                float u = helper(t) + p;
                return t + u + l.scaled(2.0) + l.twice() + K;
            }
        }
    """.trimIndent()

    /** The offsets that are highlighted when the caret is at the `|` in [marked], a piece of the source that is found in it. */
    private fun collectHighlightsAt(marked: String): List<Int> {
        val needle = marked.replace("|", "")
        val offset = source.indexOf(needle) + marked.indexOf('|')
        assertTrue("'$needle' is in the source", source.contains(needle))
        myFixture.configureByText("highlight_${marked.hashCode()}.bwsl", source.substring(0, offset) + "<caret>" + source.substring(offset))
        BwslcAstHelper.parseAndCache(source, myFixture.file.virtualFile.path)
        HighlightUsagesHandler.invoke(project, myFixture.editor, myFixture.file)
        return myFixture.editor.markupModel.allHighlighters.map { it.startOffset }.distinct().sorted()
    }

    private fun offsetsOf(word: String, filter: (Int) -> Boolean = { true }): List<Int> =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(word) + "(?![A-Za-z0-9_])").findAll(source).map { it.range.first }.filter(filter).toList()

    fun testAVariableIsHighlightedWithItsDeclarationAndAllItsUses() {
        // `t`: declared in consume, used twice.
        val declaration = source.indexOf("float t =") + "float ".length

        val highlighted = collectHighlightsAt("return |t + u")

        assertTrue("the declaration, got: $highlighted", declaration in highlighted)
        assertEquals(offsetsOf("t").filter { it >= declaration }, highlighted)
    }

    fun testAParameterIsHighlightedWithItsDeclaration() {
        val declaration = source.indexOf("float p)") + "float ".length

        val highlighted = collectHighlightsAt("helper(t) + |p")

        assertTrue("the declaration, got: $highlighted", declaration in highlighted)
        assertEquals(offsetsOf("p").filter { it >= declaration }, highlighted)
    }

    fun testAFieldUsedInStructMethodsIsHighlightedEverywhereWithItsDeclaration() {
        val declaration = source.indexOf("float intensity;") + "float ".length

        val highlighted = collectHighlightsAt("float a = |intensity * k")

        // The declaration, the three uses in the struct's methods, and `l.intensity` outside it.
        assertEquals(offsetsOf("intensity"), highlighted)
        assertTrue(declaration in highlighted)
        assertEquals(5, highlighted.size)
    }

    fun testAFieldIsHighlightedFromItsDeclarationToo() {
        val highlighted = collectHighlightsAt("float |intensity;")

        assertEquals(offsetsOf("intensity"), highlighted)
    }

    fun testAFieldThatIsAVectorIsHighlightedThroughMemberAccess() {
        val highlighted = collectHighlightsAt("return a + |color.x")

        assertEquals(offsetsOf("color"), highlighted)
    }

    fun testAConstantAndAFunctionAreHighlightedWithTheirDeclarations() {
        assertEquals(offsetsOf("K"), collectHighlightsAt("a + |K;"))
        assertEquals(offsetsOf("helper"), collectHighlightsAt("+ |helper(p)"))
    }

    fun testAStructTypeIsHighlightedWithItsDeclaration() {
        assertEquals(offsetsOf("S"), collectHighlightsAt("(|S l"))
    }

    fun testAMethodIsHighlightedWithItsDeclaration() {
        assertEquals(offsetsOf("scaled"), collectHighlightsAt("l.|scaled"))
    }
}
