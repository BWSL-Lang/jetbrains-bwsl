package com.bwsl.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** `@bounds(...)`: the clause is written without spaces around its operators, and the decorator names complete. */
class BwslBoundsTest : BasePlatformTestCase() {

    private fun formatText(source: String): String {
        myFixture.configureByText("bounds.bwsl", source)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        return myFixture.file.text
    }

    private val written = """
        pipeline P {
            struct Params {
                @bounds(>0, <10) int count;
                @bounds(>=0, <=1) float4 tint;
                @bounds(x>0, y<=1) float4 weights;
                @bounds(>0, -10<y<0) float4 offsets;
            };

            resources {
                params: Params
                @bounds(>=0, <=1) fade: float
                scale: float @bounds(>0)
            }

            attributes {
                position: float3
                uv: float2 @bounds(>=0, <=1)
            }
        }
    """.trimIndent() + "\n"

    fun testAClauseWrittenWithoutSpacesIsKept() {
        assertEquals(written, formatText(written))
    }

    fun testSpacesInsideAClauseAreRemovedAndOneFollowsEachComma() {
        val spaced = written
            .replace("@bounds(>=0, <=1) float4", "@bounds( >= 0 ,<= 1 ) float4")
            .replace("@bounds(x>0, y<=1)", "@bounds (x > 0,  y <= 1)")
            .replace("-10<y<0", "-10 < y < 0")

        assertEquals(written, formatText(spaced))
    }

    fun testComparisonsOutsideAClauseKeepTheirSpaces() {
        val result = formatText("pipeline P {\n    f :: (float x) -> float {\n        @branch if (x>=0.0) { return x; }\n        return x<1.0 ? x : 1.0;\n    }\n}\n")

        assertTrue(result, result.contains("if (x >= 0.0)"))
    }

    private fun completeAfter(text: String): List<String> {
        myFixture.configureByText("complete.bwsl", text)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testDecoratorNamesCompleteAfterAnAt() {
        val names = completeAfter("pipeline P {\n    struct S {\n        @<caret> float x;\n    }\n}\n")

        assertEquals(setOf("bounds", "flat", "noperspective", "centroid", "branch", "flatten"), names.toSet())
    }

    fun testBoundsCompletesWithItsParenthesesAndTheCaretInsideThem() {
        myFixture.configureByText("complete.bwsl", "pipeline P {\n    struct S {\n        @bou<caret> float x;\n    }\n}\n")
        myFixture.completeBasic()

        assertTrue(myFixture.file.text, myFixture.file.text.contains("@bounds() float x;"))
        assertEquals(myFixture.file.text.indexOf("@bounds(") + "@bounds(".length, myFixture.caretOffset)
    }
}
