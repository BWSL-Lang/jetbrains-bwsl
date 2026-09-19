package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the position-semantics table in FUCK_THE_LEXER.md §3 against real bwslc output.
 * This is the gate for Phase 2: every later phase's resolution logic depends on [BwslAstIndex]'s
 * [BwslAstIndex.nameRangeOf] landing on the actual identifier text, not just "close to" it.
 */
class BwslAstIndexTest {

    private fun moduleBwslSource(): String =
        File(javaClass.classLoader.getResource("lexer_test_files/module.bwsl")!!.toURI()).readText()

    private fun buildIndex(source: String): Pair<BwslAstIndex, AstRoot> {
        val root = BwslcAstHelper.parse(source)
        val raw = BwslcAstHelper.parseRaw(source)
        return BwslAstIndex(root, raw, source) to root
    }

    private fun assertNameRangeIs(index: BwslAstIndex, source: String, nodeId: String, expected: String) {
        val node = index.nodesById[nodeId]
        assertNotNull(node) { "Expected node $nodeId in index" }
        val range = index.nameRangeOf(node!!)
        assertNotNull(range) { "Expected nameRangeOf to resolve a range for $nodeId (${node.type})" }
        assertEquals(
            expected, source.substring(range!!.first, range.last + 1),
            "nameRangeOf mismatch for $nodeId (${node.type}) at ${node.line}:${node.column}"
        )
    }

    @Test
    fun everyReferenceEdgeFromANamedNodeResolvesToItsIdentifierText() {
        val source = moduleBwslSource()
        val (index, root) = buildIndex(source)
        val references = root.referenceIndex?.references.orEmpty()
        assertTrue(references.isNotEmpty(), "Expected a non-empty reference index for module.bwsl")

        var checked = 0
        for (ref in references) {
            // Synthetic ids (FUNCTION:n/parameter:n, STRUCT_DECL:n/field:n) have no source
            // position - out of scope here, see FUCK_THE_LEXER.md gap #1.
            val node = index.nodesById[ref.from] ?: continue
            val expected = node.member ?: node.name ?: continue
            // A for-loop's `int i = 0` init clause is a VARIABLE_DECL with no nameLine/nameColumn
            // at all (line/column there points at the type, "int") - nameRangeOf deliberately
            // fails closed rather than guess. Documented gap, not a bug; see FUCK_THE_LEXER.md.
            if (node.type == "VARIABLE_DECL" && node.nameLine == null) continue
            assertNameRangeIs(index, source, ref.from, expected)
            checked++
        }
        assertTrue(checked > 20, "Expected to have actually verified a meaningful number of edges, got $checked")
    }

    @Test
    fun moduleNameRangeSkipsTheModuleKeyword() {
        val source = moduleBwslSource()
        val (index, _) = buildIndex(source)
        assertNameRangeIs(index, source, "MODULE:0", "Test1")
        assertNameRangeIs(index, source, "MODULE:1", "LengthMethodTest")
        assertNameRangeIs(index, source, "MODULE:2", "LengthTest2")
    }

    @Test
    fun structDeclNameRangeSkipsTheStructKeyword() {
        val source = moduleBwslSource()
        val (index, _) = buildIndex(source)
        assertNameRangeIs(index, source, "STRUCT_DECL:0", "testStruct")
        assertNameRangeIs(index, source, "STRUCT_DECL:1", "testStruct")
    }

    @Test
    fun receiverAndQualifiedFunctionCallsPointAtTheNameNotTheDotOrColonColon() {
        val source = moduleBwslSource()
        val (index, _) = buildIndex(source)

        // "values.length()" - receiver-based, line/column is the dot.
        val receiverCall = index.nodesById.values.first {
            it.type == "FUNCTION_CALL" && it.hasReceiver && it.name == "length"
        }
        assertNameRangeIs(index, source, receiverCall.id, "length")

        // "LengthMethodTest::test(values)" - module-qualified, line/column is the '::'.
        val qualifiedCall = index.nodesById.values.first {
            it.type == "FUNCTION_CALL" && it.hasModuleQualifier
        }
        assertNameRangeIs(index, source, qualifiedCall.id, "test")
    }

    @Test
    fun pipelineAttributeAndMemberAccessNameRangesAreCorrect() {
        val source = "pipeline ShaderIoTest {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position }\n" +
            "        vertex {\n" +
            "            output.position = float4(0, 0, 0, 1);\n" +
            "            output.uv = float2(0, 0);\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.color = float4(input.uv, 0.0, 1.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"
        val (index, root) = buildIndex(source)

        // PIPELINE keyword-skip.
        assertNameRangeIs(index, source, "PIPELINE:0", "ShaderIoTest")

        // ATTRIBUTE_DECL: line/column points at the type ("float4"), not the name.
        assertNameRangeIs(index, source, "ATTRIBUTE_DECL:0", "position")

        // MEMBER_ACCESS: line/column is the dot, member starts right after it.
        val memberAccessRefs = root.referenceIndex!!.references.filter { it.role == "output" || it.role == "input" }
        assertTrue(memberAccessRefs.isNotEmpty(), "Expected output/input MEMBER_ACCESS edges in the pipeline probe")
        for (ref in memberAccessRefs) {
            val node = index.nodesById[ref.from]!!
            assertEquals("MEMBER_ACCESS", node.type)
            assertNameRangeIs(index, source, ref.from, node.member!!)
        }
    }
}
