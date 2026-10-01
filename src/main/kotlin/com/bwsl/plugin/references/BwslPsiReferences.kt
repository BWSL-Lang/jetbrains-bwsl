package com.bwsl.plugin.references

import com.bwsl.plugin.*

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult

/**
 * The single reference for everything [resolveSymbolAt] can resolve: identifier reads, function
 * calls (unqualified/receiver/module-qualified), a variable's declared-type text, `Mod::` type
 * qualifiers, member access (including vertex/fragment `output`/`input` linking and
 * `attributes.x`), `use attributes { ... }` names, and `import`/`using` module names. There are no
 * other reference classes: every one of these is a positioned node with an edge in bwslc's
 * reference index (`bwsl.ast.v3`).
 */
class BwslAstReference(element: PsiElement) :
    PsiPolyVariantReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val file = element.containingFile
        val index = buildAstIndex(file) ?: return ResolveResult.EMPTY_ARRAY
        return resolveSymbolAt(file, index, element.textOffset)
            .map { PsiElementResolveResult(it) as ResolveResult }
            .toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
