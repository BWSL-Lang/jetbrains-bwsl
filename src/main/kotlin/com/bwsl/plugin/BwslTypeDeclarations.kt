package com.bwsl.plugin

import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement

/**
 * The declarations of the type of the symbol at [offset]: for a variable, parameter or struct field the
 * struct its declared type names, for a function or method the struct it returns, for a struct itself
 * that struct. Followed from the compiler's `type`/`return-type` edge, so a type from another module
 * (`Mod::Type`) opens in that module's file. Built-in types such as `float4` have no source and give
 * nothing.
 */
internal fun findTypeDeclarationsAt(file: com.intellij.psi.PsiFile, offset: Int): List<PsiElement> {
    val index = buildAstIndex(file) ?: return emptyList()
    val symbol = findSymbolAt(index, offset) ?: return emptyList()
    if (symbol.kind == "struct") return listOfNotNull(resolveDeclarationPosition(file, index, symbol.declaration))
    return index.refsByFrom[symbol.declaration].orEmpty()
        .filter { it.role == "type" || it.role == "return-type" }
        .mapNotNull { index.symbolsById[it.to] }
        .filter { it.kind == "struct" }
        .distinct()
        .mapNotNull { resolveDeclarationPosition(file, index, it.declaration) }
}

/** Go to Type Declaration (Ctrl+Shift+B): see [findTypeDeclarationsAt]. */
class BwslTypeDeclarationProvider : TypeDeclarationProvider {
    /** [symbol] is what the caret is on, or what it refers to: either way the declaration's own name. */
    override fun getSymbolTypeDeclarations(symbol: PsiElement): Array<PsiElement>? {
        val file = symbol.containingFile ?: return null
        if (file.language != BwslLanguage) return null
        return findTypeDeclarationsAt(file, symbol.textRange.startOffset).takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
