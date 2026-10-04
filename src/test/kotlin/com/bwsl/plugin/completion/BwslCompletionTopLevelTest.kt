package com.bwsl.plugin.completion

/** Completion tests for the file's root (top-level) scope, outside any module/pipeline. */
class BwslCompletionTopLevelTest : BwslCompletionScopeTestCase() {

    fun testTopLevelSuggestsOnlyModuleAndPipeline() {
        assertCompletions(
            "module M {\n" +
                "}\n" +
                "<caret>\n",
            present = setOf("module", "submodule", "pipeline"),
            absent = setOf("normalize", "struct", "import", "const", "null", "true", "false", "bool", "float", "if")
        )
    }

    fun testTopLevelBeforeModule() {
        assertCompletions(
            "<caret>\n" +
                "module M {\n" +
                "}\n",
            present = setOf("module", "submodule", "pipeline"),
            absent = setOf("normalize", "struct", "import", "bool", "float", "if")
        )
    }

    fun testTopLevelSuggestsSubmodule() {
        assertCompletions(
            "module Base {\n" +
                "}\n" +
                "<caret>\n",
            present = setOf("submodule"),
            absent = setOf("if", "float", "normalize")
        )
    }

    fun testExtendsIsSuggestedAfterSubmoduleName() {
        // Caret sits between the submodule name and `extends`, so the cleaned source is still
        // valid bwslc input while the PSI at the caret position has MODULE_NAME ← KW_SUBMODULE.
        assertCompletions(
            "module Base {\n}\nsubmodule Child <caret>extends Base {\n}\n",
            present = setOf("extends"),
            absent = setOf("module", "pipeline", "if", "float", "normalize")
        )
    }

    fun testExtendsIsNotSuggestedInFunctionBody() {
        assertCompletions(
            "module Base {\n" +
                "    f :: () -> void { <caret> }\n" +
                "}\n",
            absent = setOf("extends")
        )
    }

    fun testExtendsIsNotSuggestedAtTopLevel() {
        assertCompletions(
            "module Base {\n" +
                "}\n" +
                "<caret>\n",
            absent = setOf("extends")
        )
    }
}
