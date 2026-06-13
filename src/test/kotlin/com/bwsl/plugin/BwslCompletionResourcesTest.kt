package com.bwsl.plugin

/** Completion tests for positions inside a pipeline's `resources { ... }` block. */
class BwslCompletionResourcesTest : BwslCompletionScopeTestCase() {

    fun testInsideResourcesBlock() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "    }\n" +
                "    resources {\n" +
                "        output: buffer<float>\n" +
                "        <caret>\n" +
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
            present = setOf("float3", "float4", "bool", "uint", "buffer"),
            absent = structuralKeywords + setOf("normalize", "if", "break", "continue")
        )
    }
}
