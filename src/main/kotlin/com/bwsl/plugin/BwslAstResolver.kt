package com.bwsl.plugin

import com.bwsl.plugin.references.findIdentifierInRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Resolves the declaration(s) a caret position points to, using bwslc's own reference index
 * ([BwslAstIndex]) instead of re-deriving BWSL's scoping rules. See FUCK_THE_LEXER.md §5/§6
 * (Phase 3) for the design.
 *
 * Returns an empty list when there's genuinely no reference at [offset] - e.g. the caret is on a
 * declaration's own name (not its type), or the target is a `builtin:*` symbol with no source
 * location. Callers must not fall back to lexical resolution in that case: per FUCK_THE_LEXER.md's
 * Phase 6 decision, "no AST cached (or no reference found)" means "no reference", full stop.
 */
fun resolveSymbolAt(file: PsiFile, index: BwslAstIndex, offset: Int): List<PsiElement> {
    val fromId = symbolOccurrenceAt(index, offset) ?: return emptyList()
    val edges = index.refsByFrom[fromId].orEmpty()
    if (edges.isEmpty()) return emptyList()

    val declarationIds = edges.mapNotNull { index.symbolsById[it.to]?.declaration }.distinct()
    return declarationIds.mapNotNull { declId -> resolveDeclarationPosition(file, index, declId) }
}

/**
 * The id of the AST node whose *occurrence* (not declaration) [offset] falls within - i.e. the
 * "from" side of a reference edge.
 *
 * A VARIABLE_DECL's own name is deliberately excluded here even though [BwslAstIndex.nodeAtOffset]
 * would happily match it: VARIABLE_DECL has an outgoing `type` edge (describing its declared
 * type), and following that edge for a caret on the *name* would incorrectly navigate a variable's
 * own declaration to its type. Only a caret on the *type* text should follow that edge - see the
 * role-awareness note in FUCK_THE_LEXER.md's Phase 3.
 */
private fun symbolOccurrenceAt(index: BwslAstIndex, offset: Int): String? {
    index.nodeAtOffset(offset)?.let { hit ->
        return if (hit.type == "VARIABLE_DECL") null else hit.id
    }
    return index.variableDeclTypeAtOffset(offset)?.id
}

/** Resolves a declaration id (real, synthetic, or builtin) from [AstSymbol.declaration] to a PSI element. */
private fun resolveDeclarationPosition(file: PsiFile, index: BwslAstIndex, declId: String): PsiElement? {
    if (declId.startsWith("builtin:")) return null

    index.nodesById[declId]?.let { node ->
        val range = index.nameRangeOf(node) ?: return null
        return file.findElementAt(range.first)
    }

    // Synthetic id, shaped "OWNER/kind:name-or-index" (e.g. "FUNCTION:0/parameter:0",
    // "STRUCT_DECL:0/field:0", "PASS:0/interface:uv", "PASS:0/fragment-output:color") - no
    // position of its own; see FUCK_THE_LEXER.md gaps #1 and #3.
    val symbol = index.symbolsById[declId] ?: return null
    val kind = declId.substringAfter('/', "").substringBefore(':')

    if (kind == "interface" || kind == "fragment-output") {
        // Resolve via the edge that *writes* it (the vertex stage's `output.<member> = ...`
        // assignment) rather than the owner's range - these symbols have no position of their
        // own to search near.
        val definingEdge = index.refsByTo[declId]?.firstOrNull { it.role == "output" } ?: return null
        val definingNode = index.nodesById[definingEdge.from] ?: return null
        val range = index.nameRangeOf(definingNode) ?: return null
        return file.findElementAt(range.first)
    }

    val ownerId = declId.substringBefore('/')
    val owner = index.nodesById[ownerId] ?: return null
    val ownerRange = index.ownerRangeOf(owner) ?: return null
    return findIdentifierInRange(file, symbol.name, ownerRange)
}
