package com.bwsl.plugin

import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.tree.IElementType

/**
 * Formats BWSL: re-indents every line by how deeply it is nested and normalises the spaces between
 * tokens in the places where that is unambiguous. It never moves a token to another line, and never
 * changes the order of tokens, so the program means what it meant.
 *
 * BWSL's PSI is flat (a file of tokens, a few wrapped in a REFERENCE or CALL_EXPRESSION), so the
 * formatting model is flat too: one block per token under the file, each with the indent its line
 * should have, computed by [IndentTracker] from the braces, parentheses and braceless statement
 * bodies before it.
 */
class BwslFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val file = formattingContext.containingFile
        val settings = formattingContext.codeStyleSettings
        val root = BwslRootBlock(file, collectLeaves(file), settings)
        return FormattingModelProvider.createFormattingModelForPsiFile(file, root, settings)
    }
}

/** A token of the file, with what the indent and spacing rules need to know about its surroundings. */
internal class Leaf(
    node: ASTNode,
    val previous: Leaf?,
    /** Whether a line break lies between the previous token and this one (or this is the first token). */
    val isFirstOnLine: Boolean
) {
    // Both are read once, now: the formatter edits the tree as it applies whitespace, after which
    // the node's own range no longer says where the token was in the text being formatted.
    val type: IElementType = node.elementType
    val range: TextRange = node.textRange
    var next: Leaf? = null

    /** A comment is skipped when the rules look for what came before or after a token. */
    val isComment: Boolean get() = type == BwslTokenTypes.LINE_COMMENT || type == BwslTokenTypes.BLOCK_COMMENT

    /** The nearest token before this one that is not a comment. */
    val previousSignificant: Leaf? get() = generateSequence(previous) { it.previous }.firstOrNull { !it.isComment }

    /** The nearest token after this one that is not a comment. */
    val nextSignificant: Leaf? get() = generateSequence(next) { it.next }.firstOrNull { !it.isComment }

    /**
     * Whether this `+ - * / %` is a binary operator: it follows something that ends an operand. After
     * anything else it is a sign, which is left as the author wrote it.
     */
    val isBinaryArithmetic: Boolean
        get() = type in ARITHMETIC && previousSignificant?.type in OPERAND_ENDS
}

/** Every token of [file] in order, whitespace left out, and the composites around some of them looked through. */
internal fun collectLeaves(file: PsiFile): List<Leaf> {
    val text = file.text
    val leaves = ArrayList<Leaf>()
    fun visit(node: ASTNode) {
        var child: ASTNode? = node.firstChildNode
        while (child != null) {
            if (child.firstChildNode != null) {
                visit(child)
            } else if (child.elementType != TokenType.WHITE_SPACE && child.textLength > 0) {
                val previous = leaves.lastOrNull()
                val gapStart = previous?.range?.endOffset ?: 0
                val isFirstOnLine = previous == null || text.substring(gapStart, child.startOffset).contains('\n')
                leaves += Leaf(child, previous, isFirstOnLine).also { previous?.next = it }
            }
            child = child.treeNext
        }
    }
    visit(file.node)
    return leaves
}

