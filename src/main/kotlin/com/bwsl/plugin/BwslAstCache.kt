package com.bwsl.plugin

import java.util.concurrent.ConcurrentHashMap

data class BwslFunctionSignature(val name: String, val params: List<String>, val returnType: String = "")

data class AstParam(val name: String, val type: String)
data class AstFunction(val name: String, val parameters: List<AstParam>, val returnType: String)
data class AstModule(val functions: List<AstFunction>)
data class AstRoot(val modules: List<AstModule>)

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
