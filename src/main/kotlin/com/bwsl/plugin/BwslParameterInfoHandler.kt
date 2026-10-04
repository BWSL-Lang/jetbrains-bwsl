package com.bwsl.plugin
import com.bwsl.plugin.references.findPreviousNonWhitespace

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
        val hasReceiver = findPreviousNonWhitespace(callExpr)?.node?.elementType == BwslTokenTypes.DOT
        val signatures = collectSignaturesFor(nameToken, hasReceiver)
        log.warn("findElementForParameterInfo: name=${nameToken.text} tokenType=${nameToken.node.elementType} signatures=${signatures.size}")
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

    fun collectSignaturesAt(file: PsiFile, offset: Int): List<BwslFunctionSignature> {
        val callExpr = findEnclosingCallExpression(file, offset) ?: return emptyList()
        val nameToken = callExpr.firstChild?.firstChild ?: return emptyList()
        val hasReceiver = findPreviousNonWhitespace(callExpr)?.node?.elementType == BwslTokenTypes.DOT
        return collectSignaturesFor(nameToken, hasReceiver)
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

    /**
     * The signature(s) of the function called at [nameToken]: an intrinsic's from the built-in
     * table, any other call's from the compiler's reference index - the declaration the call
     * resolves to, so same-named functions in different modules or passes can't be mixed up.
     */
    private fun collectSignaturesFor(nameToken: PsiElement, hasReceiver: Boolean): List<BwslFunctionSignature> {
        val name = nameToken.text
        if (nameToken.node.elementType == BwslTokenTypes.INTRINSIC_CALL) {
            if (hasReceiver && name == "length") {
                return listOf(BwslFunctionSignature("length", emptyList(), "int"))
            }
            return BwslIntrinsics.ALL.filter { it.name == name }.map { fn ->
                BwslFunctionSignature(fn.name, fn.params.map { "${it.type} ${it.name}" }, fn.returnType)
            }
        }
        val index = buildAstIndex(nameToken.containingFile) ?: return emptyList()
        return collectDeclarationIdsAt(index, nameToken.textOffset)
            .mapNotNull { index.symbolsById[it] }
            .mapNotNull { buildFunctionSignature(index, it) }
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
