package com.bwsl.plugin

/** Completion tests for positions inside a `vertex { ... }` shader stage body. */
class BwslCompletionVertexStageTest : BwslCompletionScopeTestCase() {

    fun testInsideVertexStageBody() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            <caret>\n" +
                "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}\n",
            present = setOf("normalize", "float", "if", "return"),
            absent = setOf("attributes", "resources", "variants", "pass", "vertex", "fragment", "compute", "module")
        )
    }
}
