package com.bwsl.plugin

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType

/**
 * Resolves the declaration(s) a caret position points to, using bwslc's own reference index
 * ([BwslAstIndex]) instead of re-deriving BWSL's scoping rules.
 *
 * Returns an empty list when there's genuinely no reference at [offset] - e.g. the caret is on a
 * declaration's own name (not its type), or the target is a `builtin:*` symbol with no source
 * location. Callers must not fall back to lexical resolution in that case: "no AST cached (or no
 * reference found)" means "no reference", full stop.
 */
fun resolveSymbolAt(file: PsiFile, index: BwslAstIndex, offset: Int): List<PsiElement> =
    declarationIdsAt(index, offset).mapNotNull { declId -> resolveDeclarationPosition(file, index, declId) }

/**
 * Builds the [BwslAstIndex] for [file] from what [BwslAstCache] has cached for it, or null when no
 * AST (or no raw JSON) is cached. Callers must not fall back to lexical resolution in that case.
 */
fun buildAstIndex(file: PsiFile): BwslAstIndex? {
    val path = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(path) ?: return null
    val rawRoot = BwslAstCache.getRawRoot(path) ?: return null
    return BwslAstIndex(root, rawRoot, file.text)
}

/**
 * The declaration ids ([AstSymbol.declaration]) the caret at [offset] refers to, following the
 * reference edges out of the node there. Empty when nothing is referenced - a caret on a
 * declaration's own name refers to nothing, it *is* the declaration (see [symbolAt]).
 */
fun declarationIdsAt(index: BwslAstIndex, offset: Int): List<String> =
    referenceEdgesAt(index, offset).mapNotNull { index.symbolsById[it.to]?.declaration }.distinct()

/**
 * The reference edges out of the node at [offset]: the edges of the node whose name is there, minus
 * the `type`/`return-type` ones that merely describe a declaration (following those from a
 * declaration's own name would navigate a field to its type). A caret on a variable's declared-type
 * text is the one place those are the answer.
 */
fun referenceEdgesAt(index: BwslAstIndex, offset: Int): List<AstReference> {
    index.nodeAtOffset(offset)?.let { hit ->
        return index.refsByFrom[hit.id].orEmpty().filter { it.role !in DECLARATION_ROLES }
    }
    val declared = index.variableDeclTypeAtOffset(offset) ?: return emptyList()
    return index.refsByFrom[declared.id].orEmpty()
}

/**
 * The symbol the caret at [offset] is on or refers to: the declaration's own symbol when the caret
 * is on a declaration's name, otherwise the symbol its reference edge points at.
 */
fun symbolAt(index: BwslAstIndex, offset: Int): AstSymbol? {
    index.nodeAtOffset(offset)?.let { hit -> index.symbolsById[hit.id]?.let { return it } }
    return declarationIdsAt(index, offset).firstNotNullOfOrNull { index.symbolsById[it] }
}

/**
 * The signature of a function or method symbol, built from the index: its return type is the
 * symbol's type, and its parameters are the parameter symbols it owns, in order. Null for any other
 * kind of symbol.
 */
fun functionSignatureOf(index: BwslAstIndex, symbol: AstSymbol): BwslFunctionSignature? {
    if (symbol.kind != "function" && symbol.kind != "method") return null
    val params = index.symbolsById.values
        .filter { it.kind == "parameter" && it.owner == symbol.declaration }
        .sortedBy { it.id.substringAfterLast(':').toIntOrNull() ?: Int.MAX_VALUE }
        .map { "${it.type} ${it.name}" }
    return BwslFunctionSignature(symbol.name, params, symbol.type)
}

/** Edge roles that *describe a declaration* (its type) rather than naming something at the caret. */
private val DECLARATION_ROLES = setOf("type", "return-type")

/**
 * The leaf token at [offset], promoted to its parent when that parent is a REFERENCE composite
 * (BWSL wraps some identifier occurrences - notably VARIABLE_DECL names, which are syntactically
 * ambiguous with usages - in a REFERENCE node; declaration-site tokens for functions/modules/
 * structs are not).
 */
private fun elementAt(file: PsiFile, offset: Int): PsiElement? {
    val leaf = file.findElementAt(offset) ?: return null
    val parent = leaf.parent
    return if (parent?.elementType == BwslTokenTypes.REFERENCE) parent else leaf
}

/**
 * Resolves a declaration written in another file - an imported module's, or a member a `submodule`
 * merged into one - by opening the file the node names in its own `sourceFile` and measuring the
 * node's position against *that* file's text. Returns null if the file can't be opened, rather than
 * guessing a position in the wrong file.
 */
private fun resolveInSourceFile(file: PsiFile, index: BwslAstIndex, node: AstNodePos): PsiElement? {
    val path = node.sourceFile ?: return null
    val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/')) ?: return null
    val target = PsiManager.getInstance(file.project).findFile(virtualFile) ?: return null
    val range = index.positionsFor(target.text).nameRangeOf(node) ?: return null
    return elementAt(target, range.first)
}

/** Resolves a declaration id (real, synthetic, or builtin) from [AstSymbol.declaration] to a PSI element. */
private fun resolveDeclarationPosition(file: PsiFile, index: BwslAstIndex, declId: String): PsiElement? {
    if (declId.startsWith("builtin:")) return null

    index.nodesById[declId]?.let { node ->
        return index.nameRangeOf(node)?.let { range -> elementAt(file, range.first) }
    }
    index.externalNodesById[declId]?.let { node ->
        return resolveInSourceFile(file, index, node)
    }

    // The one declaration with no node of its own: a stage-interface value (`PASS:0/interface:uv`).
    // The language has no declaration for it - the vertex stage's `output.<member> = ...` is its
    // definition - so go to the first assignment the symbol lists, at that assignment's target.
    val symbol = index.symbolsById[declId] ?: return null
    if (declId.substringAfter('/', "").substringBefore(':') != "interface") return null
    val targetId = symbol.definitions.firstNotNullOfOrNull { index.assignmentTargetOf(it) } ?: return null
    val targetNode = index.nodesById[targetId] ?: return null
    return index.nameRangeOf(targetNode)?.let { range -> elementAt(file, range.first) }
}
