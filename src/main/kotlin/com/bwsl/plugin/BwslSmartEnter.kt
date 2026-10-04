package com.bwsl.plugin

import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.tree.IElementType

/** What Complete Current Statement does to a line: [insertions] to make, and whether it opens a block to type in. */
data class StatementCompletion(val insertions: List<TextInsertion>, val opensBlock: Boolean)

/** The tokens that start a block header, which then needs a `{ }` of its own. */
private val BLOCK_HEADERS = setOf(
    BwslTokenTypes.KW_IF, BwslTokenTypes.KW_FOR, BwslTokenTypes.KW_FOREACH, BwslTokenTypes.KW_WHILE, BwslTokenTypes.KW_SWITCH,
    BwslTokenTypes.KW_LOOP, BwslTokenTypes.KW_ELSE, BwslTokenTypes.KW_STRUCT, BwslTokenTypes.KW_ENUM, BwslTokenTypes.KW_MODULE,
    BwslTokenTypes.KW_SUBMODULE, BwslTokenTypes.KW_PIPELINE, BwslTokenTypes.KW_PASS, BwslTokenTypes.KW_VERTEX,
    BwslTokenTypes.KW_FRAGMENT, BwslTokenTypes.KW_COMPUTE
)

/** Headers that hold a parenthesised condition: they are only complete once it is closed. */
private val CONDITION_HEADERS = setOf(
    BwslTokenTypes.KW_IF, BwslTokenTypes.KW_FOR, BwslTokenTypes.KW_FOREACH, BwslTokenTypes.KW_WHILE, BwslTokenTypes.KW_SWITCH
)

/** Blocks whose entries are separated by line, not ended by `;`. */
private val ENTRY_BLOCKS = setOf(
    BwslTokenTypes.KW_ATTRIBUTES, BwslTokenTypes.KW_RESOURCES, BwslTokenTypes.KW_VARIANTS, BwslTokenTypes.KW_OUTPUTS,
    BwslTokenTypes.KW_INPUTS
)

/** What ends a line of an expression that goes on, so it is not a statement that lacks its `;`. */
private val UNFINISHED_ENDS = setOf(
    BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE, BwslTokenTypes.COMMA, BwslTokenTypes.COLON,
    BwslTokenTypes.ARROW, BwslTokenTypes.COLONCOLON, BwslTokenTypes.DOT, BwslTokenTypes.QUESTION, BwslTokenTypes.LT,
    BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET,
    BwslTokenTypes.PLUS, BwslTokenTypes.MINUS, BwslTokenTypes.STAR, BwslTokenTypes.SLASH, BwslTokenTypes.PERCENT,
    BwslTokenTypes.EQ, BwslTokenTypes.AND, BwslTokenTypes.OR, BwslTokenTypes.AMP, BwslTokenTypes.PIPE
)

/**
 * Complete Current Statement (Ctrl+Shift+Enter) for the line at [caretOffset]: close the brackets that
 * were opened on it, then either add the `;` the statement lacks, or - for a block header such as
 * `if (x)` or a function declaration - add a `{ }` block. Null when the line needs nothing: it is
 * empty, a comment, or already complete, and the platform then just starts a new line.
 *
 * Works from the tokens of the line and the block around it, so it is right for text that has not been
 * compiled.
 */
