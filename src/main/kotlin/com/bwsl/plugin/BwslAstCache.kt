package com.bwsl.plugin

import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val type: String)
data class AstStatement(
    val type: String = "",
    val name: String = "",
    val declaredType: String = "",
    val line: Int = 0,
    val column: Int = 0,
    val body: AstBlock? = null
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
data class AstPass(
    val name: String = "",
    val functions: List<AstFunction> = emptyList(),
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
data class AstRoot(val modules: List<AstModule> = emptyList(), val root: AstRootNode? = null) {
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