private class BwslRootBlock(
    file: PsiFile,
    leaves: List<Leaf>,
    settings: CodeStyleSettings
) : Block {

    private val textRange: TextRange = file.textRange
    private val indentOptions = settings.getIndentOptionsByFile(file)
    private val keepBlankLines = settings.getCommonSettings(BwslLanguage).KEEP_BLANK_LINES_IN_CODE
    private val indentSize = indentOptions.INDENT_SIZE.coerceAtLeast(0)
    private val continuationSize = indentOptions.CONTINUATION_INDENT_SIZE.coerceAtLeast(0)

    private val leafBlocks: List<Block>

    /** The indent a new line would get after token `i`, which is what Enter after it should produce. */
    private val indentAfter: List<Int>

    init {
        val tracker = IndentTracker(indentSize, continuationSize)
        val blocks = ArrayList<Block>()
        val after = ArrayList<Int>()
        for (leaf in leaves) {
            val spaces = tracker.indentFor(leaf)
            blocks += BwslLeafBlock(leaf, spaces)
            tracker.consume(leaf)
            after += tracker.indentForNewLine()
        }
        leafBlocks = blocks
        indentAfter = after
    }

    override fun getTextRange(): TextRange = textRange
    override fun getSubBlocks(): List<Block> = leafBlocks
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent? = null
    override fun getAlignment(): Alignment? = null
    override fun isLeaf(): Boolean = leafBlocks.isEmpty()
    override fun isIncomplete(): Boolean = false

    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val second = (child2 as? BwslLeafBlock)?.leaf ?: return null
        val first = (child1 as? BwslLeafBlock)?.leaf ?: return null
        return spacingBetween(first, second)?.let { (min, max) -> Spacing.createSpacing(min, max, 0, true, keepBlankLines) }
            ?: Spacing.createSpacing(0, Int.MAX_VALUE, 0, true, keepBlankLines)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val spaces = if (newChildIndex > 0) indentAfter.getOrNull(newChildIndex - 1) ?: 0 else 0
        return ChildAttributes(toIndent(spaces), null)
    }
}

