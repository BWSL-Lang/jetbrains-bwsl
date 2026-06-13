package com.bwsl.plugin

/** Completion tests for positions inside a pipeline's `attributes { ... }` block. */
class BwslCompletionAttributesTest : BwslCompletionScopeTestCase() {

    fun testInsideAttributesBlock() {
        checkCompletions(
            "pipeline Base {\n" +
                "    attributes {\n" +
                "        position: float3\n" +
                "        <caret>\n" +
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
            present = setOf("float3", "float4", "bool", "uint"),
            absent = structuralKeywords + setOf("normalize", "if", "break", "continue")
        )
    }
}
