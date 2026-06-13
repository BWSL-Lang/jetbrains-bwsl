package com.bwsl.plugin

/**
 * Completion tests for positions directly inside a `pipeline { ... }` block (not inside any of
 * its subblocks) — i.e. the gaps before/between/after `attributes`/`resources`/`variants`/`pass`.
 * Each test provides a realistic pipeline with `<caret>` placed at the position under test;
 * [checkCompletions] compiles the caret-free source with the real bwslc compiler and asserts the
 * suggestions at that position.
 */
class BwslCompletionPipelineTest : BwslCompletionScopeTestCase() {

    fun testPipelineBodyBeforeAttributes() {
        checkCompletions(
            "pipeline Base {\n" +
                "    <caret>\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    resources {\n" +
                "        output: buffer<float>\n" +
                "    }\n" +
                "    variants {\n" +
                "        useFog: bool = false;\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            present = pipelineBodyKeywords,
            absent = setOf("module", "vertex", "fragment", "compute", "float", "bool", "if", "break", "continue", "normalize")
        )
    }

    fun testBetweenAttributesAndResources() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    <caret>\n" +
                "    resources {\n" +
                "        output: buffer<float>\n" +
                "    }\n" +
                "    variants {\n" +
                "        useFog: bool = false;\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            present = pipelineBodyKeywords,
            absent = setOf("module", "vertex", "fragment", "compute", "float", "bool", "if", "break", "continue", "normalize")
        )
    }

    fun testPipelineBodyBeforePass() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    resources {\n" +
                "        output: buffer<float>\n" +
                "    }\n" +
                "    variants {\n" +
                "        useFog: bool = false;\n" +
                "    }\n" +
                "    <caret>\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            present = pipelineBodyKeywords,
            absent = setOf("module", "vertex", "fragment", "compute", "float", "bool", "if", "break", "continue", "normalize")
        )
    }

    fun testPipelineBodyAfterPass() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "    <caret>\n" +
                "}\n",
            present = pipelineBodyKeywords,
            absent = setOf("module", "vertex", "fragment", "compute", "float", "bool", "if", "break", "continue", "normalize")
        )
    }
}
