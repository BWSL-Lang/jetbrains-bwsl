package com.bwsl.plugin.completion

import com.bwsl.plugin.*

/** Completion tests for the file's root (top-level) scope, outside any module/pipeline. */
class BwslCompletionTopLevelTest : BwslCompletionScopeTestCase() {

    fun testTopLevelSuggestsOnlyModuleAndPipeline() {
        checkCompletions(
            "module M {\n" +
                "}\n" +
                "<caret>\n",
            present = setOf("module", "pipeline"),
            absent = setOf("normalize", "struct", "import", "const", "null", "true", "false", "bool", "float", "if")
        )
    }

    fun testTopLevelBeforeModule() {
        checkCompletions(
            "<caret>\n" +
                "module M {\n" +
                "}\n",
            present = setOf("module", "pipeline"),
            absent = setOf("normalize", "struct", "import", "bool", "float", "if")
        )
    }
}
