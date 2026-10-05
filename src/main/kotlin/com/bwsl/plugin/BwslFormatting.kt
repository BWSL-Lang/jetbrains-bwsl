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
    val node: ASTNode,
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
    private val commonSettings = settings.getCommonSettings(BwslLanguage)
    private val bwslSettings = settings.getCustomSettings(BwslCodeStyleSettings::class.java)
    private val keepBlankLines = commonSettings.KEEP_BLANK_LINES_IN_CODE
    private val indentSize = indentOptions.INDENT_SIZE.coerceAtLeast(0)
    private val continuationSize = indentOptions.CONTINUATION_INDENT_SIZE.coerceAtLeast(0)

    /**
     * The `{` of every block that spans lines, after formatting: only those are moved by the brace style.
     * Set in `init`, once it is known which breaks the formatter adds itself.
     */
    private val multiLineBraces: Set<Leaf>

    private val leafBlocks: List<Block>

    /** The indent a new line would get after token `i`, which is what Enter after it should produce. */
    private val indentAfter: List<Int>

    /** The tokens that start a line only because a call's arguments were too long for one. */
    private val wrappedLeaves: Set<Leaf>

    init {
        val tracker = IndentTracker(indentSize, continuationSize)
        val indents = ArrayList<Int>()
        val continuations = ArrayList<Boolean>()
        val after = ArrayList<Int>()
        for (leaf in leaves) {
            indents += tracker.indentFor(leaf)
            continuations += tracker.isContinuationLine(leaf)
            tracker.consume(leaf)
            after += tracker.indentForNewLine()
        }
        val text = file.text
        val bracesAsWritten = collectMultiLineBraces(leaves, text, emptySet(), movesElse = false)
        wrappedLeaves = if (bwslSettings.WRAP_CALL_ARGUMENTS) {
            collectWrappedCallArguments(leaves, indents, settings.getRightMargin(BwslLanguage)) { index ->
                val rule = spacingBetween(leaves[index - 1], leaves[index], bwslSettings.BRACE_STYLE, leaves[index] in bracesAsWritten)
                rule?.min ?: (leaves[index].range.startOffset - leaves[index - 1].range.endOffset).coerceAtLeast(1)
            }
        } else {
            emptySet()
        }
        // A block that holds a break the formatter adds (a wrapped argument, an `else` on its own line) spans
        // lines too, or formatting would move its brace only the second time.
        multiLineBraces = collectMultiLineBraces(leaves, text, wrappedLeaves, movesElse = bwslSettings.BRACE_STYLE == BraceStyle.NEXT_LINE)

        val alignments = if (bwslSettings.ALIGN_CONTINUED_EXPRESSIONS) ContinuationAlignments() else null
        leafBlocks = leaves.mapIndexed { index, leaf ->
            val startsLine = leaf.isFirstOnLine || leaf in wrappedLeaves
            BwslLeafBlock(leaf, indents[index], alignments?.alignmentFor(leaf, startsLine, continuations[index]))
        }
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
        val rule = if (second in wrappedLeaves) {
            SpacingRule(0, 0, minLineFeeds = 1)
        } else {
            spacingBetween(first, second, bwslSettings.BRACE_STYLE, second in multiLineBraces) ?: SpacingRule(0, Int.MAX_VALUE)
        }
        return Spacing.createSpacing(rule.min, rule.max, rule.minLineFeeds, rule.keepLineBreaks, keepBlankLines)
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes {
        val spaces = if (newChildIndex > 0) indentAfter.getOrNull(newChildIndex - 1) ?: 0 else 0
        return ChildAttributes(toIndent(spaces), null)
    }
}

private class BwslLeafBlock(val leaf: Leaf, private val spaces: Int, private val alignment: Alignment?) : Block {
    override fun getTextRange(): TextRange = leaf.range
    override fun getSubBlocks(): List<Block> = emptyList()
    override fun getWrap(): Wrap? = null
    override fun getIndent(): Indent = toIndent(spaces)
    override fun getAlignment(): Alignment? = alignment
    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null
    override fun getChildAttributes(newChildIndex: Int): ChildAttributes = ChildAttributes(null, null)
    override fun isIncomplete(): Boolean = false
    override fun isLeaf(): Boolean = true
}

