package com.bwsl.plugin

/** Completion tests for positions inside a `pass { ... }` block, directly (not inside a shader stage). */
class BwslCompletionPassTest : BwslCompletionScopeTestCase() {

    fun testInsidePassBodyBeforeVertex() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        <caret>\n" +
                "        vertex {\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            present = setOf("vertex", "fragment", "compute"),
            absent = setOf("attributes", "resources", "variants", "pass", "module", "float", "bool", "normalize")
        )
    }
}