fun planStatementCompletion(file: PsiFile, caretOffset: Int): StatementCompletion? {
    val text = file.text
    val lineStart = text.lastIndexOf('\n', (caretOffset - 1).coerceAtLeast(0)).let { if (caretOffset == 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', caretOffset).let { if (it < 0) text.length else it }
    val leaves = collectLeaves(file)
    val onLine = leaves.filter { it.range.startOffset >= lineStart && it.range.endOffset <= lineEnd && !it.isComment }
    val first = onLine.firstOrNull() ?: return null
    val last = onLine.last()
    val insertAt = last.range.endOffset

    // Brackets opened on this line and not closed.
    val closers = StringBuilder()
    val opened = ArrayList<IElementType>()
    for (leaf in onLine) {
        when (leaf.type) {
            BwslTokenTypes.LPAREN -> opened += BwslTokenTypes.RPAREN
            BwslTokenTypes.LBRACKET -> opened += BwslTokenTypes.RBRACKET
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> if (opened.lastOrNull() == leaf.type) opened.removeLast()
        }
    }
    for (closer in opened.asReversed()) closers.append(if (closer == BwslTokenTypes.RPAREN) ")" else "]")
    val closed = closers.isNotEmpty()
    val lastAfterClosing = if (closed) null else last.type

    // A block header without its block.
    val hasBrace = onLine.any { it.type == BwslTokenTypes.LBRACE } || last.nextSignificant?.type == BwslTokenTypes.LBRACE
    val isFunctionDeclaration = onLine.any { it.type == BwslTokenTypes.FUNCTION_DECLARATION } &&
        onLine.any { it.type == BwslTokenTypes.COLONCOLON }
    val isHeader = first.type in BLOCK_HEADERS || isFunctionDeclaration
    if (isHeader && !hasBrace) {
        val isUnfinished = lastAfterClosing in UNFINISHED_ENDS && lastAfterClosing != BwslTokenTypes.RBRACKET
        val needsCondition = first.type in CONDITION_HEADERS && onLine.none { it.type == BwslTokenTypes.LPAREN }
        if (!isUnfinished && !needsCondition) {
            return StatementCompletion(listOf(TextInsertion(insertAt, "$closers {\n\n}")), opensBlock = true)
        }
    }

    // A statement without its `;`.
    val endsStatement = lastAfterClosing == null || lastAfterClosing !in UNFINISHED_ENDS
    if (!isHeader && endsStatement && isSemicolonCompleted(first, leaves, onLine.first())) {
        return StatementCompletion(listOf(TextInsertion(insertAt, "$closers;")), opensBlock = false)
    }
    return if (closed) StatementCompletion(listOf(TextInsertion(insertAt, closers.toString())), opensBlock = false) else null
}

/** Whether a statement that starts with [first] is ended by `;` where it stands (inside a function body or struct, or a constant). */
private fun isSemicolonCompleted(first: Leaf, leaves: List<Leaf>, lineStartLeaf: Leaf): Boolean {
    if (first.type == BwslTokenTypes.KW_IMPORT || first.type == BwslTokenTypes.KW_USING) return false
    if (first.type == BwslTokenTypes.KW_CASE || first.type == BwslTokenTypes.KW_DEFAULT) return false
    if (first.type == BwslTokenTypes.KW_CONST) return true
    // The `{` of the block the line is in, and what owns it.
    val open = ArrayList<Leaf>()
    for (leaf in leaves) {
        if (leaf === lineStartLeaf) break
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> open += leaf
            BwslTokenTypes.RBRACE -> open.removeLastOrNull()
        }
    }
    val owner = open.lastOrNull()?.previousSignificant ?: return false
    if (owner.type in ENTRY_BLOCKS) return false
    // `module M {`, `pipeline P {`, `enum E {`: what is inside is declarations and entries, not statements.
    val ownerKeyword = owner.previousSignificant?.type
    if (ownerKeyword == BwslTokenTypes.KW_MODULE || ownerKeyword == BwslTokenTypes.KW_PIPELINE ||
        ownerKeyword == BwslTokenTypes.KW_SUBMODULE || ownerKeyword == BwslTokenTypes.KW_ENUM
    ) return false
    return true
}

/** Complete Current Statement for BWSL: see [planStatementCompletion]. */
class BwslSmartEnterProcessor : SmartEnterProcessor() {

    override fun process(project: Project, editor: Editor, psiFile: PsiFile): Boolean {
        if (psiFile.language != BwslLanguage) return false
        val documents = PsiDocumentManager.getInstance(project)
        val document = editor.document
        documents.commitDocument(document)
        val plan = planStatementCompletion(psiFile, editor.caretModel.offset) ?: return false

        for (insertion in plan.insertions.sortedByDescending { it.offset }) document.insertString(insertion.offset, insertion.text)
        documents.commitDocument(document)

        val line = document.getLineNumber(plan.insertions.maxOf { it.offset })
        if (plan.opensBlock) {
            // `{`, an empty line to type in, `}`: indent the last two as the formatter would and stop on the empty one.
            val styles = CodeStyleManager.getInstance(project)
            styles.adjustLineIndent(document, document.getLineStartOffset(line + 2))
            styles.adjustLineIndent(document, document.getLineStartOffset(line + 1))
            editor.caretModel.moveToOffset(document.getLineEndOffset(line + 1))
        } else {
            editor.caretModel.moveToOffset(document.getLineEndOffset(line))
        }
        return true
    }
}
