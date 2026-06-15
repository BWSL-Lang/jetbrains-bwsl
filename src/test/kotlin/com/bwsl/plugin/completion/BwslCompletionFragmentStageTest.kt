package com.bwsl.plugin.completion

import com.bwsl.plugin.*

/** Completion tests for positions inside a `fragment { ... }` shader stage body. */
class BwslCompletionFragmentStageTest : BwslCompletionScopeTestCase() {

    fun testInputMemberAccessSuggestsVertexOutputs() {
        // The caret sits right after "input." with a placeholder member name following it, so the
        // source is valid for AST parsing; completion is requested at the position before "pos".
        val source = "pipeline Base {\n" +
            "    pass \"Main\" {\n" +
            "        vertex {\n" +
            "            output.position = float4(0.0, 0.0, 0.0, 1.0);\n" +
            "            output.uv = float2(0.0, 0.0);\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.color = float4(input.<caret>pos, 0.0, 1.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        val root = BwslcAstHelper.parse(source.replace("<caret>", ""))
        myFixture.configureByText("fragment_input_test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, root)

        val strings = myFixture.completeBasic().map { it.lookupString }

        assertTrue("expected 'position' to be suggested, got: $strings", strings.contains("position"))
        assertTrue("expected 'uv' to be suggested, got: $strings", strings.contains("uv"))
        assertFalse("expected 'color' NOT to be suggested, got: $strings", strings.contains("color"))
    }
}
