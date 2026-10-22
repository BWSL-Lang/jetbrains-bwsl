package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.openapi.fileEditor.FileEditorManager

/**
 * What the cache keeps of a compile: the raw tree only for a file that is open, and for every file what Find Usages,
 * Go to Symbol, the resolver and the import suggestions read out of it.
 */
class BwslAstCacheTest : BwslAstFixtureTestCase() {

    private val lib = mapOf(
        "Lib" to "module Lib {\n    struct Box { float a; }\n    scale :: (float x, float y) -> float {\n        float sum = x + y;\n        return sum * 2.0;\n    }\n}"
    )

    private val pipeline = """
        pipeline P {
            import Lib
            attributes { position: float4 }
            pass "Main" {
                use attributes { position }
                outputs { c: float4 }
                vertex { output.pos = attributes.position; }
                fragment { output.c = float4(Lib::scale(1.0, 2.0)); }
            }
        }
    """.trimIndent()

    private fun cacheWithoutRaw(compiled: CompiledAst = BwslcAstHelper.compile(pipeline, lib)): String {
        val path = "C:/not/open/Shader.bwsl"
        BwslAstCache.update(path, compiled.root, compiled.rawJson, mapOf(normalizePathKey(path) to BwslAstCache.hashText(pipeline)), keepRaw = false)
        return path
    }

    fun testAFileThatIsNotKeptRawStillHasItsNodesAndModuleMembers() {
        val path = cacheWithoutRaw()

        assertNotNull(BwslAstCache.findRoot(path))
        assertNull("the raw tree is not kept", BwslAstCache.findRawRoot(path))
        assertNotNull(BwslAstCache.findIndexedNodes(path))
        assertEquals(listOf("Lib"), BwslAstCache.findModuleMembers(path)!!.map { it.module })
    }

    fun testAnIndexFromTheCachedNodesIsTheIndexFromTheRawTree() {
        val compiled = BwslcAstHelper.compile(pipeline, lib)
        val path = cacheWithoutRaw(compiled)

        val fromNodes = BwslAstIndex(compiled.root, BwslAstCache.findIndexedNodes(path)!!, pipeline)
        val fromRaw = BwslAstIndex(compiled.root, compiled.rawJson, pipeline)

        assertEquals(fromRaw.nodesById, fromNodes.nodesById)
        assertEquals(fromRaw.externalNodesById, fromNodes.externalNodesById)
        assertEquals(fromRaw.symbolsById, fromNodes.symbolsById)
    }

    fun testOfAnImportedModuleOnlyTheDeclarationsAreKept() {
        val compiled = BwslcAstHelper.compile(pipeline, lib)

        val kept = buildIndexedNodes(compiled.root, compiled.rawJson).externalNodesById

        assertTrue("the function, its parameters and the struct are kept: $kept", kept.values.any { it.name == "scale" })
        assertTrue(kept.values.any { it.name == "x" })
        assertTrue(kept.values.any { it.name == "Box" })
        assertTrue("the function's local and its statements are not: ${kept.values.map { it.name ?: it.type }}", kept.values.none { it.name == "sum" || it.type == "RETURN" })
    }

    fun testTheSingularRootIsNotKept() {
        val compiled = BwslcAstHelper.compile(pipeline, lib)

        assertFalse(compiled.rawJson.has("root"))
        assertTrue(compiled.rawJson.has("roots"))
    }

    fun testTheRawTreeIsLetGoOfWhenTheLastEditorClosesButTheRestStays() {
        configureAndCache(pipeline, lib)
        val file = myFixture.file.virtualFile
        val path = file.path
        assertNotNull(BwslAstCache.findRawRoot(path))

        FileEditorManager.getInstance(project).closeFile(file)

        assertNull(BwslAstCache.findRawRoot(path))
        assertNotNull(BwslAstCache.findRoot(path))
        assertNotNull(BwslAstCache.findIndexedNodes(path))
        assertNotNull(BwslAstCache.findModuleMembers(path))
    }
}
