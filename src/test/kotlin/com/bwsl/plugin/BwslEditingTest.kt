package com.bwsl.plugin

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Commenting, bracket and quote matching, and what typing a bracket or quote inserts. */
class BwslEditingTest : BasePlatformTestCase() {

    fun testCommentLineAddsAndRemovesTheLineCommentPrefix() {
        val source = "module M {\n    f :: () -> float {\n        <caret>return 1.0;\n    }\n}"
        myFixture.configureByText("comment.bwsl", source)

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)

        val commented = myFixture.editor.document.text
        assertTrue("the line is commented out, got: $commented", commented.lines()[2].trimStart().startsWith("//"))
        assertEquals("the other lines are untouched", source.lines().filterIndexed { i, _ -> i != 2 }, commented.lines().filterIndexed { i, _ -> i != 2 })

        // Commenting moves the caret to the next line; go back to the commented one.
        myFixture.editor.caretModel.moveToLogicalPosition(LogicalPosition(2, 8))
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)

        assertEquals(source.replace("<caret>", ""), myFixture.editor.document.text)
    }

    fun testCommentLineCommentsEverySelectedLine() {
        myFixture.configureByText(
            "comment.bwsl",
            "module M {\n<selection>    a = 1;\n    b = 2;</selection>\n}"
        )

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)

        val lines = myFixture.editor.document.text.lines()
        assertTrue(lines[1].trimStart().startsWith("//") && lines[2].trimStart().startsWith("//"))
        assertEquals("module M {", lines[0])
        assertEquals("}", lines[3])
    }

    fun testCommentBlockWrapsTheSelectionInABlockComment() {
        myFixture.configureByText("comment.bwsl", "module M {\n    x = <selection>1.0 + 2.0</selection>;\n}")

        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)

        assertEquals("module M {\n    x = /*1.0 + 2.0*/;\n}", myFixture.editor.document.text)
    }

    fun testTypingAnOpeningBracketInsertsTheClosingOne() {
        for ((typed, expected) in listOf("{" to "{<caret>}", "(" to "(<caret>)", "[" to "[<caret>]")) {
            myFixture.configureByText("brackets.bwsl", "x = <caret>")

            myFixture.type(typed)

            myFixture.checkResult("x = $expected")
        }
    }

    fun testTypingTheClosingBracketStepsOverTheOneThatWasInserted() {
        myFixture.configureByText("brackets.bwsl", "f<caret>")

        myFixture.type("(a)")

        myFixture.checkResult("f(a)<caret>")
    }

    fun testNoClosingBracketIsInsertedBeforeAnIdentifier() {
        myFixture.configureByText("brackets.bwsl", "x = <caret>value;")

        myFixture.type("(")

        myFixture.checkResult("x = (<caret>value;")
    }

    fun testEnterBetweenBracesPutsTheCaretOnAnIndentedLineAndTheClosingBraceBelow() {
        myFixture.configureByText("brackets.bwsl", "module M {<caret>}")

        myFixture.type("\n")

        myFixture.checkResult("module M {\n    <caret>\n}")
    }

    fun testTypingAQuoteInsertsTheClosingQuoteAndTypingItAgainStepsOver() {
        myFixture.configureByText("quotes.bwsl", "pass <caret>")

        myFixture.type("\"Main\"")

        myFixture.checkResult("pass \"Main\"<caret>")
    }

    fun testTheMatchingBraceIsFoundForEachKindOfBracket() {
        val source = "module M { f(a[1]); }"
        for ((open, close) in listOf("{" to "}", "(" to ")", "[" to "]")) {
            val caret = source.indexOf(open)
            myFixture.configureByText("match.bwsl", source)
            myFixture.editor.caretModel.moveToOffset(caret)

            val matched = BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file)

            assertEquals("the match of '$open'", source.lastIndexOf(close), matched)
        }
    }

    fun testAngleBracketsAreNotAPairBecauseTheyAreAlsoComparisons() {
        val pairs = BwslBraceMatcher().pairs.map { it.leftBraceType to it.rightBraceType }

        assertEquals(
            listOf(
                BwslTokenTypes.LBRACE to BwslTokenTypes.RBRACE,
                BwslTokenTypes.LPAREN to BwslTokenTypes.RPAREN,
                BwslTokenTypes.LBRACKET to BwslTokenTypes.RBRACKET
            ),
            pairs
        )
    }
}
