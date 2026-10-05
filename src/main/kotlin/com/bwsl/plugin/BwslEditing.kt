package com.bwsl.plugin

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Comment / uncomment with the comment shortcut: `//` per line, `/* ... */` around a selection. */
class BwslCommenter : Commenter {

    override fun getLineCommentPrefix(): String = "//"
    override fun getBlockCommentPrefix(): String = "/*"
    override fun getBlockCommentSuffix(): String = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
}

/**
 * Matches `{ }`, `( )` and `[ ]`: the matching bracket is highlighted next to the caret, jumped to
 * with **Move Caret to Matching Brace**, and the closing one is inserted when the opening one is typed.
 * `<` and `>` are not a pair: they are also comparison operators.
 */
class BwslBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE, true),
        BracePair(BwslTokenTypes.LPAREN, BwslTokenTypes.RPAREN, false),
        BracePair(BwslTokenTypes.LBRACKET, BwslTokenTypes.RBRACKET, false)
    )

    /** A closing bracket is only inserted when what follows would not end up inside it: whitespace, a comment, the end, or something that closes or ends. */
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean =
        contextType == null || contextType == TokenType.WHITE_SPACE || contextType in COMMENT_OR_CLOSING

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    private companion object {
        val COMMENT_OR_CLOSING = setOf(
            BwslTokenTypes.LINE_COMMENT, BwslTokenTypes.BLOCK_COMMENT, BwslTokenTypes.RBRACE,
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET, BwslTokenTypes.SEMI, BwslTokenTypes.COMMA
        )
    }
}

/** Typing `"` inserts the closing quote, and typing it again at the end of the string steps over it. */
class BwslQuoteHandler : SimpleTokenSetQuoteHandler(BwslTokenTypes.STRING_LIT)
