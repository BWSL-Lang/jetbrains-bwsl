package com.bwsl.plugin
import com.bwsl.plugin.references.nextNonWhitespace
import com.bwsl.plugin.references.previousNonWhitespace

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

/** Coarse classification of "what kind of block surrounds this position", used to decide which
 *  block-structure keywords and intrinsics are valid completions. */
enum class BwslBlockContext {
    /** Not inside any module/pipeline/struct/function — `module`/`pipeline` declarations allowed. */
    TOP_LEVEL,
    /** Directly inside a `module { ... }` (or the implicit file-root scope) — function/struct declarations. */
    MODULE_BODY,
    /** Directly inside a `pipeline { ... }` — `attributes`/`resources`/`variants`/`pass` allowed. */
    PIPELINE_BODY,
    /** Directly inside a `pass { ... }` — `vertex`/`fragment`/`compute` stage blocks allowed. */
    PASS_BODY,
    /** Inside a `struct { ... }` but not inside one of its methods. */
    STRUCT_BODY,
    /** Inside a pipeline's `attributes { ... }` block — `name: type` declarations, type keywords valid. */
    ATTRIBUTES_BODY,
    /** Inside a pipeline's `resources { ... }` block — `name: type` declarations, type keywords valid. */
    RESOURCES_BODY,
    /** Inside a pipeline's `variants { ... }` block — `name: type = expr` declarations, type keywords valid. */
    VARIANTS_BODY,
    /** Inside a function/method body or a shader stage body — statements and intrinsics are valid. */
    STATEMENT_BODY
}

private fun astContainsRange(line: Int, column: Int, r: AstStage) =
    astContains(line, column, r.line, r.column, r.endLine, r.endColumn)

private fun astContainsRange(line: Int, column: Int, fn: AstFunction) =
    astContains(line, column, fn.line, fn.column, fn.endLine, fn.endColumn)

private fun structContext(struct: AstStruct, line: Int, column: Int): BwslBlockContext {
    val fn = struct.methods.firstOrNull { astContainsRange(line, column, it) }
    return if (fn != null) BwslBlockContext.STATEMENT_BODY else BwslBlockContext.STRUCT_BODY
}

private fun passContext(pass: AstPass, line: Int, column: Int): BwslBlockContext {
    val stages = listOfNotNull(pass.vertexShader, pass.fragmentShader, pass.computeShader)
    if (stages.any { astContainsRange(line, column, it) }) return BwslBlockContext.STATEMENT_BODY
    if (pass.functions.any { astContainsRange(line, column, it) }) return BwslBlockContext.STATEMENT_BODY
    return BwslBlockContext.PASS_BODY
}

/**
 * Finds the (1-based, inclusive) line range of the `keyword { ... }` block in [text], by locating
 * `keyword {` and brace-matching to its closing `}`. bwslc's AST gives only point locations for
 * individual `attributes`/`resources`/`variants` declarations (no range for the enclosing block),
 * so this brace-matching is needed to correctly classify positions where no declaration exists yet
 * (e.g. an empty line while typing a new declaration, or a still-empty block).
 */
private fun blockLineRange(text: String, keyword: String): IntRange? {
    val match = Regex("\\b$keyword\\s*\\{").find(text) ?: return null
    val startLine = text.substring(0, match.range.first).count { it == '\n' } + 1
    var depth = 0
    for (i in match.range.last until text.length) {
        when (text[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return startLine..(text.substring(0, i).count { it == '\n' } + 1)
            }
        }
    }
    return null
}

/** Determines which kind of block surrounds the given (1-based) source position, based on AST ranges. */
fun blockContextAt(root: AstRoot, line: Int, column: Int, text: String = ""): BwslBlockContext {
    for (module in root.modules) {
        if (!astContains(line, column, module.line, module.column, module.endLine, module.endColumn)) continue
        val struct = module.structs.firstOrNull { astContains(line, column, it.line, it.column, it.endLine, it.endColumn) }
        if (struct != null) return structContext(struct, line, column)
        val fn = module.functions.firstOrNull { astContainsRange(line, column, it) }
        return if (fn != null) BwslBlockContext.STATEMENT_BODY else BwslBlockContext.MODULE_BODY
    }
    for (pipeline in root.pipelines) {
        if (!astContains(line, column, pipeline.line, pipeline.column, pipeline.endLine, pipeline.endColumn)) continue
        val pass = pipeline.passes.firstOrNull { astContains(line, column, it.line, it.column, it.endLine, it.endColumn) }
        if (pass != null) return passContext(pass, line, column)

        // bwslc's AST gives only point locations for individual attributes/resources/variants
        // declarations, not a range for the enclosing block, so brace-matching on the source text
        // is used to find each block's extent (see blockLineRange).
        val sections = listOfNotNull(
            if (pipeline.attributes.isNotEmpty()) blockLineRange(text, "attributes")?.let { it to BwslBlockContext.ATTRIBUTES_BODY } else null,
            if (pipeline.resources.isNotEmpty()) blockLineRange(text, "resources")?.let { it to BwslBlockContext.RESOURCES_BODY } else null,
            if (pipeline.variantDecls.isNotEmpty()) blockLineRange(text, "variants")?.let { it to BwslBlockContext.VARIANTS_BODY } else null
        )

        return sections.firstOrNull { line in it.first }?.second ?: BwslBlockContext.PIPELINE_BODY
    }
    root.root?.let { r ->
        if (astContains(line, column, r.line, r.column, r.endLine, r.endColumn)) {
            val struct = r.structs.firstOrNull { astContains(line, column, it.line, it.column, it.endLine, it.endColumn) }
            if (struct != null) return structContext(struct, line, column)
            val pass = r.passes.firstOrNull { astContains(line, column, it.line, it.column, it.endLine, it.endColumn) }
            if (pass != null) return passContext(pass, line, column)
            val fn = r.functions.firstOrNull { astContainsRange(line, column, it) }
            return if (fn != null) BwslBlockContext.STATEMENT_BODY else BwslBlockContext.MODULE_BODY
        }
    }
    return BwslBlockContext.TOP_LEVEL
}

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
