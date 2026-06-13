package com.bwsl.plugin.references

import com.bwsl.plugin.*

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

/**
 * Finds the leftmost REFERENCE-wrapped identifier matching [name] within [range], skipping
 * member-access/qualified usages (preceded by '.' or '::'). For a declaration range, the
 * declaration itself is the leftmost such occurrence (it precedes all usages).
 */
private fun findIdentifierInRange(file: PsiFile, name: String, range: IntRange): PsiElement? {
    for (leaf in fileLeaves(file)) {
        if (leaf.elementType != BwslTokenTypes.IDENTIFIER) continue
        if (leaf.text != name) continue
        if (leaf.textOffset !in range) continue
        val wrapper = leaf.parent ?: continue
        val prev = previousNonWhitespace(wrapper)?.elementType
        if (prev == BwslTokenTypes.DOT || prev == BwslTokenTypes.COLONCOLON) continue
        return wrapper
    }
    return null
}

/**
 * Resolves a variable usage to its declaration using the bwslc AST to determine the enclosing
 * function, then locating the actual PSI token for the declaration's name. Returns null if no
 * AST is cached for this file, letting callers fall back to a text-based search.
 */
private fun resolveVariableViaAst(file: PsiFile, offset: Int, name: String): PsiElement? {
    val filePath = file.virtualFile?.path ?: return null
    val root = BwslAstCache.getRoot(filePath) ?: return null
    val (line, column) = lineColumnAt(file, offset) ?: return null
    val scope = findScope(root, line, column)
    val fn = findEnclosingFunction(root, scope, line, column) ?: return null

    val decl = collectVariableDecls(fn.body)
        .filter { it.name == name && (it.line < line || (it.line == line && it.column <= column)) }
        .maxWithOrNull(compareBy({ it.line }, { it.column }))
    if (decl != null) {
        lineRange(file, decl.line)?.let { findIdentifierInRange(file, name, it) }?.let { return it }
    }

    if (fn.parameters.any { it.name == name }) {
        functionRange(file, fn)?.let { findIdentifierInRange(file, name, it) }?.let { return it }
    }

    return null
}

class BwslVariableReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val name = element.text
        val offset = element.textOffset

        resolveVariableViaAst(element.containingFile, offset, name)?.let { return it }

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

/**
 * Returns true if [element] is part of a declared type written out in a VARIABLE_DECL, per the
 * AST (e.g. "testStruct" or "LengthMethodTest"/"testStruct" in "LengthMethodTest::testStruct s1;").
 * The AST gives each VARIABLE_DECL's declaredType string along with the (line, column) where that
 * type text starts; [element]'s position must fall within that span.
 */
fun isAstTypeReference(file: PsiFile, offset: Int): Boolean {
    val filePath = file.virtualFile?.path ?: return false
    val root = BwslAstCache.getRoot(filePath) ?: return false
    val (line, column) = lineColumnAt(file, offset) ?: return false
    val scope = findScope(root, line, column)
    val fn = findEnclosingFunction(root, scope, line, column) ?: return false
    return collectVariableDecls(fn.body).any { decl ->
        if (decl.declaredType.isBlank()) return@any false
        val start = offsetAt(file, decl.line, decl.column) ?: return@any false
        val end = start + decl.declaredType.length
        offset in start until end
    }
}

/**
 * Resolves a type-name reference (e.g. "testStruct" or "LengthMethodTest::testStruct" in a
 * variable declaration) to the AST struct declaration it names, using the enclosing AST scope
 * to resolve unqualified names.
 */
class BwslTypeReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val name = element.text
        val file = element.containingFile
        val filePath = file.virtualFile?.path ?: return null
        val root = BwslAstCache.getRoot(filePath) ?: return null
        val (line, column) = lineColumnAt(file, element.textOffset) ?: return null
        val scope = findScope(root, line, column)

        val prev = previousNonWhitespace(element)
        val qualifier = if (prev?.elementType == BwslTokenTypes.COLONCOLON) previousNonWhitespace(prev)?.text else null
        val typeName = if (qualifier != null) "$qualifier::$name" else name

        val struct = resolveStruct(root, scope, typeName) ?: return null
        return findDeclarationElement(file, struct)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Resolves a module-name qualifier (e.g. "LengthMethodTest" in "LengthMethodTest::testStruct" or
 * "LengthMethodTest::test(...)") to that module's declaration. Prefers a module declared in the
 * same file (via the AST); falls back to a cross-file module lookup by filename, like
 * [BwslModuleReference].
 */
class BwslModuleNameReference(element: PsiElement) :
    PsiReferenceBase<PsiElement>(element, com.intellij.openapi.util.TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val name = element.text
        val file = element.containingFile
        val filePath = file.virtualFile?.path
        val root = filePath?.let { BwslAstCache.getRoot(it) }

        root?.modules?.firstOrNull { it.name == name }?.let { module ->
            findModuleNameElement(file, module)?.let { return it }
        }

        return BwslModuleReference(element).resolve()
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
