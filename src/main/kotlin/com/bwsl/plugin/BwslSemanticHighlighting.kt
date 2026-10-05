package com.bwsl.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** The colour of each kind of symbol that is coloured by what it is, not by how it is written. */
private val SEMANTIC_KEYS = mapOf(
    "parameter" to BwslSyntaxHighlighter.PARAMETER,
    "variable" to BwslSyntaxHighlighter.LOCAL_VARIABLE,
    "loop-iterator" to BwslSyntaxHighlighter.LOCAL_VARIABLE,
    "constant" to BwslSyntaxHighlighter.CONSTANT,
    "struct-field" to BwslSyntaxHighlighter.FIELD
)

/**
 * The name of every parameter, local, loop variable, constant and struct field written in the file,
 * declaration or use, with the colour for what it is - read from the compiler's reference index, so a
 * name is coloured by what it resolves to rather than how it is spelled. Empty when no AST of the
 * current text is cached.
 */
internal fun collectSemanticHighlights(file: PsiFile): List<Pair<TextRange, TextAttributesKey>> {
    val input = findInspectionInput(file) ?: return emptyList()
    val index = input.index
    val positions = SourcePositions(input.text)
    val result = ArrayList<Pair<TextRange, TextAttributesKey>>()
    for (node in index.nodesById.values) {
        val kind = index.symbolsById[node.id]?.kind?.takeIf { it in SEMANTIC_KEYS }
            ?: index.refsByFrom[node.id].orEmpty().firstNotNullOfOrNull { edge ->
                index.symbolsById[edge.to]?.kind?.takeIf { it in SEMANTIC_KEYS }
            }
            ?: continue
        val range = positions.findNameRangeOf(node) ?: continue
        result += TextRange(range.first, range.last + 1) to SEMANTIC_KEYS.getValue(kind)
    }
    return result.distinctBy { it.first }
}

/** Colours parameters, locals, constants and fields differently (see [collectSemanticHighlights]). */
class BwslSemanticAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        for ((range, key) in collectSemanticHighlights(element)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
        }
    }
}
