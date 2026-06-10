package com.bwsl.plugin

import com.bwsl.plugin.psi.BwslFile
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

private val FILE        = IFileElementType(BwslLanguage)
private val COMMENTS    = TokenSet.create(BwslTokenTypes.LINE_COMMENT, BwslTokenTypes.BLOCK_COMMENT)
private val STRING_LITS = TokenSet.create(BwslTokenTypes.STRING_LIT)

class BwslParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = BwslLexerAdapter()

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        fun parseElement() {
            val type = builder.tokenType ?: return
            if (type == BwslTokenTypes.FUNCTION_CALL || type == BwslTokenTypes.INTRINSIC_CALL) {
                val marker = builder.mark()
                builder.advanceLexer() // consume function name
                while (builder.tokenType == com.intellij.psi.TokenType.WHITE_SPACE) builder.advanceLexer()
                if (builder.tokenType == BwslTokenTypes.LPAREN) {
                    builder.advanceLexer() // consume (
                    while (!builder.eof() && builder.tokenType != BwslTokenTypes.RPAREN) parseElement()
                    if (!builder.eof()) builder.advanceLexer() // consume )
                }
                marker.done(BwslTokenTypes.CALL_EXPRESSION)
            } else {
                builder.advanceLexer()
            }
        }

        val rootMarker = builder.mark()
        while (!builder.eof()) parseElement()
        rootMarker.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType                 = FILE
    override fun getWhitespaceTokens(): TokenSet                     = TokenSet.WHITE_SPACE
    override fun getCommentTokens(): TokenSet                        = COMMENTS
    override fun getStringLiteralElements(): TokenSet                = STRING_LITS
    override fun createElement(node: ASTNode): PsiElement           = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider): PsiFile = BwslFile(viewProvider)
}
