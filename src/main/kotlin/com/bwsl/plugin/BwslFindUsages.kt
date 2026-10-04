package com.bwsl.plugin

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.tree.TokenSet
import com.intellij.util.Processor

/**
 * Find Usages for declarations: functions, methods, structs, fields, parameters, variables,
 * constants, attributes, modules and stage values. See [findUsagesOf] for where the usages come from.
 */
class BwslFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        BwslLexerAdapter(),
        TokenSet.create(
            BwslTokenTypes.IDENTIFIER, BwslTokenTypes.FUNCTION_DECLARATION, BwslTokenTypes.FUNCTION_CALL,
            BwslTokenTypes.INTRINSIC_CALL, BwslTokenTypes.MODULE_NAME, BwslTokenTypes.MODULE_QUALIFIER
        ),
        TokenSet.create(BwslTokenTypes.LINE_COMMENT, BwslTokenTypes.BLOCK_COMMENT),
        TokenSet.create(BwslTokenTypes.STRING_LIT)
    )

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean = findDeclarationIdentityOf(psiElement) != null

    override fun getHelpId(psiElement: PsiElement): String? = null

    override fun getType(element: PsiElement): String =
        findDeclarationIdentityOf(element)?.symbol?.kind?.replace('-', ' ') ?: ""

    override fun getDescriptiveName(element: PsiElement): String =
        findDeclarationIdentityOf(element)?.symbol?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/**
 * Makes a caret on a declaration's own name a Find Usages target. BWSL's PSI has no named
 * elements, so the platform can't recognise a declaration name by itself; the compiler's AST can.
 */
class BwslTargetElementEvaluator : TargetElementEvaluatorEx2() {

    override fun getNamedElement(element: PsiElement): PsiElement? {
        val file = element.containingFile ?: return null
        val index = buildAstIndex(file) ?: return null
        val declaration = index.findNodeAtOffset(element.textOffset)
            ?.takeIf { index.symbolsById.containsKey(it.id) } ?: return null
        return index.findNameRangeOf(declaration)?.let { findElementAtOffset(file, it.first) }
    }
}

/**
 * Supplies the references to a BWSL declaration to the platform's reference search, from the
 * compiler's reference edges. The platform's own search looks for the target's name in a word
 * index, which needs a named element; declaration names here are bare tokens.
 */
class BwslReferencesSearch : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = queryParameters.elementToSearch
        if (target.language != BwslLanguage) return
        for (reference in findUsagesOf(target, queryParameters.effectiveSearchScope)) {
            if (!consumer.process(reference)) return
        }
    }
}
