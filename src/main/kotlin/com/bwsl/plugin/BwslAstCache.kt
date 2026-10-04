package com.bwsl.plugin

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val dataType: String)

data class AstLiteralVal(val literalType: String = "")

/** A (partial) expression node — used to detect `output.<member>`/`input.<member>` targets and deduce value types. */
data class AstExpr(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val member: String = "",
    val identifierKind: String = "",
    val line: Int = 0,
    val column: Int = 0,
    @SerializedName("object") val objectExpr: AstExpr? = null,
    // FUNCTION_CALL
    val arguments: List<AstExpr> = emptyList(),
    // BINARY_OP
    val left: AstExpr? = null,
    val right: AstExpr? = null,
    val op: String = "",
    // UNARY_OP
    val operand: AstExpr? = null,
    // LITERAL
    val literal: AstLiteralVal? = null
)

data class AstStatement(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val declaredType: String = "",
    val line: Int = 0,
    val column: Int = 0,
    /** Start of the declared-type text (VARIABLE_DECL only). */
    val typeLine: Int = 0,
    val typeColumn: Int = 0,
    /** Start of the variable-name text (VARIABLE_DECL only). */
    val nameLine: Int = 0,
    val nameColumn: Int = 0,
    val body: AstBlock? = null,
    val target: AstExpr? = null,
    val value: AstExpr? = null,
    val interpolation: String = ""
)
data class AstBlock(val statements: List<AstStatement> = emptyList())
data class AstFunction(
    val name: String,
    val parameters: List<AstParam>,
    val returnType: String,
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val body: AstBlock? = null,
    val id: String = ""
)
data class AstStruct(
    val id: String = "",
    val name: String = "",
    val methods: List<AstFunction> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
data class AstModule(
    val id: String = "",
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
data class AstStage(
    val id: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val body: AstBlock? = null
)
data class AstUsedAttribute(val name: String = "")
data class AstPass(
    val id: String = "",
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val vertexShader: AstStage? = null,
    val fragmentShader: AstStage? = null,
    val computeShader: AstStage? = null,
    val usedAttributes: List<AstUsedAttribute> = emptyList()
)
data class AstAttributeDecl(
    val id: String = "",
    val name: String = "",
    val dataType: String = "",
    val line: Int = 0,
    val column: Int = 0
)
data class AstResourceDecl(
    val id: String = "",
    val name: String = "",
    val typeName: String = "",
    val line: Int = 0,
    val column: Int = 0
)
data class AstExprPos(
    val line: Int = 0,
    val column: Int = 0
)
data class AstVariantDecl(
    val id: String = "",
    val name: String = "",
    val typeName: String = "",
    val defaultExpr: AstExprPos? = null
)
data class AstPipeline(
    val id: String = "",
    val name: String = "",
    val passes: List<AstPass> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val attributes: List<AstAttributeDecl> = emptyList(),
    val resources: List<AstResourceDecl> = emptyList(),
    val variantDecls: List<AstVariantDecl> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)

/** An entry in [AstReferenceIndex.symbols] - a declared or synthetic (compiler-builtin) symbol. */
data class AstSymbol(
    val id: String = "",
    val kind: String = "",
    val name: String = "",
    val declaration: String = "",
    val owner: String = "",
    val type: String = "",
    val stableId: String = "",
    /** Node ids that write/define this symbol (e.g. ASSIGNMENT ids for a stage-interface symbol). */
    val definitions: List<String> = emptyList()
)

/** An edge in [AstReferenceIndex.references], e.g. a `call`/`read`/`write`/`type`/`output`/`input` use. */
data class AstReference(
    val from: String = "",
    val to: String = "",
    val role: String = ""
)

/** bwslc's own resolved symbol/reference graph for the file (schema `bwsl.references.v1`). */
data class AstReferenceIndex(
    val format: String = "",
    val unit: String = "",
    val symbols: List<AstSymbol> = emptyList(),
    val references: List<AstReference> = emptyList()
)

data class AstRoot(
    val schema: String = "",
    val modules: List<AstModule> = emptyList(),
    val pipelines: List<AstPipeline> = emptyList(),
    /** The id of every top-level declaration (module or pipeline) the compiled file itself
     *  declares, in declaration order. Each id is present in [modules]/[pipelines], which also hold
     *  every imported module - look it up there by id. */
    val roots: List<String> = emptyList(),
    val referenceIndex: AstReferenceIndex? = null
) {
    /**
     * [modules] also contains every imported module (bwslc is given `-modules` paths), whose
     * line/column are relative to *their own* file. Anything measured against the compiled file's
     * text must go through this (or [collectOwnPipelines]) instead of [modules] directly.
     */
    fun collectOwnModules(): List<AstModule> {
        val ids = roots.toSet()
        return modules.filter { it.id in ids }
    }

    fun collectOwnPipelines(): List<AstPipeline> {
        val ids = roots.toSet()
        return pipelines.filter { it.id in ids }
    }
}

object BwslAstCache {
    private val roots = ConcurrentHashMap<String, AstRoot>()
    private val rawRoots = ConcurrentHashMap<String, com.google.gson.JsonObject>()
    private val compiledInputs = ConcurrentHashMap<String, Map<String, Int>>()
    private val uncompilableInputs = ConcurrentHashMap<String, Map<String, Int>>()

    /**
     * [rawJson] is the same bwslc -ast-json payload as [root], parsed generically. It's needed
     * because the typed [AstRoot] model doesn't (and can't practically) mirror every node type -
     * a generic id -> position index built from the raw tree is used instead (see BwslAstIndex).
     *
     * [inputs] are the files the AST was built from - the compiled file and every file it imported
     * from - as [normalizePathKey] -> [hashText] of the text bwslc read, so [findCompiledInputs]
     * can tell whether any of them has changed since.
     */
    fun update(
        filePath: String,
        root: AstRoot,
        rawJson: com.google.gson.JsonObject? = null,
        inputs: Map<String, Int>? = null
    ) {
        roots[filePath] = root
        if (rawJson != null) rawRoots[filePath] = rawJson else rawRoots.remove(filePath)
        if (inputs != null) compiledInputs[filePath] = inputs else compiledInputs.remove(filePath)
        uncompilableInputs.remove(filePath)
    }

    /** Records that bwslc produced no AST for [filePath] when its candidate inputs were [inputs]. */
    fun recordUncompilable(filePath: String, inputs: Map<String, Int>) {
        uncompilableInputs[filePath] = inputs
    }

    /** The inputs the cached AST for [filePath] was built from, or null if none is cached or they are not known. */
    fun findCompiledInputs(filePath: String): Map<String, Int>? = compiledInputs[filePath]

    /** The inputs bwslc last failed to produce an AST from for [filePath], or null if it has not failed since the last AST. */
    fun findUncompilableInputs(filePath: String): Map<String, Int>? = uncompilableInputs[filePath]

    fun hashText(text: String): Int = text.hashCode()

    fun findRoot(filePath: String): AstRoot? = roots[filePath]

    fun findRawRoot(filePath: String): com.google.gson.JsonObject? = rawRoots[filePath]
}
