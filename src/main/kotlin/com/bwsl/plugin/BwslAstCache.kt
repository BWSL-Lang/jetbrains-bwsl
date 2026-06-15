package com.bwsl.plugin

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val type: String)

/** A (partial) expression node — used to detect `output.<member>`/`input.<member>` assignment targets. */
data class AstExpr(
    val type: String = "",
    val name: String = "",
    val member: String = "",
    val identifierKind: String = "",
    val line: Int = 0,
    val column: Int = 0,
    @SerializedName("object") val objectExpr: AstExpr? = null
)

data class AstStatement(
    val type: String = "",
    val name: String = "",
    val declaredType: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val body: AstBlock? = null,
    val target: AstExpr? = null
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
    val body: AstBlock? = null
)
data class AstStruct(
    val name: String = "",
    val methods: List<AstFunction> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
data class AstModule(
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
data class AstStage(
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val body: AstBlock? = null
)
data class AstPass(
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0,
    val vertexShader: AstStage? = null,
    val fragmentShader: AstStage? = null,
    val computeShader: AstStage? = null
)
data class AstAttributeDecl(
    val name: String = "",
    val dataType: String = "",
    val line: Int = 0,
    val column: Int = 0
)
data class AstResourceDecl(
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
    val name: String = "",
    val typeName: String = "",
    val defaultExpr: AstExprPos? = null
)
data class AstPipeline(
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
data class AstRootNode(
    val functions: List<AstFunction> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val passes: List<AstPass> = emptyList(),
    val line: Int = 0,
    val column: Int = 0,
    val endLine: Int = 0,
    val endColumn: Int = 0
)
data class AstRoot(
    val modules: List<AstModule> = emptyList(),
    val pipelines: List<AstPipeline> = emptyList(),
    val root: AstRootNode? = null
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

    fun update(filePath: String, functions: List<AstFunction>) {
        cache[filePath] = signaturesOf(functions)
    }

    fun update(filePath: String, root: AstRoot) {
        roots[filePath] = root
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

    fun keys(): Set<String> = cache.keys
}
