package com.bwsl.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope

/**
 * A declaration, identified independently of any one compile: the ids in a payload (`FUNCTION:3`)
 * mean different things in different payloads, but a symbol's `stableId`
 * (`module:Common/function:helper()->float`) names the same declaration in every payload that
 * contains it. A symbol with no `stableId` (a local) exists only in [payloadPath].
 */
data class DeclarationIdentity(val symbol: AstSymbol, val payloadPath: String) {
    val stableId: String get() = symbol.stableId
}

/** The project's BWSL files that have a cached AST - the compiles whose edges can name a usage. */
internal fun collectPayloadFiles(project: Project): List<PsiFile> {
    val manager = PsiManager.getInstance(project)
    return FileTypeIndex.getFiles(BwslFileType, GlobalSearchScope.allScope(project))
        .filter { BwslAstCache.findRoot(it.path) != null && BwslAstCache.findRawRoot(it.path) != null }
        .mapNotNull { manager.findFile(it) }
}

private fun doesPathMatch(a: String, b: String): Boolean {
    val x = a.replace('\\', '/')
    val y = b.replace('\\', '/')
    return if (SystemInfo.isFileSystemCaseSensitive) x == y else x.equals(y, ignoreCase = true)
}

/**
 * The declaration whose *name* is [element], or null if [element] is not a declaration name. Looks
 * in the element's own file's AST first, then in the AST of any other file that imports it (its
 * declarations appear there as nodes written in this file).
 */
fun findDeclarationIdentityOf(element: PsiElement): DeclarationIdentity? {
    val file = element.containingFile ?: return null
    if (file.language != BwslLanguage) return null
    val path = file.virtualFile?.path ?: return null
    val offset = element.textOffset

    buildAstIndex(file)?.let { index ->
        val symbol = index.findNodeAtOffset(offset)?.let { index.symbolsById[it.id] }
        if (symbol != null) return DeclarationIdentity(symbol, path)
    }

    val positions = SourcePositions(file.text)
    for (other in collectPayloadFiles(file.project)) {
        val otherPath = other.virtualFile?.path ?: continue
        if (otherPath == path) continue
        val index = buildAstIndex(other) ?: continue
        val declared = index.externalNodesById.values.firstOrNull { node ->
            node.sourceFile != null && doesPathMatch(node.sourceFile, path) &&
                index.symbolsById.containsKey(node.id) &&
                positions.findNameRangeOf(node)?.let { offset in it } == true
        } ?: continue
        return DeclarationIdentity(index.symbolsById.getValue(declared.id), otherPath)
    }
    return null
}

/**
 * Every reference to the declaration named by [target], found by following the compiler's incoming
 * reference edges (`refsByTo`) in each cached AST, not by scanning text for the name. Only
 * references the editor itself resolves back to [target] are returned. A file with no cached AST
 * has no known references.
 */
fun findUsagesOf(target: PsiElement, scope: SearchScope): List<PsiReference> {
    val identity = findDeclarationIdentityOf(target) ?: return emptyList()
    val targetFile = target.containingFile
    val targetOffset = target.textOffset

    val references = LinkedHashMap<Pair<String, Int>, PsiReference>()
    for (file in collectPayloadFiles(target.project)) {
        val path = file.virtualFile?.path ?: continue
        val index = buildAstIndex(file) ?: continue
        val declarationId = when {
            path == identity.payloadPath -> identity.symbol.id
            identity.stableId.isNotEmpty() ->
                index.symbolsById.values.firstOrNull { it.stableId == identity.stableId }?.id ?: continue
            else -> continue
        }

        for (edge in index.refsByTo[declarationId].orEmpty()) {
            val from = index.nodesById[edge.from] ?: continue
            val offset = findUsageOffset(index, from, edge) ?: continue
            val reference = file.findReferenceAt(offset) ?: continue
            if (!PsiSearchScopeUtil.isInScope(scope, reference.element)) continue
            if (!doesReferenceResolveTo(reference, targetFile, targetOffset)) continue
            references[path to reference.element.textOffset + reference.rangeInElement.startOffset] = reference
        }
    }
    return references.values.toList()
}

/**
 * An offset inside the text that makes [from] a usage. For a `type`/`return-type` edge that is the
 * end of the declared-type text (the type's own name, after any `Mod::`), since the node's name is
 * the declaration's, not the usage's; otherwise it is the node's name.
 */
private fun findUsageOffset(index: BwslAstIndex, from: AstNodePos, edge: AstReference): Int? =
    if (edge.role == "type" || edge.role == "return-type") index.findTypeRangeOf(from)?.last
    else index.findNameRangeOf(from)?.first

private fun doesReferenceResolveTo(reference: PsiReference, targetFile: PsiFile, targetOffset: Int): Boolean {
    val resolved = if (reference is PsiPolyVariantReference)
        reference.multiResolve(false).mapNotNull { it.element }
    else listOfNotNull(reference.resolve())
    return resolved.any {
        it.containingFile?.virtualFile == targetFile.virtualFile && it.textOffset == targetOffset
    }
}
