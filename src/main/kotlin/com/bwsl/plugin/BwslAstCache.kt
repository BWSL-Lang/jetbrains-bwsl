package com.bwsl.plugin

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val type: String)

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
    val attributes: List<AstAttributeDecl> = emptyList(),
    val resources: List<AstResourceDecl> = emptyList(),
    val variantDecls: List<AstVariantDecl> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
/**
 * The file's top-level node when it isn't (only) a list of modules - e.g. a top-level `pipeline`.
 * When the top level is a pipeline, bwslc duplicates it here AND in [AstRoot.pipelines] with a
 * matching [id]; [findScope] must not process both (see the dedup check there).
 */
data class AstRootNode(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val passes: List<AstPass> = emptyList(),
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
    val root: AstRootNode? = null,
    val referenceIndex: AstReferenceIndex? = null
) {
    fun allFunctions(): List<AstFunction> {
        val moduleFunctions = modules.flatMap { it.functions + it.structs.flatMap { s -> s.methods } }
        val rootFunctions = root?.let { r ->
            r.functions + r.structs.flatMap { s -> s.methods } + r.passes.flatMap { p -> p.functions }
        } ?: emptyList()
        return moduleFunctions + rootFunctions
    }
}

object BwslAstCache {
    private val cache = ConcurrentHashMap<String, Map<String, BwslFunctionSignature>>()
    private val roots = ConcurrentHashMap<String, AstRoot>()
    private val rawRoots = ConcurrentHashMap<String, com.google.gson.JsonObject>()

    fun update(filePath: String, functions: List<AstFunction>) {
        cache[filePath] = signaturesOf(functions)
    }

    /**
     * [rawJson] is the same bwslc -ast-json payload as [root], parsed generically. It's needed
     * because the typed [AstRoot] model doesn't (and can't practically) mirror every node type -
     * a generic id -> position index built from the raw tree is used instead (see BwslAstIndex).
     */
    fun update(filePath: String, root: AstRoot, rawJson: com.google.gson.JsonObject? = null) {
        roots[filePath] = root
        if (rawJson != null) rawRoots[filePath] = rawJson else rawRoots.remove(filePath)
        cache[filePath] = signaturesOf(root.allFunctions())
    }

    private fun signaturesOf(functions: List<AstFunction>): Map<String, BwslFunctionSignature> =
        functions.associate { fn ->
            fn.name to BwslFunctionSignature(
                fn.name,
                fn.parameters.map { "${it.type} ${it.name}" },
                fn.returnType.lowercase()
            )
        }

    fun getSignatures(filePath: String): Map<String, BwslFunctionSignature> =
        cache[filePath] ?: emptyMap()

    fun getRoot(filePath: String): AstRoot? = roots[filePath]

    fun getRawRoot(filePath: String): com.google.gson.JsonObject? = rawRoots[filePath]

    fun keys(): Set<String> = cache.keys
}
