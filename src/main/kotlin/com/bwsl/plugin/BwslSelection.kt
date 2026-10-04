package com.bwsl.plugin

import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandlerBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Extend Selection (Ctrl+W) for BWSL, which has a flat PSI: the ranges around the caret come from the
 * tokens. From the inside out: the argument, the contents of each enclosing `( )` or `[ ]` and then the
 * group with its brackets, the statement, the contents of each enclosing block, and the block with the
 * header that owns it (a whole function, struct, pass, `if`, ...).
 */
class BwslExtendWordSelectionHandler : ExtendWordSelectionHandlerBase() {

    override fun canSelect(e: PsiElement): Boolean = e.language == BwslLanguage

    override fun select(e: PsiElement, editorText: CharSequence, cursorOffset: Int, editor: Editor): List<TextRange> =
        collectSelectionRanges(e.containingFile ?: return emptyList(), cursorOffset)
}

/** The ranges that can be selected around [offset], each containing the caret; unordered, the platform picks the next larger one. */
internal fun collectSelectionRanges(file: PsiFile, offset: Int): List<TextRange> {
    val leaves = collectLeaves(file)
    if (leaves.isEmpty()) return emptyList()
    val index = leaves.indexOfFirst { offset >= it.range.startOffset && offset < it.range.endOffset }
        .takeIf { it >= 0 } ?: leaves.indexOfLast { it.range.endOffset <= offset }.takeIf { it >= 0 } ?: return emptyList()
    val groups = findGroups(leaves)
    val ranges = LinkedHashSet<TextRange>()

    fun add(startIndex: Int, endIndex: Int) {
        if (startIndex > endIndex) return
        ranges += TextRange(leaves[startIndex].range.startOffset, leaves[endIndex].range.endOffset)
    }

    // The groups around the token, innermost first.
    val around = groups.filter { it.open < index && index < it.close }.sortedBy { it.close - it.open }
    for (group in around) {
        if (leaves[group.open].type != BwslTokenTypes.LBRACE) {
            // An argument, the contents of the brackets, then the brackets with what they hold.
            val (argumentStart, argumentEnd) = findArgumentBounds(leaves, group, index)
            add(argumentStart, argumentEnd)
            add(group.open + 1, group.close - 1)
            add(group.open, group.close)
        } else {
            val region = Region(group.open + 1, group.close - 1)
            findStatementBounds(leaves, groups, region, index)?.let { add(it.first, it.second) }
            add(group.open + 1, group.close - 1)
            add(group.open, group.close)
            // The header the block belongs to, with the block: a whole function, struct or `if`.
            findStatementBounds(leaves, groups, parentRegion(leaves, groups, group), group.open)?.let { add(it.first, group.close) }
        }
    }
    // The statement at the top level of the file, when the caret is not inside a block.
    if (around.none { leaves[it.open].type == BwslTokenTypes.LBRACE }) {
        findStatementBounds(leaves, groups, Region(0, leaves.lastIndex), index)?.let { add(it.first, it.second) }
    }
    ranges += TextRange(leaves.first().range.startOffset, leaves.last().range.endOffset)
    return ranges.filter { it.containsOffset(offset) || it.endOffset == offset }
}

/** The tokens from [first] to [last], inclusive, that a statement can be looked for in. */
private class Region(val first: Int, val last: Int)

/** An open bracket and its close, as token indexes. */
private class Group(val open: Int, val close: Int)

private val OPENERS = mapOf(
    BwslTokenTypes.LPAREN to BwslTokenTypes.RPAREN,
    BwslTokenTypes.LBRACKET to BwslTokenTypes.RBRACKET,
    BwslTokenTypes.LBRACE to BwslTokenTypes.RBRACE
)

private fun findGroups(leaves: List<Leaf>): List<Group> {
    val groups = ArrayList<Group>()
    val open = ArrayList<Int>()
    for ((index, leaf) in leaves.withIndex()) {
        if (leaf.type in OPENERS) open += index
        else if (leaf.type in OPENERS.values) {
            val opener = open.removeLastOrNull() ?: continue
            if (OPENERS[leaves[opener].type] == leaf.type) groups += Group(opener, index) else open += opener
        }
    }
    return groups
}

/** The tokens of the argument the token at [index] is in: between the commas (or the bracket) around it. */
private fun findArgumentBounds(leaves: List<Leaf>, group: Group, index: Int): Pair<Int, Int> {
    var start = group.open + 1
    var end = group.close - 1
    var depth = 0
    for (i in group.open + 1 until index) {
        when (leaves[i].type) {
            in OPENERS -> depth++
            in OPENERS.values -> depth--
            BwslTokenTypes.COMMA -> if (depth == 0) start = i + 1
        }
    }
    depth = 0
    for (i in index until group.close) {
        when (leaves[i].type) {
            in OPENERS -> depth++
            in OPENERS.values -> depth--
            BwslTokenTypes.COMMA -> if (depth == 0) { end = i - 1; break }
        }
    }
    return start to end
}

/** The region of the block (or the file) that holds [group]. */
private fun parentRegion(leaves: List<Leaf>, groups: List<Group>, group: Group): Region {
    val parent = groups.filter { it.open < group.open && group.close < it.close && leaves[it.open].type == BwslTokenTypes.LBRACE }
        .minByOrNull { it.close - it.open }
    return if (parent != null) Region(parent.open + 1, parent.close - 1) else Region(0, leaves.lastIndex)
}

/**
 * The first and last token of the statement at the top level of [region] that holds [index]: it starts
 * after the `;` or `}` (not before an `else`) before it, or at the start of the region, and ends at its
 * `;`, at the `}` of a block that ends it, or at the end of the region.
 */
private fun findStatementBounds(leaves: List<Leaf>, groups: List<Group>, region: Region, index: Int): Pair<Int, Int>? {
    if (region.first > region.last || index < region.first || index > region.last) return null
    val closeToOpen = groups.associate { it.close to it.open }
    val openToClose = groups.associate { it.open to it.close }

    // The token at the top level of the region that holds [index] (the opening of the group it is in, or itself).
    val top = groups.filter { it.open >= region.first && it.close <= region.last && it.open <= index && index <= it.close }
        .minByOrNull { -(it.close - it.open) }?.open ?: index

    var start = region.first
    var i = top - 1
    while (i >= region.first) {
        val leaf = leaves[i]
        if (leaf.type == BwslTokenTypes.SEMI) { start = i + 1; break }
        if (leaf.type == BwslTokenTypes.RBRACE && leaves.getOrNull(i + 1)?.type != BwslTokenTypes.KW_ELSE) { start = i + 1; break }
        i = if (leaf.type in OPENERS.values) (closeToOpen[i] ?: i) - 1 else i - 1
    }

    var end = region.last
    var j = top
    while (j <= region.last) {
        val leaf = leaves[j]
        if (leaf.type == BwslTokenTypes.SEMI) { end = j; break }
        if (leaf.type in OPENERS) {
            val close = openToClose[j] ?: break
            if (leaf.type == BwslTokenTypes.LBRACE && leaves.getOrNull(close + 1)?.type != BwslTokenTypes.KW_ELSE) { end = close; break }
            j = close + 1
        } else {
            j++
        }
    }
    return if (start <= end) start to end else null
}
