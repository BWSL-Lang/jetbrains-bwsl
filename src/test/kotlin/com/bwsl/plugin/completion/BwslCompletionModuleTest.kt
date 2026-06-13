package com.bwsl.plugin.completion

import com.bwsl.plugin.*

/** Completion tests for positions inside a `module { ... }` block. */
class BwslCompletionModuleTest : BwslCompletionScopeTestCase() {

    fun testModuleBodyDoesNotSuggestModuleOrPipeline() {
        checkCompletions(
            "module M {\n" +
                "    <caret>\n" +
                "}\n",
            present = setOf("struct"),
            absent = setOf("module", "pipeline", "normalize", "vertex", "fragment", "compute", "attributes")
        )
    }

    fun testInsideFunctionBodySuggestsStatementsAndIntrinsics() {
        checkCompletions(
            "module M {\n" +
                "    f1 :: () -> float {\n" +
                "        <caret>\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "}\n",
            present = setOf("normalize", "return", "if", "break", "continue", "float"),
            absent = setOf("module", "pipeline")
        )
    }
}
