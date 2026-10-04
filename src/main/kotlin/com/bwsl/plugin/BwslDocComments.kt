package com.bwsl.plugin

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile

private val URL_PATTERN = Regex("https?://[^\\s<>()\"]+[^\\s<>()\".,;:!?]")
private val CODE_PATTERN = Regex("`([^`\\n]+)`")

/**
 * The documentation comment written directly above the declaration whose name is at [nameOffset]:
 * `///` lines, or a `/** ... */` block, ending on the line before the declaration starts (no blank
 * line and no ordinary `//` comment between). Returns its text with the comment markers removed, one
 * line per source line, or null if there is none. Works on the text of [file], so it also reads the
 * documentation of a declaration in another file, such as a copy of a standard module.
 */
fun findDocCommentAbove(file: PsiFile, nameOffset: Int): String? {
    val text = file.text
    val leaves = collectLeaves(file)
    val name = leaves.firstOrNull { nameOffset >= it.range.startOffset && nameOffset < it.range.endOffset } ?: return null

    // The declaration starts with the first token of the line its name is on.
    var start = name
    while (!start.isFirstOnLine) start = start.previous ?: break

    val lines = ArrayList<String>()
    var below = start
    var comment = start.previous
    while (comment != null && comment.isComment && isDirectlyAbove(text, comment, below)) {
        val commentText = text.substring(comment.range.startOffset, comment.range.endOffset)
        when {
            comment.type == BwslTokenTypes.LINE_COMMENT && isDocLine(commentText) -> lines.add(0, stripDocLine(commentText))
            comment.type == BwslTokenTypes.BLOCK_COMMENT && isDocBlock(commentText) -> lines.addAll(0, stripDocBlock(commentText))
            else -> break
        }
        below = comment
        comment = comment.previous
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** Whether [comment] ends on the line right above the one [below] starts on. */
private fun isDirectlyAbove(text: String, comment: Leaf, below: Leaf): Boolean {
    val between = text.substring(comment.range.endOffset, below.range.startOffset)
    return between.count { it == '\n' } == 1 && between.isBlank()
}

private fun isDocLine(commentText: String): Boolean = commentText.startsWith("///") && !commentText.startsWith("////")

private fun isDocBlock(commentText: String): Boolean = commentText.startsWith("/**") && commentText != "/**/"

private fun stripDocLine(commentText: String): String = commentText.removePrefix("///").removePrefix(" ").trimEnd()

private fun stripDocBlock(commentText: String): List<String> =
    commentText.removePrefix("/**").removeSuffix("*/").lines()
        .map { it.trim().removePrefix("*").removePrefix(" ").trimEnd() }
        .dropWhile { it.isEmpty() }
        .dropLastWhile { it.isEmpty() }

/**
 * A doc comment's text as HTML for the documentation popup: blank lines separate paragraphs, the lines
 * of a paragraph run together, `code` in backticks is code, and a web address is a link.
 */
fun renderDocCommentHtml(commentText: String): String =
    commentText.split(Regex("\n\\s*\n")).map { it.lines().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("<br/><br/>") { paragraph ->
            val escaped = StringUtil.escapeXmlEntities(paragraph)
            val withCode = CODE_PATTERN.replace(escaped) { "<code>${it.groupValues[1]}</code>" }
            URL_PATTERN.replace(withCode) { "<a href=\"${it.value}\">${it.value}</a>" }
        }