/**
 * The tokens that must start a line so that no line passes [rightMargin]: the arguments (or
 * parameters) of a call, one per line, chosen by simulating the layout. A line that is too long has the
 * arguments of the shallowest call on it chopped first, then the next call in, and so on until it fits or
 * has no comma left to break at. The result depends only on the tokens, never on where lines were
 * broken by an earlier wrap, so formatting the result again changes nothing.
 *
 * The platform's own wrapping is not used: it only runs together with a hard wrap that cuts a line
 * wherever it falls, through a number if need be.
 *
 * [indents] is the indent of the line each token would start, and [gapBefore] the spaces between a
 * token (by index) and the one before it.
 */
private fun collectWrappedCallArguments(leaves: List<Leaf>, indents: List<Int>, rightMargin: Int, gapBefore: (Int) -> Int): Set<Leaf> {
    if (rightMargin <= 0 || leaves.isEmpty()) return emptySet()

    // How many parentheses (not brackets) a token is directly inside, or -1 if the innermost opener is a bracket.
    val callDepth = IntArray(leaves.size)
    val openers = ArrayList<IElementType>()
    var parentheses = 0
    for ((index, leaf) in leaves.withIndex()) {
        when (leaf.type) {
            BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> {
                openers += leaf.type
                if (leaf.type == BwslTokenTypes.LPAREN) parentheses++
            }
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> {
                if (openers.removeLastOrNull() == BwslTokenTypes.LPAREN) parentheses--
            }
        }
        callDepth[index] = if (openers.lastOrNull() == BwslTokenTypes.LPAREN) parentheses else -1
    }

    val widths = IntArray(leaves.size) { leaves[it].range.length }
    val startsLine = BooleanArray(leaves.size) { leaves[it].isFirstOnLine }
    val wrapped = HashSet<Leaf>()

    repeat(MAX_WRAP_PASSES) {
        var changed = false
        var lineStart = 0
        var column = 0
        for (index in leaves.indices) {
            if (startsLine[index]) {
                lineStart = index
                column = indents[index] + widths[index]
            } else {
                column += gapBefore(index) + widths[index]
            }
            if (column <= rightMargin) continue

            // Too long here: break the commas of the shallowest call on this line that is not broken yet.
            var lineEnd = index
            while (lineEnd + 1 < leaves.size && !startsLine[lineEnd + 1]) lineEnd++
            val breakable = (lineStart..lineEnd).filter { it > 0 && leaves[it - 1].type == BwslTokenTypes.COMMA && !startsLine[it] && callDepth[it] > 0 }
            val depth = breakable.minOfOrNull { callDepth[it] } ?: continue
            for (candidate in breakable.filter { callDepth[it] == depth }) {
                startsLine[candidate] = true
                wrapped += leaves[candidate]
            }
            changed = true
            break
        }
        if (!changed) return wrapped
    }
    return wrapped
}

private const val MAX_WRAP_PASSES = 200

/**
 * What a line that carries an expression on lines up with: the token after an `=`, a compound
 * assignment or `return` for a line that starts with, or follows, an operator; the token after an open
 * `(` for a line inside the parentheses.
 */
private class ContinuationAlignments {
    private var expression: Alignment? = null
    private val parentheses = ArrayList<Alignment>()
    private var startsExpression = false

    fun alignmentFor(leaf: Leaf, startsLine: Boolean, continuesExpression: Boolean): Alignment? {
        var alignment: Alignment? = null
        if (leaf.previous?.type == BwslTokenTypes.LPAREN) {
            alignment = Alignment.createAlignment().also { parentheses += it }
        } else if (startsLine && parentheses.isNotEmpty() && leaf.type != BwslTokenTypes.RPAREN) {
            alignment = parentheses.last()
        } else if (startsExpression) {
            alignment = Alignment.createAlignment().also { expression = it }
        } else if (continuesExpression && parentheses.isEmpty()) {
            alignment = expression
        }
        startsExpression = leaf.type in EXPRESSION_STARTERS && parentheses.isEmpty()
        when (leaf.type) {
            BwslTokenTypes.RPAREN -> parentheses.removeLastOrNull()
            BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE -> if (parentheses.isEmpty()) expression = null
        }
        return alignment
    }

    private companion object {
        val EXPRESSION_STARTERS = setOf(
            BwslTokenTypes.EQ, BwslTokenTypes.PLUSEQ, BwslTokenTypes.MINUSEQ, BwslTokenTypes.STAREQ, BwslTokenTypes.SLASHEQ,
            BwslTokenTypes.PERCENTEQ, BwslTokenTypes.AMPEQ, BwslTokenTypes.PIPEEQ, BwslTokenTypes.CARETEQ,
            BwslTokenTypes.LSHIFTEQ, BwslTokenTypes.RSHIFTEQ, BwslTokenTypes.KW_RETURN
        )
    }
}

