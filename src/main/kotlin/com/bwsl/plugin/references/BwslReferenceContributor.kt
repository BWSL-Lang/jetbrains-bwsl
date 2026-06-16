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
import com.intellij.psi.tree.TokenSet

val BWSL_TYPE_KEYWORDS: TokenSet = TokenSet.create(
    BwslTokenTypes.KW_BOOL, BwslTokenTypes.KW_INT, BwslTokenTypes.KW_UINT,
    BwslTokenTypes.KW_FLOAT, BwslTokenTypes.KW_DOUBLE,
    BwslTokenTypes.KW_INT64, BwslTokenTypes.KW_UINT64,
    BwslTokenTypes.KW_INT2, BwslTokenTypes.KW_INT3, BwslTokenTypes.KW_INT4,
    BwslTokenTypes.KW_UINT2, BwslTokenTypes.KW_UINT3, BwslTokenTypes.KW_UINT4,
    BwslTokenTypes.KW_FLOAT2, BwslTokenTypes.KW_FLOAT3, BwslTokenTypes.KW_FLOAT4,
    BwslTokenTypes.KW_DOUBLE2, BwslTokenTypes.KW_DOUBLE3, BwslTokenTypes.KW_DOUBLE4,
    BwslTokenTypes.KW_INT64X2, BwslTokenTypes.KW_INT64X3, BwslTokenTypes.KW_INT64X4,
    BwslTokenTypes.KW_UINT64X2, BwslTokenTypes.KW_UINT64X3, BwslTokenTypes.KW_UINT64X4,
    BwslTokenTypes.KW_MAT2, BwslTokenTypes.KW_MAT3, BwslTokenTypes.KW_MAT4,
    BwslTokenTypes.KW_DMAT2, BwslTokenTypes.KW_DMAT3, BwslTokenTypes.KW_DMAT4,
    BwslTokenTypes.KW_SAMPLER, BwslTokenTypes.KW_TEXTURE2D, BwslTokenTypes.KW_TEXTURE3D,
    BwslTokenTypes.KW_TEXTURECUBE, BwslTokenTypes.KW_TEXTURE2DARRAY,
    BwslTokenTypes.KW_IMAGE2D, BwslTokenTypes.KW_BUFFER, BwslTokenTypes.KW_CBUFFER,
    BwslTokenTypes.KW_VOID
)

class BwslReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withElementType(BwslTokenTypes.REFERENCE),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
                    val innerType = element.firstChild?.node?.elementType ?: return emptyArray()
                    if (innerType == BwslTokenTypes.FUNCTION_CALL) {
                        return arrayOf(BwslFunctionReference(element))
                    }
                    if (innerType == BwslTokenTypes.INTRINSIC_CALL) {
                        return emptyArray()
                    }
                    if (innerType == BwslTokenTypes.MODULE_QUALIFIER) {
                        return arrayOf(BwslModuleNameReference(element))
                    }
                    if (innerType == BwslTokenTypes.MODULE_NAME) {
                        // The declaration site of a module ("module <Name> { ... }") has no reference.
                        return emptyArray()
                    }
                    val prev = previousNonWhitespace(element)?.node?.elementType
                    val next = nextNonWhitespace(element)?.node?.elementType
                    return when {
                        prev == BwslTokenTypes.DOT && previousNonWhitespace(previousNonWhitespace(element)!!)?.text == "input" ->
                            arrayOf(BwslShaderInputReference(element))
                        prev == BwslTokenTypes.KW_IMPORT || prev == BwslTokenTypes.KW_USING ->
                            arrayOf(BwslModuleReference(element))
                        prev != null && BWSL_TYPE_KEYWORDS.contains(prev) -> emptyArray()
                        isInUseAttributesBlock(element) -> arrayOf(BwslAttributeNameReference(element))
                        isAstTypeReference(element.containingFile, element.textOffset) ->
                            arrayOf(BwslTypeReference(element))
                        BwslAstCache.getRoot(element.containingFile.virtualFile?.path ?: "") == null && next == BwslTokenTypes.REFERENCE ->
                            arrayOf(BwslTypeReference(element))
                        else -> arrayOf(BwslVariableReference(element))
                    }
                }
            }
        )
    }
}

/**
 * Returns true when [element] is an identifier inside `use attributes { ... }`.
 * Walks backwards through siblings to find the opening LBRACE, then checks that
 * the two preceding non-whitespace tokens are KW_ATTRIBUTES and KW_USE.
 */
private fun isInUseAttributesBlock(element: PsiElement): Boolean {
    var sib = element.prevSibling
    while (sib != null) {
        when (sib.node.elementType) {
            BwslTokenTypes.LBRACE -> {
                val beforeBrace = previousNonWhitespace(sib) ?: return false
                if (beforeBrace.node.elementType != BwslTokenTypes.KW_ATTRIBUTES) return false
                val beforeAttr = previousNonWhitespace(beforeBrace) ?: return false
                return beforeAttr.node.elementType == BwslTokenTypes.KW_USE
            }
            BwslTokenTypes.RBRACE -> return false
        }
        sib = sib.prevSibling
    }
    return false
}

fun previousNonWhitespace(element: PsiElement): PsiElement? {
    var sibling = element.prevSibling
    while (sibling != null && (sibling.node.elementType == TokenType.WHITE_SPACE ||
            sibling.node.elementType == BwslTokenTypes.LINE_COMMENT ||
            sibling.node.elementType == BwslTokenTypes.BLOCK_COMMENT)) {
        sibling = sibling.prevSibling
    }
    return sibling
}

fun nextNonWhitespace(element: PsiElement): PsiElement? {
    var sibling = element.nextSibling
    while (sibling != null && (sibling.node.elementType == TokenType.WHITE_SPACE ||
            sibling.node.elementType == BwslTokenTypes.LINE_COMMENT ||
            sibling.node.elementType == BwslTokenTypes.BLOCK_COMMENT)) {
        sibling = sibling.nextSibling
    }
    return sibling
}
