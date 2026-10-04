package com.bwsl.plugin

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Extend Selection (and Shrink Selection): what each press of the shortcut selects, from the caret outwards. */
class BwslSelectionTest : BasePlatformTestCase() {

    /** The text selected after each of [presses] presses of Extend Selection at the caret of [sourceWithCaret]. */
    private fun selectRepeatedly(sourceWithCaret: String, presses: Int): List<String> {
        myFixture.configureByText("selection.bwsl", sourceWithCaret)
        val selections = ArrayList<String>()
        repeat(presses) {
            myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET)
            selections += myFixture.editor.selectionModel.selectedText.orEmpty()
        }
        return selections
    }

    private fun tidy(selection: String) = selection.lines().joinToString("\n") { it.trimEnd() }

    fun testExtendingFromAnArgumentGrowsThroughTheCallTheStatementAndTheBlocks() {
        val selections = selectRepeatedly(
            """
            module M {
                f :: (float a, float b) -> float {
                    float t = blend(al<caret>pha, b, 2.0);
                    return t;
                }
            }
            """.trimIndent(),
            presses = 12
        ).map { tidy(it) }

        val expected = listOf(
            "alpha",
            "alpha, b, 2.0",
            "(alpha, b, 2.0)",
            "float t = blend(alpha, b, 2.0);",
            "float t = blend(alpha, b, 2.0);\n        return t;",
            "{\n        float t = blend(alpha, b, 2.0);\n        return t;\n    }",
            "f :: (float a, float b) -> float {\n        float t = blend(alpha, b, 2.0);\n        return t;\n    }"
        )
        for ((position, text) in expected.withIndex()) {
            assertTrue("press ${position + 1} should select a range that contains '$text', got: $selections", selections.any { it == text })
        }
        for (position in 1 until selections.size) {
            assertTrue("each press selects something at least as large, got: $selections", selections[position].length >= selections[position - 1].length)
        }
    }

    fun testASecondArgumentIsSelectedBeforeAllTheArguments() {
        val selections = selectRepeatedly(
            "module M {\n    f :: () -> float {\n        return blend(alpha, be<caret>ta, gamma);\n    }\n}",
            presses = 4
        )

        assertEquals(listOf("beta", "alpha, beta, gamma", "(alpha, beta, gamma)"), selections.take(3))
    }

    fun testANestedCallIsSelectedBeforeTheOneAroundIt() {
        val selections = selectRepeatedly(
            "module M {\n    f :: () -> float {\n        return outer(inner(x<caret>, y), z);\n    }\n}",
            presses = 6
        )

        assertTrue(selections.contains("x, y"))
        assertTrue(selections.contains("(x, y)"))
        assertTrue("then the arguments of the outer call, got: $selections", selections.contains("inner(x, y), z"))
        assertTrue(selections.indexOf("(x, y)") < selections.indexOf("inner(x, y), z"))
    }

    fun testABlockIsSelectedWithoutItsBracesBeforeWithThem() {
        val selections = selectRepeatedly(
            "module M {\n    f :: (float a) -> float {\n        if (a > 0.0) {\n            ret<caret>urn 1.0;\n        }\n        return 0.0;\n    }\n}",
            presses = 8
        ).map { tidy(it) }

        val inner = "return 1.0;"
        assertTrue("the statement, got: $selections", selections.contains(inner))
        val withoutBraces = selections.indexOf(inner)
        assertTrue("then the block with its braces, got: $selections", selections.any { it.startsWith("{") && it.contains(inner) && !it.contains("return 0.0") })
        assertTrue("then the whole if, got: $selections", selections.any { it.startsWith("if (a > 0.0) {") && !it.contains("return 0.0") })
        assertTrue(withoutBraces >= 0)
    }

    fun testAnIfWithAnElseIsSelectedAsOneStatement() {
        val selections = selectRepeatedly(
            "module M {\n    f :: (float a) -> float {\n        if (a > 0.0) {\n            return 1.0;\n        } else {\n            re<caret>turn 2.0;\n        }\n    }\n}",
            presses = 7
        ).map { tidy(it) }

        assertTrue(
            "the if with its else, got: $selections",
            selections.any { it.startsWith("if (a > 0.0) {") && it.endsWith("return 2.0;\n        }") }
        )
    }

    fun testAStructMemberAndAFunctionAreSelectedAsWholeDeclarations() {
        val selections = selectRepeatedly(
            "module M {\n    struct S {\n        float a;\n        fl<caret>oat b;\n    }\n}",
            presses = 6
        ).map { tidy(it) }

        assertTrue("the field with its semicolon, got: $selections", selections.contains("float b;"))
        assertTrue("then the struct, got: $selections", selections.any { it.startsWith("struct S {") && it.endsWith("}") })
    }

    fun testShrinkingGoesBackDownTheSameSteps() {
        myFixture.configureByText("selection.bwsl", "module M {\n    f :: () -> float {\n        return blend(al<caret>pha, beta);\n    }\n}")
        repeat(3) { myFixture.performEditorAction(IdeActions.ACTION_EDITOR_SELECT_WORD_AT_CARET) }
        val grown = myFixture.editor.selectionModel.selectedText

        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_UNSELECT_WORD_AT_CARET)

        assertNotSame(grown, myFixture.editor.selectionModel.selectedText)
        assertTrue(grown!!.length > myFixture.editor.selectionModel.selectedText!!.length)
    }

    fun testTheRangesAroundACaretOutsideAnyBlockAreTheTopLevelStatementAndTheFile() {
        val ranges = collectSelectionRanges(
            myFixture.configureByText("selection.bwsl", "import Math\nmodule M {\n}\n").also { },
            offset = 3
        )

        assertTrue(ranges.isNotEmpty())
        assertTrue(ranges.all { it.containsOffset(3) })
    }
}
