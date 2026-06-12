package com.bwsl.plugin

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

data class AstScope(val module: AstModule?, val struct: AstStruct?, val pass: AstPass?)

/** Converts a zero-based document offset to a 1-based (line, column) pair, matching bwslc's AST positions. */
fun lineColumnAt(file: PsiFile, offset: Int): Pair<Int, Int>? {
    val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    if (offset < 0 || offset > doc.textLength) return null
    val line = doc.getLineNumber(offset)
    val column = offset - doc.getLineStartOffset(line)
    return (line + 1) to (column + 1)
}

/** Converts a 1-based (line, column) AST position to a zero-based document offset. */
fun offsetAt(file: PsiFile, line: Int, column: Int): Int? {
    val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    if (line < 1 || line > doc.lineCount) return null
    return doc.getLineStartOffset(line - 1) + (column - 1)
}

private fun contains(line: Int, column: Int, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean {
    if (line < startLine || line > endLine) return false
    if (line == startLine && column < startColumn) return false
    if (line == endLine && column > endColumn) return false
    return true
}

/** Finds the module/struct/pass that contains the given source position, based on the AST's line/column ranges. */
fun findScope(root: AstRoot, line: Int, column: Int): AstScope {
    for (module in root.modules) {
        if (contains(line, column, module.line, module.column, module.endLine, module.endColumn)) {
            val struct = module.structs.firstOrNull { contains(line, column, it.line, it.column, it.endLine, it.endColumn) }
            return AstScope(module, struct, null)
        }
    }
    root.root?.let { rootNode ->
        for (pass in rootNode.passes) {
            if (contains(line, column, pass.line, pass.column, pass.endLine, pass.endColumn)) {
                return AstScope(null, null, pass)
            }
        }
        val struct = rootNode.structs.firstOrNull { contains(line, column, it.line, it.column, it.endLine, it.endColumn) }
        if (struct != null || contains(line, column, rootNode.line, rootNode.column, rootNode.endLine, rootNode.endColumn)) {
            return AstScope(null, struct, null)
        }
    }
    return AstScope(null, null, null)
}

/** Functions callable without qualification from within the given scope. */
fun functionsInScope(root: AstRoot, scope: AstScope): List<AstFunction> {
    scope.struct?.let { return it.methods }
    scope.module?.let { return it.functions }
    scope.pass?.let { return it.functions }
    return root.root?.functions ?: emptyList()
}

/** Resolves the PSI element at the position where an AST function/struct member is declared. */
fun findDeclarationElement(file: PsiFile, fn: AstFunction): PsiElement? {
    val offset = offsetAt(file, fn.line, fn.column) ?: return null
    return file.findElementAt(offset)
}
