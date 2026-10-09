package com.bwsl.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The editor's highlighter re-lexes only what an edit touches: a block comment must come out right after each edit. */
class BwslBlockCommentHighlightingTest : BasePlatformTestCase() {

    private val source = "module M {\n    f :: (float a) -> float {\n        float b = a * 2.0;\n        return b;\n    }\n}\n"

    private fun typeAt(offset: Int, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(offset, text) }
    }

    /** The token type the editor's highlighter has at [offset] of the current text. */
    private fun findHighlightedTypeAt(offset: Int): IElementType? {
        val iterator = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        return iterator.tokenType
    }

    fun testAnUnclosedCommentColoursTheRestOfTheFileAtOnce() {
        myFixture.configureByText("c.bwsl", source)
        val line = source.indexOf("float b")

        typeAt(line, "/* ")

        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(line + 5))
        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(source.indexOf("return b") + 3))
    }

    fun testClosingTheCommentColoursOnlyWhatItEnclosesAtOnce() {
        myFixture.configureByText("c.bwsl", source)
        val start = source.indexOf("float b")
        val end = source.indexOf("\n", start)

        typeAt(start, "/* ")
        typeAt(end + 3, " */")

        val text = myFixture.editor.document.text
        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(text.indexOf("float b")))
        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(text.indexOf("2.0")))
        assertEquals("what follows the comment is code again", BwslTokenTypes.KW_RETURN, findHighlightedTypeAt(text.indexOf("return b")))
    }

    fun testCommentingOutALineWithABlockCommentInOneActionColoursItAtOnce() {
        myFixture.configureByText("c.bwsl", source)
        val start = source.indexOf("float b")
        val end = source.indexOf("\n", start)

        // What the Comment with Block Comment action does: the closing mark first, then the opening one.
        typeAt(end, " */")
        typeAt(start, "/* ")

        val text = myFixture.editor.document.text
        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(text.indexOf("float b")))
        assertEquals(BwslTokenTypes.KW_RETURN, findHighlightedTypeAt(text.indexOf("return b")))
    }

    fun testRemovingTheClosingMarkExtendsTheCommentToTheEnd() {
        val commented = "module M {\n    /* a\n       b */\n    f :: () -> float { return 1.0; }\n}\n"
        myFixture.configureByText("c.bwsl", commented)
        val close = commented.indexOf("*/")

        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.deleteString(close, close + 2) }

        val text = myFixture.editor.document.text
        assertEquals(BwslTokenTypes.BLOCK_COMMENT, findHighlightedTypeAt(text.indexOf("return")))
    }
}