/**
 * The `{` of every block whose `}` is on a later line, or that holds a line break the formatter adds:
 * a token in [wrapped] starts a line, an `else` after a `}` goes to its own line when [movesElse], or a
 * block inside it spans lines.
 */
private fun collectMultiLineBraces(leaves: List<Leaf>, text: String, wrapped: Set<Leaf>, movesElse: Boolean): Set<Leaf> {
    class Frame(val open: Leaf, var hasBreak: Boolean = false)

    val multiLine = HashSet<Leaf>()
    val frames = ArrayList<Frame>()
    for (leaf in leaves) {
        if (leaf in wrapped) frames.lastOrNull()?.hasBreak = true
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> frames += Frame(leaf)
            BwslTokenTypes.RBRACE -> frames.removeLastOrNull()?.let { frame ->
                val spansLines = frame.hasBreak || text.substring(frame.open.range.startOffset, leaf.range.startOffset).contains("\n")
                if (spansLines) {
                    multiLine += frame.open
                    frames.lastOrNull()?.hasBreak = true
                }
                if (movesElse && leaf.nextSignificant?.type == BwslTokenTypes.KW_ELSE) frames.lastOrNull()?.hasBreak = true
            }
        }
    }
    return multiLine
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
 * How two tokens that follow each other are separated: [min] to [max] spaces when they share a line,
 * at least [minLineFeeds] line breaks between them, and whether a line break that is there is kept.
 */
internal class SpacingRule(val min: Int, val max: Int, val minLineFeeds: Int = 0, val keepLineBreaks: Boolean = true)

/** What a block's `{` can follow: a header's `)`, a name, a string (`pass "Main"`), a keyword (`else`, `vertex`) or a type. */
private fun isBraceOwner(type: IElementType): Boolean = type in BRACE_OWNERS || type.toString().startsWith("KW_")

private val BRACE_OWNERS = setOf(
    BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET, BwslTokenTypes.IDENTIFIER, BwslTokenTypes.MODULE_NAME,
    BwslTokenTypes.FUNCTION_DECLARATION, BwslTokenTypes.STRING_LIT
)

/**
 * The number of spaces to put between [first] and [second] when they are on one line, as a
 * (minimum, maximum) pair, or null to leave what is there. Only unambiguous places have a rule: `<`
 * and `>` (a comparison or a generic's brackets), `:` and `?` (a label, an entry, a ternary), `^` (an
 * operator or a pointer), `..` and a sign are left alone.
 */
