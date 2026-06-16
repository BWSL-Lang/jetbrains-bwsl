package com.bwsl.plugin.completion

import com.bwsl.plugin.*
import com.intellij.codeInsight.lookup.LookupElementPresentation

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

        val items = myFixture.completeBasic().associateBy { it.lookupString }

        assertTrue("expected 'position' to be suggested", items.containsKey("position"))
        assertTrue("expected 'uv' to be suggested", items.containsKey("uv"))
        assertFalse("expected 'color' NOT to be suggested", items.containsKey("color"))

        // Type text should reflect the deduced type from the RHS constructor.
        fun typeTextOf(key: String): String? {
            val pres = LookupElementPresentation()
            items[key]?.renderElement(pres)
            return pres.typeText
        }
        assertEquals("float4", typeTextOf("position"))
        assertEquals("float2", typeTextOf("uv"))
    }

    fun testInputMemberAccessTypeFromAttributesBlock() {
        // When output.X is assigned directly from attributes.X, type should be inferred from
        // the pipeline's attributes block (not from a function call).
        val source = "pipeline AttrTest {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "        texcoord: float2\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position, texcoord }\n" +
            "        vertex {\n" +
            "            output.pos = attributes.position;\n" +
            "            output.uv = attributes.texcoord;\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.color = float4(input.<caret>uv2, 0.0, 1.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        val root = BwslcAstHelper.parse(source.replace("<caret>", ""))
        myFixture.configureByText("fragment_attrs_test.bwsl", source)
        BwslAstCache.update(myFixture.file.virtualFile.path, root)

        val items = myFixture.completeBasic().associateBy { it.lookupString }

        assertTrue("expected 'uv' to be suggested", items.containsKey("uv"))
        val pres = LookupElementPresentation()
        items["uv"]?.renderElement(pres)
        assertEquals("float2", pres.typeText)
    }
}
