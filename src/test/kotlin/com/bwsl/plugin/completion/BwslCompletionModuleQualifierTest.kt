package com.bwsl.plugin.completion

import com.intellij.openapi.util.SystemInfo

/** `import <module>` and `Module.` in a resources block: which modules and which types are offered. */
class BwslCompletionModuleQualifierTest : BwslCompletionScopeTestCase() {

    private val lib = mapOf("Golyvec" to "module Golyvec {\n    struct Render { float4 mask; }\n    enum Mode { A, B }\n    const float K = 1.0;\n}")

    private fun pipeline(resource: String) = """
        pipeline P {
            import Golyvec
            resources {
                $resource
            }
            attributes { position: float4 }
            pass "Main" {
                use attributes { position }
                outputs { c: float4 }
                vertex { output.pos = attributes.position; }
                fragment { output.c = float4(1.0); }
            }
        }
    """.trimIndent()

    fun testAModuleNameInAResourcesBlockOffersTheTypesOfTheModule() {
        assertCompletions(
            pipeline("render: Golyvec.<caret>Render"),
            present = setOf("Render", "Mode"),
            absent = setOf("K", "pipeline", "float4"),
            modules = lib
        )
    }

    fun testImportOffersTheModulesOfTheProjectsFilesByTheirDeclaredNames() {
        val directory = myFixture.addFileToProject("modules/Helpers.bwsl", "module Helpers {\n}\n")
        val notAModule = myFixture.addFileToProject("atomic_operations.bwsl", "module Elsewhere {\n}\n")
        val aPipeline = myFixture.addFileToProject("circle.bwsl", "pipeline Circle {\n}\n")
        for (file in listOf(directory, notAModule, aPipeline)) {
            BwslcAstHelper.parseAndCache(file.text, file.virtualFile.path)
        }
        myFixture.configureByText("use.bwsl", "module M {\n    import <caret>\n}")

        val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertTrue("a module in a file of its name is offered, got: $strings", "Helpers" in strings)
        assertFalse("a file's name is not a module's, got: $strings", "atomic_operations" in strings)
        assertFalse("nor is a module bwslc could not find in that file, got: $strings", "Elsewhere" in strings)
        assertFalse("a pipeline is not importable, got: $strings", "circle" in strings || "Circle" in strings)
    }

    fun testAModuleInAFileWithADifferentCaseIsOfferedWhereTheFileSystemIgnoresCase() {
        val file = myFixture.addFileToProject("golyvec.bwsl", "module Golyvec {\n}\n")
        BwslcAstHelper.parseAndCache(file.text, file.virtualFile.path)
        myFixture.configureByText("use.bwsl", "module M {\n    import <caret>\n}")

        val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertEquals("got: $strings", !SystemInfo.isFileSystemCaseSensitive, "Golyvec" in strings)
    }

}