private class BwslLeafBlock(val leaf: Leaf, private val spaces: Int) : Block {
    override fun getTextRange(): TextRange = leaf.range
    override fun getSubBlocks(): List<Block> = emptyList()
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = toIndent(spaces)
    override fun getAlignment(): Alignment? = null
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(null, null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = true
}

private fun toIndent(spaces: Int): Indent = if (spaces <= 0) Indent.getNoneIndent() else Indent.getSpaceIndent(spaces, false)

private val ARITHMETIC = setOf(
    BwslTokenTypes.PLUS, BwslTokenTypes.MINUS, BwslTokenTypes.STAR, BwslTokenTypes.SLASH, BwslTokenTypes.PERCENT
)

/** Tokens an operand can end with; a `-` after one of these is a subtraction, after anything else a sign. */
private val OPERAND_ENDS = setOf(
    BwslTokenTypes.IDENTIFIER, BwslTokenTypes.NUMBER_LIT, BwslTokenTypes.STRING_LIT, BwslTokenTypes.RPAREN,
    BwslTokenTypes.RBRACKET, BwslTokenTypes.KW_TRUE, BwslTokenTypes.KW_FALSE, BwslTokenTypes.KW_SELF,
    BwslTokenTypes.PLUSPLUS, BwslTokenTypes.MINUSMINUS
)

/** Operators that always have a space on both sides: assignments, comparisons and the logical ones. */
private val SPACED_OPERATORS = setOf(
    BwslTokenTypes.EQ, BwslTokenTypes.PLUSEQ, BwslTokenTypes.MINUSEQ, BwslTokenTypes.STAREQ,
    BwslTokenTypes.SLASHEQ, BwslTokenTypes.PERCENTEQ, BwslTokenTypes.AMPEQ, BwslTokenTypes.PIPEEQ,
    BwslTokenTypes.CARETEQ, BwslTokenTypes.LSHIFTEQ, BwslTokenTypes.RSHIFTEQ, BwslTokenTypes.EQEQ,
    BwslTokenTypes.NEQ, BwslTokenTypes.LE, BwslTokenTypes.GE, BwslTokenTypes.AND, BwslTokenTypes.OR,
    BwslTokenTypes.ARROW
)

private val CONTROL_KEYWORDS = setOf(
    BwslTokenTypes.KW_IF, BwslTokenTypes.KW_FOR, BwslTokenTypes.KW_FOREACH, BwslTokenTypes.KW_WHILE,
    BwslTokenTypes.KW_SWITCH, BwslTokenTypes.KW_LOOP
)

/** Tokens a line can end with, or start with, that mean the expression goes on: the line is a continuation. */
private val CONTINUING_OPERATORS = SPACED_OPERATORS - BwslTokenTypes.ARROW + ARITHMETIC +
    setOf(BwslTokenTypes.DOT, BwslTokenTypes.AMP, BwslTokenTypes.PIPE)

/** What ends a statement or opens a block: the line after one of these starts something new. */
private val STATEMENT_BOUNDARIES = setOf(
    BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE, BwslTokenTypes.COMMA
)

/**
 * The number of spaces to put between [first] and [second] when they are on one line, as a
 * (minimum, maximum) pair, or null to leave what is there. Only unambiguous places have a rule: `<`
 * and `>` (a comparison or a generic's brackets), `:` and `?` (a label, an entry, a ternary), `^` (an
 * operator or a pointer), `..` and a sign are left alone.
 */
internal fun spacingBetween(first: Leaf, second: Leaf): Pair<Int, Int>? {
    val one = 1 to 1
    val none = 0 to 0
    val a = first.type
    val b = second.type

    if (first.isComment || second.isComment) return null
    if (b == BwslTokenTypes.COMMA || b == BwslTokenTypes.SEMI) return none
    if (a == BwslTokenTypes.COMMA) return one
    if (a == BwslTokenTypes.LPAREN || a == BwslTokenTypes.LBRACKET) return none
    if (b == BwslTokenTypes.RPAREN || b == BwslTokenTypes.RBRACKET) return none
    if (b == BwslTokenTypes.LPAREN && a in CONTROL_KEYWORDS) return one

    if (b == BwslTokenTypes.LBRACE) return if (a == BwslTokenTypes.LBRACE) null else one
    if (a == BwslTokenTypes.LBRACE) return if (b == BwslTokenTypes.RBRACE) null else one
    if (b == BwslTokenTypes.RBRACE) return one
    if (a == BwslTokenTypes.RBRACE) return one

    if (a in SPACED_OPERATORS || b in SPACED_OPERATORS) return one
    if (first.isBinaryArithmetic || second.isBinaryArithmetic) return one

    // `name :: (...)` declares a function; `Module::name` qualifies one and has no spaces.
    if (b == BwslTokenTypes.COLONCOLON && a == BwslTokenTypes.FUNCTION_DECLARATION) return one
    if (a == BwslTokenTypes.COLONCOLON && first.previous?.type == BwslTokenTypes.FUNCTION_DECLARATION) return one
    return null
}

/**
 * Follows the tokens of a file from the top and says how far the line of each is indented.
 *
 * - Every open `{` indents what is inside it by one level, and every open `(` or `[` by a
 *   continuation indent.
 * - A line that carries on an expression (it follows, or starts with, an operator) gets a
 *   continuation indent too.
 * - A braceless statement body (`if (c)` then the statement on the next line, or after `else`) is
 *   indented one level. Whether it is, is decided by whether its first token starts a line; a body on
 *   the header's own line stays there. A body ends at its `;` or its closing `}`, and an `else` after
 *   it sits at the level of the `if` it belongs to.
 *
 * An unbalanced file never indents below zero.
 */
internal class IndentTracker(private val indentSize: Int, private val continuationSize: Int) {

    /** A control construct (`if (...)`, `else`, a loop) whose body has not ended. */
    private class Construct(val keyword: IElementType, var isBodyOnItsOwnLine: Boolean = false)

    /** An open `{`: the body depth and number of open constructs to go back to at its `}`, and whether it is a construct's body. */
    private class Frame(val bodyDepthAtOpen: Int, val constructsAtOpen: Int, val isConstructBody: Boolean)

    private var braceDepth = 0
    private var parenDepth = 0
    private var bodyDepth = 0

    /** The construct waiting for the first token of its body. */
    private var pending: Construct? = null

    private val constructs = ArrayList<Construct>()
    private val frames = ArrayList<Frame>()
    private val parenOpeners = ArrayList<IElementType?>()

