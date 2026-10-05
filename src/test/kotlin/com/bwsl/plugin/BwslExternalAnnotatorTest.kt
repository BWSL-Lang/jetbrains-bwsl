package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * The diagnostics annotator only wants diagnostics from bwslc. Without `-check`, bwslc also writes
 * `<stem>.vert.spv` / `.frag.spv` for every pass into the process's working directory, and nothing
 * ever deletes them.
 */
class BwslExternalAnnotatorTest : BasePlatformTestCase() {

    private lateinit var originalCompilerPath: String

    override fun setUp() {
        super.setUp()
        originalCompilerPath = BwslSettings.getInstance().compilerPath
        BwslSettings.getInstance().compilerPath = System.getProperty("bwslc.path")
            ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")
    }

    override fun tearDown() {
        try {
            BwslSettings.getInstance().compilerPath = originalCompilerPath
        } finally {
            super.tearDown()
        }
    }

    private fun buildPipelineSource(vertexBody: String) = """
        pipeline P {
            attributes {
                position: float4
            }
            pass "Main" {
                use attributes { position }
                outputs {
                    result: float4
                }
                vertex {
                    output.pos = attributes.position;
                    $vertexBody
                }
                fragment {
                    output.result = float4(1.0);
                }
            }
        }
    """.trimIndent()

    /** The SPIR-V files in the directories bwslc could write to when run by the annotator. */
    private fun collectSpirvFiles(): Set<String> =
        listOf(File(System.getProperty("user.dir")), File(System.getProperty("java.io.tmpdir")))
            .flatMap { dir -> dir.listFiles { f -> f.extension == "spv" }?.map { it.absolutePath }.orEmpty() }
            .toSet()

    fun testAValidPipelineLeavesNoSpirvFilesBehind() {
        val before = collectSpirvFiles()

        val diagnostics = BwslExternalAnnotator().doAnnotate(DiagnosticsRequest(buildPipelineSource("output.uv = float2(0.0);")))

        val created = collectSpirvFiles() - before
        try {
            assertTrue("Expected no diagnostics, got: $diagnostics", diagnostics.isEmpty())
            assertTrue("The annotator left SPIR-V files behind: $created", created.isEmpty())
        } finally {
            created.forEach { File(it).delete() }
        }
    }

    fun testDiagnosticsAreStillReportedAndNoSpirvFilesAreLeftBehind() {
        val before = collectSpirvFiles()

        val diagnostics = BwslExternalAnnotator().doAnnotate(
            DiagnosticsRequest(buildPipelineSource("output.uv = float2(0.0); output.uv = float3(0.0);"))
        )

        val created = collectSpirvFiles() - before
        try {
            assertTrue(
                "Expected the conflicting-types error, got: $diagnostics",
                diagnostics.any { it.severity == "error" && it.message.contains("conflicting types") }
            )
            assertTrue("The annotator left SPIR-V files behind: $created", created.isEmpty())
        } finally {
            created.forEach { File(it).delete() }
        }
    }

    fun testAModuleBesideTheFileIsFoundForTheEditorsText() {
        val directory = java.nio.file.Files.createTempDirectory("bwsl_diag_test_").toFile()
        try {
            File(directory, "Lib.bwsl").writeText("module Lib {\n    helper :: (float v) -> float { return v; }\n}\n")
            val app = File(directory, "App.bwsl")
            val source = "module App {\n    import Lib\n    f :: (float x) -> float { return Lib::helper(x); }\n}\n"
            app.writeText(source)

            val withFile = BwslExternalAnnotator().doAnnotate(DiagnosticsRequest(source, emptyList(), app.path))
            val withoutFile = BwslExternalAnnotator().doAnnotate(DiagnosticsRequest(source))

            assertEquals("the import resolves beside the file, got: $withFile", emptyList<Diagnostic>(), withFile.filter { it.severity == "error" })
            assertTrue("without a file the module cannot be found", withoutFile.any { it.message.contains("Unknown module") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
