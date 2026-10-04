package com.bwsl.plugin.references

import com.bwsl.plugin.*

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.util.ProcessingContext
import com.intellij.psi.TokenType

class BwslReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withElementType(BwslTokenTypes.REFERENCE),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val innerType = element.firstChild?.node?.elementType ?: return emptyArray()
                    if (innerType == BwslTokenTypes.INTRINSIC_CALL) {
                        return emptyArray()
                    }
                    if (innerType == BwslTokenTypes.MODULE_NAME) {
                        // The declaration site of a module ("module <Name> { ... }") has no reference.
                        return emptyArray()
                    }
                    // Everything else - identifier reads, function calls (unqualified / receiver /
                    // module-qualified), a variable's declared-type text and `Mod::` qualifier,
                    // member access (including output/input linking), `use attributes` names and
                    // `import`/`using` module names - is a positioned node with an edge in the
                    // compiler's reference index, so resolveSymbolAt handles it generically. A caret
                    // on a declaration's own name resolves to nothing there, so that needs no
                    // separate dispatch either.
                    return arrayOf(BwslAstReference(element))
                }
            }
        )
    }
}

fun findPreviousNonWhitespace(element: PsiElement): PsiElement? {
    var sibling = element.prevSibling
    while (sibling != null && (sibling.node.elementType == TokenType.WHITE_SPACE ||
            sibling.node.elementType == BwslTokenTypes.LINE_COMMENT ||
            sibling.node.elementType == BwslTokenTypes.BLOCK_COMMENT)) {
        sibling = sibling.prevSibling
    }
    return sibling
}