    /** The indent of the line [leaf] starts, in spaces; only used when it is the first token of its line. */
    fun indentFor(leaf: Leaf): Int {
        var braces = braceDepth
        var bodies = bodyDepth
        var parens = parenDepth
        when (leaf.type) {
            BwslTokenTypes.RBRACE -> {
                braces = (braceDepth - 1).coerceAtLeast(0)
                frames.lastOrNull()?.let { bodies = it.bodyDepthAtOpen }
                parens = 0
            }
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> parens = (parenDepth - 1).coerceAtLeast(0)
            else -> if (pending != null && leaf.type != BwslTokenTypes.LBRACE) bodies++
        }
        val continuation = when {
            parens > 0 -> parens * continuationSize
            isContinuationLine(leaf) -> continuationSize
            else -> 0
        }
        return (braces + bodies) * indentSize + continuation
    }

    /** The indent of a new line that starts right after the tokens consumed so far. */
    fun indentForNewLine(): Int {
        val bodies = if (pending != null) bodyDepth + 1 else bodyDepth
        return (braceDepth + bodies) * indentSize + parenDepth * continuationSize
    }

    /** Takes [leaf] into account for the lines that follow it. */
    fun consume(leaf: Leaf) {
        val waiting = pending
        pending = null
        if (waiting != null && leaf.type != BwslTokenTypes.LBRACE && leaf.isFirstOnLine) {
            waiting.isBodyOnItsOwnLine = true
            bodyDepth++
        }
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> {
                frames += Frame(bodyDepth, constructs.size, isConstructBody = waiting != null)
                braceDepth++
            }
            BwslTokenTypes.RBRACE -> {
                val frame = frames.removeLastOrNull()
                braceDepth = (braceDepth - 1).coerceAtLeast(0)
                if (frame != null) {
                    bodyDepth = frame.bodyDepthAtOpen
                    // Whatever was still open inside the block ended with it.
                    while (constructs.size > frame.constructsAtOpen) constructs.removeLast()
                    if (frame.isConstructBody) endStatement(leaf)
                }
            }
            BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> {
                parenOpeners += leaf.previousSignificant?.type?.takeIf { it in CONTROL_KEYWORDS }
                parenDepth++
            }
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> {
                parenDepth = (parenDepth - 1).coerceAtLeast(0)
                val keyword = parenOpeners.removeLastOrNull()
                if (keyword != null && leaf.type == BwslTokenTypes.RPAREN) openConstruct(keyword)
            }
            BwslTokenTypes.SEMI -> if (parenDepth == 0) endStatement(leaf)
            BwslTokenTypes.KW_ELSE -> openConstruct(leaf.type)
            // `loop (n) {` has a header like the others; a bare `loop {` has its body right away.
            BwslTokenTypes.KW_LOOP -> if (leaf.nextSignificant?.type != BwslTokenTypes.LPAREN) openConstruct(leaf.type)
        }
    }

    private fun openConstruct(keyword: IElementType) {
        val construct = Construct(keyword)
        constructs += construct
        pending = construct
    }

    /**
     * A statement ended at [last]: it was the body of the innermost open construct, which ends with it,
     * and so on outwards. An `else` that follows belongs to the `if` that just ended, so the outward walk
     * stops there.
     */
    private fun endStatement(last: Leaf) {
        val floor = frames.lastOrNull()?.constructsAtOpen ?: 0
        val nextIsElse = last.nextSignificant?.type == BwslTokenTypes.KW_ELSE
        while (constructs.size > floor) {
            val ended = constructs.removeLast()
            if (ended.isBodyOnItsOwnLine) bodyDepth--
            if (nextIsElse && ended.keyword == BwslTokenTypes.KW_IF) break
        }
    }

    private fun isContinuationLine(leaf: Leaf): Boolean {
        if (!leaf.isFirstOnLine || leaf.isComment) return false
        val previous = leaf.previousSignificant ?: return false
        if (previous.type in CONTINUING_OPERATORS) return true
        return leaf.type in CONTINUING_OPERATORS && previous.type !in STATEMENT_BOUNDARIES
    }
}
