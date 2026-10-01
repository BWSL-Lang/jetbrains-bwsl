package com.bwsl.plugin
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

data class AstScope(val module: AstModule?, val struct: AstStruct?, val pass: AstPass?, val pipeline: AstPipeline? = null)

/** Converts a zero-based document offset to a 1-based (line, column) pair, matching bwslc's AST positions. */
fun lineColumnAt(file: PsiFile, offset: Int): Pair<Int, Int>? {
    val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    if (offset < 0 || offset > doc.textLength) return null
    val line = doc.getLineNumber(offset)
    val column = offset - doc.getLineStartOffset(line)
    return (line + 1) to (column + 1)
}

fun astContains(line: Int, column: Int, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean =
    contains(line, column, startLine, startColumn, endLine, endColumn)

private fun contains(line: Int, column: Int, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): Boolean {
    if (line < startLine || line > endLine) return false
    if (line == startLine && column < startColumn) return false
    if (line == endLine && column > endColumn) return false
    return true
}

/**
 * Finds the module/struct/pass that contains the given source position, based on the AST's
 * line/column ranges. Drives this from [AstRoot.roots] (the complete, ordered list of top-level
 * declaration ids) rather than blindly iterating [AstRoot.modules]/[AstRoot.pipelines] wholesale,
 * so a top-level kind not modeled here is simply skipped (id not "MODULE:"/"PIPELINE:").
 */
fun findScope(root: AstRoot, line: Int, column: Int): AstScope {
    for (rootId in root.roots) {
        when {
            rootId.startsWith("MODULE:") -> {
                val module = root.modules.firstOrNull { it.id == rootId } ?: continue
                if (contains(line, column, module.line, module.column, module.endLine, module.endColumn)) {
                    val struct = module.structs.firstOrNull { contains(line, column, it.line, it.column, it.endLine, it.endColumn) }
                    return AstScope(module, struct, null)
                }
            }
            rootId.startsWith("PIPELINE:") -> {
                val pipeline = root.pipelines.firstOrNull { it.id == rootId } ?: continue
                if (contains(line, column, pipeline.line, pipeline.column, pipeline.endLine, pipeline.endColumn)) {
                    val pass = pipeline.passes.firstOrNull { contains(line, column, it.line, it.column, it.endLine, it.endColumn) }
                    if (pass != null) return AstScope(null, null, pass, pipeline)
                    val struct = pipeline.structs.firstOrNull { contains(line, column, it.line, it.column, it.endLine, it.endColumn) }
                    if (struct != null) return AstScope(null, struct, null, pipeline)
                }
            }
        }
    }
    return AstScope(null, null, null)
}

/** Recursively collects all VARIABLE_DECL statements within a block, including nested blocks (if/for/etc). */
fun collectVariableDecls(block: AstBlock?): List<AstStatement> {
    if (block == null) return emptyList()
    return block.statements.flatMap { stmt ->
        val self = if (stmt.type == "VARIABLE_DECL") listOf(stmt) else emptyList()
        self + collectVariableDecls(stmt.body)
    }
}

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
    for (module in root.ownModules()) {
        if (!astContains(line, column, module.line, module.column, module.endLine, module.endColumn)) continue
        val struct = module.structs.firstOrNull { astContains(line, column, it.line, it.column, it.endLine, it.endColumn) }
        if (struct != null) return structContext(struct, line, column)
        val fn = module.functions.firstOrNull { astContainsRange(line, column, it) }
        return if (fn != null) BwslBlockContext.STATEMENT_BODY else BwslBlockContext.MODULE_BODY
    }
    for (pipeline in root.ownPipelines()) {
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
    return BwslBlockContext.TOP_LEVEL
}

/** Recursively collects ASSIGNMENT statements within a block, including nested blocks (if/for/etc). */
fun collectAssignments(block: AstBlock?): List<AstStatement> {
    if (block == null) return emptyList()
    return block.statements.flatMap { stmt ->
        val self = if (stmt.type == "ASSIGNMENT") listOf(stmt) else emptyList()
        self + collectAssignments(stmt.body)
    }
}

/** A vertex output attribute with its deduced type and interpolation qualifier. */
data class VertexOutput(
    val member: String,
    val assignment: AstStatement,
    /** Deduced from the RHS expression — null when the type cannot be determined statically. */
    val type: String?,
    /** Raw interpolation qualifier from the AST: "DEFAULT", "FLAT", "NO_PERSPECTIVE". */
    val interpolation: String
)

/**
 * Deduces the BWSL type of an expression using the following rules (applied recursively):
 * 1. FUNCTION_CALL → the function/constructor name (e.g. `float4(...)` → `"float4"`)
 * 2. IDENTIFIER → look up the nearest preceding VARIABLE_DECL in [block]
 * 3. BINARY_OP / UNARY_OP → recurse on the left/first operand
 * 4. LITERAL → the literal's type (INT, FLOAT, BOOL lowercased)
 * Returns null when the type cannot be determined.
 */
fun deduceExprType(expr: AstExpr, block: AstBlock? = null, attributes: List<AstAttributeDecl> = emptyList()): String? = when (expr.type) {
    "FUNCTION_CALL" -> expr.name.takeIf { it.isNotBlank() }
    "IDENTIFIER"    -> block?.let { blk ->
        collectVariableDecls(blk).lastOrNull { it.name == expr.name }?.declaredType?.takeIf { it.isNotBlank() }
    }
    "MEMBER_ACCESS" -> when (expr.objectExpr?.identifierKind) {
        "ATTRIBUTES" -> attributes.firstOrNull { it.name == expr.member }?.dataType
        else         -> null
    }
    "BINARY_OP"     -> expr.left?.let { deduceExprType(it, block, attributes) }
    "UNARY_OP"      -> expr.operand?.let { deduceExprType(it, block, attributes) }
    "LITERAL"       -> expr.literal?.literalType?.lowercase()?.takeIf { it.isNotBlank() }
    else            -> null
}

/**
 * Output attributes written by a pass's vertex stage (`output.<member> = ...`), keyed by member
 * name. Each entry captures the first assignment, its interpolation qualifier (from the AST), and
 * the statically deduced type of the assigned value. bwslc's AST has no separate "output
 * declaration" node — outputs are plain ASSIGNMENT statements whose target is a MEMBER_ACCESS on
 * the built-in `output` identifier (identifierKind == "OUTPUT").
 */
fun vertexOutputAssignments(pass: AstPass, attributes: List<AstAttributeDecl> = emptyList()): Map<String, VertexOutput> {
    val block = pass.vertexShader?.body
    return collectAssignments(block)
        .filter { it.target?.type == "MEMBER_ACCESS" && it.target.objectExpr?.identifierKind == "OUTPUT" }
        .groupBy { it.target!!.member }
        .mapValues { (member, stmts) ->
            val stmt = stmts.minWith(compareBy({ it.line }, { it.column }))
            VertexOutput(
                member        = member,
                assignment    = stmt,
                type          = stmt.value?.let { deduceExprType(it, block, attributes) },
                interpolation = stmt.interpolation
            )
        }
}

/**
 * Returns the [AstAttributeDecl] entries from [pipeline] that are listed in the pass's
 * `usedAttributes` list, preserving the declaration order from the pipeline's attributes block.
 */
fun passUsedAttributes(pass: AstPass, pipeline: AstPipeline): List<AstAttributeDecl> {
    if (pass.usedAttributes.isEmpty()) return emptyList()
    val byName = pipeline.attributes.associateBy { it.name }
    return pass.usedAttributes.mapNotNull { byName[it.name] }
}
