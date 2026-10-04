package com.bwsl.plugin

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** The name of a parameter, to show in front of the argument written at [offset]. */
data class ParameterNameHint(val offset: Int, val name: String)

/**
 * The parameter name for each argument of each call to a function or method the compiler resolved,
 * from the reference index: the call's edge names the function, and its parameters are the ones it
 * owns. An argument that is just the parameter's own name gets none, and neither does a call to an
 * intrinsic (its overloads differ). Null when no AST of the current text is cached, since offsets
 * of text typed since the compile would be wrong.
 */
internal fun collectParameterNameHints(file: PsiFile): List<ParameterNameHint>? {
    val input = findInspectionInput(file) ?: return null
    val index = input.index
    val leaves = collectLeaves(file)
    val hints = ArrayList<ParameterNameHint>()
    for ((position, leaf) in leaves.withIndex()) {
        if (leaf.type != BwslTokenTypes.FUNCTION_CALL) continue
        if (leaves.getOrNull(position + 1)?.type != BwslTokenTypes.LPAREN) continue
        val symbols = collectDeclarationIdsAt(index, leaf.range.startOffset).mapNotNull { index.symbolsById[it] }
        val callee = symbols.singleOrNull { it.kind == "function" || it.kind == "method" } ?: continue
        val parameters = index.symbolsById.values
            .filter { it.kind == "parameter" && it.owner == callee.declaration }
            .sortedBy { it.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }
        for ((argumentIndex, argument) in findArgumentTokens(leaves, position + 1).withIndex()) {
            val parameter = parameters.getOrNull(argumentIndex) ?: break
            val first = leaves[argument.first]
            val isJustTheName = argument.first == argument.last && first.node.text == parameter.name
            if (!isJustTheName) hints += ParameterNameHint(first.range.startOffset, parameter.name)
        }
    }
    return hints
}

/** The token indexes of each argument of the call whose `(` is at [open]; none for `()`. */
private fun findArgumentTokens(leaves: List<Leaf>, open: Int): List<IntRange> {
    val arguments = ArrayList<IntRange>()
    var depth = 0
    var start = open + 1
    for (i in open until leaves.size) {
        when (leaves[i].type) {
            BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET, BwslTokenTypes.LBRACE -> depth++
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET, BwslTokenTypes.RBRACE -> {
                depth--
                if (depth == 0) {
                    if (i > start) arguments += start until i
                    return arguments
                }
            }
            BwslTokenTypes.COMMA -> if (depth == 1) {
                if (i > start) arguments += start until i
                start = i + 1
            }
        }
    }
    return emptyList()
}

/** Parameter names in front of call arguments (Settings | Editor | Inlay Hints). */
class BwslParameterNameHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (file.language != BwslLanguage) return null
        val hints = collectParameterNameHints(file) ?: return null
        return object : SharedBypassCollector {
            override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
                if (element !is PsiFile) return
                for (hint in hints) {
                    sink.addPresentation(InlineInlayPosition(hint.offset, true), hintFormat = HintFormat.default) {
                        text("${hint.name}:")
                    }
                }
            }
        }
    }
}
