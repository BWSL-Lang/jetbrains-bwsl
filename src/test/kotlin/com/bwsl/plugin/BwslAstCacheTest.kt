package com.bwsl.plugin

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File

class BwslAstCacheTest {

    @Test
    fun moduleFunctionIsCachedWithItsParameters() {
        val functions = parseAstFunctions("lexer_test_files/module.ast.json")

        val rotate = functions.firstOrNull { it.name == "rotate" }
        assertNotNull(rotate) { "Expected 'rotate' function in parsed AST" }

        BwslAstCache.update("test.bwsl", functions)
        val sig = BwslAstCache.getSignatures("test.bwsl")["rotate"]
        assertNotNull(sig) { "Expected 'rotate' signature in cache" }
        assertEquals(listOf("float2 pos", "float2 center", "float angle"), sig!!.params)
        assertEquals("float2", sig.returnType)
    }

    @Test
    fun structMethodsAreIncludedAlongsideModuleFunctions() {
        val root = AstRoot(
            modules = listOf(
                AstModule(
                    functions = listOf(
                        AstFunction("freeFn", listOf(AstParam("a", "int")), "VOID")
                    ),
                    structs = listOf(
                        AstStruct(
                            name = "Foo",
                            methods = listOf(
                                AstFunction("transform", listOf(AstParam("p", "float2")), "FLOAT2")
                            )
                        )
                    )
                )
            )
        )

        val functions = root.allFunctions()
        BwslAstCache.update("with-structs.bwsl", functions)

        val signatures = BwslAstCache.getSignatures("with-structs.bwsl")
        assertNotNull(signatures["freeFn"]) { "Expected free function 'freeFn' in cache" }
        assertNotNull(signatures["transform"]) { "Expected struct method 'transform' in cache" }
        assertEquals(listOf("float2 p"), signatures["transform"]!!.params)
    }

    @Test
    fun pipelinePassFunctionsAreIncluded() {
        val root = AstRoot(
            root = AstRootNode(
                passes = listOf(
                    AstPass(
                        functions = listOf(
                            AstFunction("transform", listOf(AstParam("pos", "float2")), "FLOAT2")
                        )
                    )
                )
            )
        )

        val functions = root.allFunctions()
        BwslAstCache.update("pipeline.bwsl", functions)

        val signatures = BwslAstCache.getSignatures("pipeline.bwsl")
        assertNotNull(signatures["transform"]) { "Expected pass function 'transform' in cache" }
        assertEquals(listOf("float2 pos"), signatures["transform"]!!.params)
    }

    private fun parseAstFunctions(resourcePath: String): List<AstFunction> {
        val file = File(javaClass.classLoader.getResource(resourcePath)!!.toURI())
        val rawBytes = file.readBytes()
        val hasUtf16Bom = rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte()
        val json = if (hasUtf16Bom) String(rawBytes, Charsets.UTF_16) else String(rawBytes, Charsets.UTF_8)
        val root = Gson().fromJson(json, AstRoot::class.java)
        return root.allFunctions()
    }
}
