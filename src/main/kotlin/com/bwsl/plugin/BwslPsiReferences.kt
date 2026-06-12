package com.bwsl.plugin

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.elementType
import java.io.File

private data class ScopeFrame(val kind: IElementType, val name: String)

private fun walkScopes(file: PsiFile, until: Int? = null, onFunctionDeclaration: (PsiElement, List<ScopeFrame>) -> Unit = { _, _ -> }): List<ScopeFrame> {
    val stack = mutableListOf<ScopeFrame>()
    var pendingFrame: IElementType? = null
    var pendingName: String? = null
    var child = file.firstChild
    while (child != null && (until == null || child.textOffset < until)) {
        when (child.elementType) {
            BwslTokenTypes.KW_MODULE, BwslTokenTypes.KW_STRUCT -> {
                pendingFrame = child.elementType
                pendingName = null
            }
            BwslTokenTypes.REFERENCE -> if (pendingFrame != null && pendingName == null) {
                pendingName = child.text
            }
            BwslTokenTypes.LBRACE -> {
                val frame = pendingFrame
                val name = pendingName
                if (frame != null && name != null) stack.add(ScopeFrame(frame, name))
                pendingFrame = null
                pendingName = null
            }
            BwslTokenTypes.RBRACE -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            BwslTokenTypes.FUNCTION_DECLARATION -> onFunctionDeclaration(child, stack.toList())
            else -> {}
        }
        child = child.nextSibling
    }
    return stack.toList()
}

/**
 * Resolves a function call to its declaration(s) using the bwslc AST (modules/structs/passes with
 * line/column ranges), rather than tree-walking the PSI. Returns an empty list if no AST is cached
 * for this file, letting callers fall back to a text-based search.
 */
private fun resolveViaAst(file: PsiFile, callOffset: Int, name: String, qualifier: String?, receiver: String?): List<PsiElement> {
    val filePath = file.virtualFile?.path ?: return emptyList()
    val root = BwslAstCache.getRoot(filePath) ?: return emptyList()
    val (line, column) = lineColumnAt(file, callOffset) ?: return emptyList()
    val scope = findScope(root, line, column)

    val candidates: List<AstFunction> = if (receiver != null) {
        val enclosingFn = findEnclosingFunction(root, scope, line, column) ?: return emptyList()
        val declaredType = findVariableType(enclosingFn, receiver, line, column) ?: return emptyList()
        val struct = resolveStruct(root, scope, declaredType) ?: return emptyList()
        struct.methods.filter { it.name == name }
    } else if (qualifier != null) {
        root.modules.firstOrNull { it.name == qualifier }?.functions?.filter { it.name == name } ?: emptyList()
    } else {
        functionsInScope(root, scope).filter { it.name == name }
    }

    return candidates.mapNotNull { findDeclarationElement(file, it) }
}

private fun fileLeaves(file: PsiFile): Sequence<PsiElement> = sequence {
    fun visit(element: PsiElement): Sequence<PsiElement> = sequence {
        if (element.firstChild == null) {
            yield(element)
        } else {
            var child = element.firstChild
            while (child != null) {
                yieldAll(visit(child))
                child = child.nextSibling
            }
        }
    }
    yieldAll(visit(file))
}

class BwslFunctionReference(element: PsiElement) :
    PsiPolyVariantReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val name = element.text
        val file = element.containingFile

        val refOrCallExprEarly = if (element.parent?.elementType == BwslTokenTypes.CALL_EXPRESSION) element.parent else element
        val prevEarly = previousNonWhitespace(refOrCallExprEarly)
        val qualifierEarly = if (prevEarly?.elementType == BwslTokenTypes.COLONCOLON) previousNonWhitespace(prevEarly)?.text else null
        val receiverEarly = if (prevEarly?.elementType == BwslTokenTypes.DOT) previousNonWhitespace(prevEarly)?.text else null

        val astResults = resolveViaAst(file, element.textOffset, name, qualifierEarly, receiverEarly)
        if (astResults.isNotEmpty()) {
            return astResults.map { PsiElementResolveResult(it) as ResolveResult }.toTypedArray()
        }

        val matches = mutableListOf<Pair<PsiElement, List<ScopeFrame>>>()
        val callerContext = walkScopes(file, element.textOffset) { decl, context ->
            if (decl.text == name) matches.add(decl to context)
        }

        val qualifier = qualifierEarly

        val selected = if (qualifier != null) {
            matches.filter { (_, context) -> context.size == 1 && context[0].name == qualifier }
        } else {
            var m = matches.filter { (_, context) -> context == callerContext }
            if (m.isEmpty() && callerContext.isNotEmpty()) {
                val moduleContext = listOf(callerContext[0])
                m = matches.filter { (_, context) -> context == moduleContext }
            }
            if (m.isEmpty()) m = matches
            m
        }

        return selected.map { (decl, _) -> PsiElementResolveResult(decl) as ResolveResult }.toTypedArray()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

class BwslVariableReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val name = element.text
        val offset = element.textOffset
        var best: PsiElement? = null
        for (leaf in fileLeaves(element.containingFile)) {
            if (leaf.elementType != BwslTokenTypes.IDENTIFIER) continue
            if (leaf.text != name) continue
            if (leaf.textOffset >= offset) continue
            val wrapper = leaf.parent ?: continue
            val prev = previousNonWhitespace(wrapper)?.elementType ?: continue
            if (!BWSL_TYPE_KEYWORDS.contains(prev)) continue
            if (best == null || wrapper.textOffset > best.textOffset) {
                best = wrapper
            }
        }
        return best
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

class BwslModuleReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val moduleName = element.text
        val project = element.project
        val fileName = "$moduleName.bwsl"

        val candidates = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.allScope(project))
        candidates.firstOrNull()?.let { return PsiManager.getInstance(project).findFile(it) }

        for (modulePath in BwslSettings.getInstance().modulePaths) {
            val candidate = File(modulePath, fileName)
            if (candidate.isFile) {
                val vFile = LocalFileSystem.getInstance().findFileByIoFile(candidate) ?: continue
                return PsiManager.getInstance(project).findFile(vFile)
            }
        }

        val currentDir = element.containingFile.virtualFile?.parent
        val sibling = currentDir?.findChild(fileName)
        if (sibling != null) {
            return PsiManager.getInstance(project).findFile(sibling)
        }

        return null
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