internal fun spacingBetween(
    first: Leaf,
    second: Leaf,
    braceStyle: Int = BraceStyle.KEEP_AS_WRITTEN,
    isMultiLineBrace: Boolean = false
): SpacingRule? {
    val one = SpacingRule(1, 1)
    val none = SpacingRule(0, 0)
    val a = first.type
    val b = second.type

    if (first.isComment || second.isComment) return null
    if (b == BwslTokenTypes.COMMA || b == BwslTokenTypes.SEMI) return none
    if (a == BwslTokenTypes.COMMA) return one
    if (a == BwslTokenTypes.LPAREN || a == BwslTokenTypes.LBRACKET) return none
    if (b == BwslTokenTypes.RPAREN || b == BwslTokenTypes.RBRACKET) return none
    if (b == BwslTokenTypes.LPAREN && a in CONTROL_KEYWORDS) return one

    if (b == BwslTokenTypes.LBRACE) {
        if (a == BwslTokenTypes.LBRACE) return null
        // The brace style moves only the `{` of a block that spans lines, and only after what owns the block.
        if (isMultiLineBrace && isBraceOwner(a)) {
            when (braceStyle) {
                BraceStyle.END_OF_LINE -> return SpacingRule(1, 1, keepLineBreaks = false)
                BraceStyle.NEXT_LINE -> return SpacingRule(0, 0, minLineFeeds = 1)
            }
        }
        return one
    }
    if (a == BwslTokenTypes.LBRACE) return if (b == BwslTokenTypes.RBRACE) null else one
    if (b == BwslTokenTypes.RBRACE) return one
    if (a == BwslTokenTypes.RBRACE) {
        if (b == BwslTokenTypes.KW_ELSE) {
            when (braceStyle) {
                BraceStyle.END_OF_LINE -> return SpacingRule(1, 1, keepLineBreaks = false)
                BraceStyle.NEXT_LINE -> return SpacingRule(0, 0, minLineFeeds = 1)
            }
        }
        return one
    }

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

    /** The `(` depth at which a `case`/`default` label was started, until its `:` is reached. */
    private var labelParenDepth: Int? = null

    /** The indent of the line [leaf] starts, in spaces; only used when it is the first token of its line. */
    fun indentFor(leaf: Leaf): Int {
        var braces = braceDepth
        var bodies = bodyDepth
        var parens = parenDepth
        when {
            leaf.type == BwslTokenTypes.RBRACE -> {
                braces = (braceDepth - 1).coerceAtLeast(0)
                frames.lastOrNull()?.let { bodies = it.bodyDepthAtOpen }
                parens = 0
            }
            leaf.type == BwslTokenTypes.RPAREN || leaf.type == BwslTokenTypes.RBRACKET -> parens = (parenDepth - 1).coerceAtLeast(0)
            // A label sits where the previous label did: the body of that one ends here.
            isLabelStart(leaf) -> bodies -= countLevelsOfOpenCaseBody()
            pending != null && leaf.type != BwslTokenTypes.LBRACE -> bodies++
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
        val startsLabel = isLabelStart(leaf)
        if (startsLabel) endCaseBody()
        // A label right after another (`case 1:` `case 2:`) is not the body of the first.
        val waiting = if (startsLabel) null else pending
        pending = null
        if (waiting != null && leaf.type != BwslTokenTypes.LBRACE && leaf.isFirstOnLine) {
            waiting.isBodyOnItsOwnLine = true
            bodyDepth++
        }
        if (startsLabel) labelParenDepth = parenDepth
        when (leaf.type) {
            BwslTokenTypes.COLON -> if (labelParenDepth == parenDepth) {
                labelParenDepth = null
                openConstruct(BwslTokenTypes.KW_CASE)
            }
            BwslTokenTypes.LBRACE -> {
                labelParenDepth = null
                frames += Frame(bodyDepth, constructs.size, isConstructBody = waiting != null)
                braceDepth++
            }
            BwslTokenTypes.RBRACE -> {
                labelParenDepth = null
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
            BwslTokenTypes.SEMI -> {
                labelParenDepth = null
                if (parenDepth == 0) endStatement(leaf)
            }
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
        // A `case` body holds many statements: it ends at the next label or the switch's `}`, not at a `;`.
        while (constructs.size > floor && constructs.last().keyword != BwslTokenTypes.KW_CASE) {
            val ended = constructs.removeLast()
            if (ended.isBodyOnItsOwnLine) bodyDepth--
            if (nextIsElse && ended.keyword == BwslTokenTypes.KW_IF) break
        }
    }

    /** Whether [leaf] starts a `case X:` or `default:` label (a `default` is one only when a `:` follows it). */
    private fun isLabelStart(leaf: Leaf): Boolean =
        leaf.type == BwslTokenTypes.KW_CASE ||
            (leaf.type == BwslTokenTypes.KW_DEFAULT && leaf.nextSignificant?.type == BwslTokenTypes.COLON)

    /** The `case` construct of the innermost switch block, with the index it has in [constructs], or null. */
    private fun findOpenCase(): Int? {
        val floor = frames.lastOrNull()?.constructsAtOpen ?: 0
        return constructs.indexOfLast { it.keyword == BwslTokenTypes.KW_CASE }.takeIf { it >= floor }
    }

    /** How many levels the body of the open `case`, and what is open inside it, add on a line of its own. */
    private fun countLevelsOfOpenCaseBody(): Int {
        val index = findOpenCase() ?: return 0
        return constructs.drop(index).count { it.isBodyOnItsOwnLine }
    }

    /** A new label ended the body of the open `case`, and whatever was left open inside it. */
    private fun endCaseBody() {
        val index = findOpenCase() ?: return
        while (constructs.size > index) {
            if (constructs.removeLast().isBodyOnItsOwnLine) bodyDepth--
        }
    }

    /** Whether [leaf] starts a line that carries on an expression: it follows, or starts with, an operator. */
    fun isContinuationLine(leaf: Leaf): Boolean {
        if (!leaf.isFirstOnLine || leaf.isComment) return false
        val previous = leaf.previousSignificant ?: return false
        if (previous.type in CONTINUING_OPERATORS) return true
        return leaf.type in CONTINUING_OPERATORS && previous.type !in STATEMENT_BOUNDARIES
    }
}
