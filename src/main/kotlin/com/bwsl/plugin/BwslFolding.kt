package com.bwsl.plugin

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

private const val MAX_PLACEHOLDER_TEXT = 40

/**
 * Folding for what spans lines: every `{ ... }` block (a function, struct, pass, stage, loop or
 * `if` body is one), a multi-line `/* ... */` comment, and a run of two or more `//` comment lines.
 * Found from the tokens, so it also works while the file does not compile.
 */
class BwslFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root.containingFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val leaves = collectLeaves(file)
        val regions = ArrayList<FoldingDescriptor>()

        val openBraces = ArrayList<Leaf>()
        for (leaf in leaves) {
            when (leaf.type) {
                BwslTokenTypes.LBRACE -> openBraces += leaf
                BwslTokenTypes.RBRACE -> openBraces.removeLastOrNull()?.let { open ->
                    if (isOnSeveralLines(document, open.range.startOffset, leaf.range.endOffset)) {
                        regions += FoldingDescriptor(open.node, TextRange(open.range.startOffset, leaf.range.endOffset), null, "{...}")
                    }
                }
                BwslTokenTypes.BLOCK_COMMENT ->
                    if (isOnSeveralLines(document, leaf.range.startOffset, leaf.range.endOffset)) {
                        regions += FoldingDescriptor(leaf.node, leaf.range, null, "/*...*/")
                    }
            }
        }
        regions += collectCommentRuns(leaves, document.charsSequence).map { run ->
            val range = TextRange(run.first().range.startOffset, run.last().range.endOffset)
            FoldingDescriptor(run.first().node, range, null, describeCommentRun(run.first(), document.charsSequence))
        }
        return regions.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private fun isOnSeveralLines(document: Document, start: Int, end: Int): Boolean =
        document.getLineNumber(start) != document.getLineNumber((end - 1).coerceAtLeast(start))
}

/** Runs of two or more `//` comments that each start a line, one directly under the other. */
private fun collectCommentRuns(leaves: List<Leaf>, text: CharSequence): List<List<Leaf>> {
    val runs = ArrayList<List<Leaf>>()
    var current = ArrayList<Leaf>()
    fun finishRun() {
        if (current.size >= 2) runs += current
        current = ArrayList()
    }
    for (leaf in leaves) {
        if (leaf.type != BwslTokenTypes.LINE_COMMENT || !leaf.isFirstOnLine) {
            finishRun()
            continue
        }
        val previous = current.lastOrNull()
        val isNextLine = previous != null && text.subSequence(previous.range.endOffset, leaf.range.startOffset).count { it == '\n' } == 1
        if (previous != null && !isNextLine) finishRun()
        current += leaf
    }
    finishRun()
    return runs
}

/** What a folded run of line comments shows: its first line's text, shortened. */
private fun describeCommentRun(first: Leaf, text: CharSequence): String {
    val body = text.subSequence(first.range.startOffset, first.range.endOffset).removePrefix("//").trim()
    val shown = if (body.length > MAX_PLACEHOLDER_TEXT) body.take(MAX_PLACEHOLDER_TEXT).trimEnd() else body
    return "// $shown..."
}
