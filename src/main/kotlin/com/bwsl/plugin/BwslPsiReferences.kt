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
import com.intellij.psi.util.elementType
import com.intellij.util.SmartList
import java.io.File

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
        val results = SmartList<ResolveResult>()
        for (leaf in fileLeaves(element.containingFile)) {
            if (leaf.elementType == BwslTokenTypes.FUNCTION_DECLARATION && leaf.text == name) {
                results.add(PsiElementResolveResult(leaf))
            }
        }
        return results.toTypedArray()
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
