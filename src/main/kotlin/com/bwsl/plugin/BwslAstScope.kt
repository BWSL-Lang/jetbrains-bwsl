package com.bwsl.plugin

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType

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

fun astContains(line: Int, column: Int, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean =
    contains(line, column, startLine, startColumn, endLine, endColumn)

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

/** Resolves the PSI element at the position where an AST struct is declared. */
fun findDeclarationElement(file: PsiFile, struct: AstStruct): PsiElement? {
    val offset = offsetAt(file, struct.line, struct.column) ?: return null
    return file.findElementAt(offset)
}

/** Resolves the PSI element for an AST module's name (the identifier following "module"). */
fun findModuleNameElement(file: PsiFile, module: AstModule): PsiElement? {
    val kwOffset = offsetAt(file, module.line, module.column) ?: return null
    val kw = file.findElementAt(kwOffset) ?: return null
    val next = nextNonWhitespace(kw) ?: return null
    return if (next.elementType == BwslTokenTypes.REFERENCE) next.firstChild else next
}

/** Finds the function (from the given scope) whose body range contains the given position. */
fun findEnclosingFunction(root: AstRoot, scope: AstScope, line: Int, column: Int): AstFunction? =
    functionsInScope(root, scope).firstOrNull {
        astContains(line, column, it.line, it.column, it.endLine, it.endColumn)
    }

/** The document offset range spanned by a function declaration, per the AST. */
fun functionRange(file: PsiFile, fn: AstFunction): IntRange? {
    val start = offsetAt(file, fn.line, fn.column) ?: return null
    val end = offsetAt(file, fn.endLine, fn.endColumn) ?: return null
    return start..end
}

/** The document offset range of a given 1-based line. */
fun lineRange(file: PsiFile, line: Int): IntRange? {
    val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    if (line < 1 || line > doc.lineCount) return null
    return doc.getLineStartOffset(line - 1)..doc.getLineEndOffset(line - 1)
}

/** Recursively collects all VARIABLE_DECL statements within a block, including nested blocks (if/for/etc). */
fun collectVariableDecls(block: AstBlock?): List<AstStatement> {
    if (block == null) return emptyList()
    return block.statements.flatMap { stmt ->
        val self = if (stmt.type == "VARIABLE_DECL") listOf(stmt) else emptyList()
        self + collectVariableDecls(stmt.body)
    }
}

/**
 * Finds the declared type of a local variable visible at the given position within [fn],
 * preferring the declaration closest to (but before) that position.
 */
fun findVariableType(fn: AstFunction, name: String, line: Int, column: Int): String? =
    collectVariableDecls(fn.body)
        .filter { it.name == name && (it.line < line || (it.line == line && it.column <= column)) }
        .maxWithOrNull(compareBy({ it.line }, { it.column }))
        ?.declaredType
        ?.takeIf { it.isNotBlank() }

/** Resolves a (possibly module-qualified, e.g. "Module::Struct") type name to its struct declaration. */
fun resolveStruct(root: AstRoot, scope: AstScope, typeName: String): AstStruct? {
    val parts = typeName.split("::")
    return if (parts.size == 2) {
        root.modules.firstOrNull { it.name == parts[0] }?.structs?.firstOrNull { it.name == parts[1] }
    } else {
        scope.module?.structs?.firstOrNull { it.name == typeName }
            ?: root.root?.structs?.firstOrNull { it.name == typeName }
    }
}
