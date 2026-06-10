package com.bwsl.plugin

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

private val log = logger<BwslParameterInfoHandler>()

class BwslParameterInfoHandler : ParameterInfoHandler<PsiElement, BwslFunctionSignature> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val callExpr = findEnclosingCallExpression(context.file, context.offset)
        if (callExpr == null) {
            log.warn("findElementForParameterInfo: no enclosing CALL_EXPRESSION at offset ${context.offset}")
            return null
        }
        val nameToken = callExpr.firstChild?.firstChild ?: return null
        val name = nameToken.text
        val tokenType = nameToken.node.elementType
        val signatures = findSignatures(context.file, name, tokenType)
        log.warn("findElementForParameterInfo: name=$name tokenType=$tokenType signatures=${signatures.size} cacheKeys=${BwslAstCache.keys()}")
        if (signatures.isEmpty()) return null
        context.itemsToShow = signatures.toTypedArray()
        return callExpr
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textOffset, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        return findEnclosingCallExpression(context.file, context.offset)
    }

    override fun updateParameterInfo(callExpr: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(countCommasBefore(callExpr, context.offset))
    }

    override fun updateUI(sig: BwslFunctionSignature, context: ParameterInfoUIContext) {
        val current = context.currentParameterIndex
        val fullText = if (sig.params.isEmpty()) "<no parameters>" else sig.params.joinToString(", ")
        var hlStart = -1
        var hlEnd = -1
        if (current in sig.params.indices) {
            var pos = 0
            for (i in 0 until current) pos += sig.params[i].length + 2
            hlStart = pos
            hlEnd = pos + sig.params[current].length
        }
        context.setupUIComponentPresentation(fullText, hlStart, hlEnd, false, false, false, context.defaultParameterColor)
    }

    fun signaturesAt(file: PsiFile, offset: Int): List<BwslFunctionSignature> {
        val callExpr = findEnclosingCallExpression(file, offset) ?: return emptyList()
        val nameToken = callExpr.firstChild?.firstChild ?: return emptyList()
        return findSignatures(file, nameToken.text, nameToken.node.elementType)
    }

    fun findEnclosingCallExpression(file: PsiFile, offset: Int): PsiElement? {
        var element: PsiElement? = file.findElementAt(offset)
            ?: file.findElementAt(maxOf(0, offset - 1))
            ?: return null
        while (element != null && element !is PsiFile) {
            if (element.node.elementType == BwslTokenTypes.CALL_EXPRESSION) return element
            element = element.parent
        }
        return null
    }

    fun findSignatures(file: PsiFile, name: String, tokenType: com.intellij.psi.tree.IElementType): List<BwslFunctionSignature> {
        if (tokenType == BwslTokenTypes.INTRINSIC_CALL) {
            return BwslIntrinsics.ALL.filter { it.name == name }.map { fn ->
                BwslFunctionSignature(fn.name, fn.params.map { "${it.type} ${it.name}" }, fn.returnType)
            }
        }
        val filePath = file.virtualFile?.path ?: return emptyList()
        return listOfNotNull(BwslAstCache.getSignatures(filePath)[name])
    }

    private fun countCommasBefore(callExpr: PsiElement, offset: Int): Int {
        var count = 0
        var foundLParen = false
        var cur = callExpr.firstChild
        while (cur != null && cur.textOffset < offset) {
            val type = cur.node.elementType
            if (!foundLParen && type == BwslTokenTypes.LPAREN) {
                foundLParen = true
            } else if (foundLParen && type == BwslTokenTypes.COMMA) {
                count++
            }
            cur = cur.nextSibling
        }
        return count
    }
}
