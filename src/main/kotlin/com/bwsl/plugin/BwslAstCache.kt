package com.bwsl.plugin

import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val type: String)
data class AstFunction(val name: String, val parameters: List<AstParam>, val returnType: String)
data class AstStruct(val name: String, val methods: List<AstFunction> = emptyList())
data class AstModule(val functions: List<AstFunction> = emptyList(), val structs: List<AstStruct> = emptyList())
data class AstPass(val functions: List<AstFunction> = emptyList())
data class AstRootNode(
    val functions: List<AstFunction> = emptyList(),
    val structs: List<AstStruct> = emptyList(),
    val passes: List<AstPass> = emptyList()
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

    fun update(filePath: String, functions: List<AstFunction>) {
        cache[filePath] = functions.associate { fn ->
            fn.name to BwslFunctionSignature(
                fn.name,
                fn.parameters.map { "${it.type} ${it.name}" },
                fn.returnType.lowercase()
            )
        }
    }

    fun getSignatures(filePath: String): Map<String, BwslFunctionSignature> =
        cache[filePath] ?: emptyMap()

    fun keys(): Set<String> = cache.keys
}
