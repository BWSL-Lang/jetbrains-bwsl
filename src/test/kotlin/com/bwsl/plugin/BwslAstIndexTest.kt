package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies against real bwslc output that [BwslAstIndex.nameRangeOf] - which is just a node's
 * nameLine/nameColumn plus its name - lands on the actual identifier text, not just "close to" it.
 * Every resolution in the plugin depends on that.
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
            val node = index.nodesById[ref.from] ?: continue
            val expected = node.member ?: node.name ?: continue
            assertNameRangeIs(index, source, ref.from, expected)
            checked++
        }
        assertTrue(checked > 20, "Expected to have actually verified a meaningful number of edges, got $checked")
    }

    @Test
    fun forLoopInitVariableDeclHasBothNameAndTypeRanges() {
        // A for-loop's `int i = 0` init clause is a VARIABLE_DECL under the loop node rather than in a
        // block's statements; it must have both a name ("i") and a declared-type ("int") range.
        val source = moduleBwslSource()
        val (index, _) = buildIndex(source)
        val forInit = index.nodesById["VARIABLE_DECL:3"]
        assertNotNull(forInit) { "Expected VARIABLE_DECL:3 (the for-loop's 'int i') in the index" }
        assertEquals("i", forInit!!.name)

        assertNameRangeIs(index, source, "VARIABLE_DECL:3", "i")

        val typeRange = index.typeRangeOf(forInit)
        assertNotNull(typeRange) { "Expected a resolvable type range for the for-loop's 'int i'" }
        assertEquals("int", source.substring(typeRange!!.first, typeRange.last + 1))
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

        // line/column of "values.length()" is the dot and of "Mod::test(values)" the "::", but
        // nameLine/nameColumn is the name in both - check the character just before it.
        val calls = index.nodesById.values.filter { it.type == "FUNCTION_CALL" && it.name != null }
        for (call in calls) assertNameRangeIs(index, source, call.id, call.name!!)
        val before = calls.map { source[index.nameRangeOf(it)!!.first - 1] }
        assertTrue('.' in before, "Expected a receiver call (recv.f()) in module.bwsl")
        assertTrue(':' in before, "Expected a module-qualified call (Mod::f()) in module.bwsl")
    }

    @Test
    fun everyNamedNodeIncludingMembersOfADeclarationPointsAtItsNameText() {
        // Every named node has nameLine/nameColumn: struct fields, parameters,
        // attributes, used attributes, fragment outputs, consts and import/using entries included.
        val source = """
            pipeline AllNames {
                attributes {
                    position: float4
                }
                const float SCALE = 2.0;
                struct Box {
                    float width;
                }
                pass "Main" {
                    use attributes { position }
                    outputs {
                        result: float4
                    }
                    vertex {
                        output.pos = attributes.position;
                    }
                    fragment {
                        output.result = float4(SCALE);
                    }
                }
            }
        """.trimIndent() + "\n"
        val (index, _) = buildIndex(source)

        val expectedKinds = listOf("/field:", "/used-attribute:", "/fragment-output:", "ATTRIBUTE_DECL:", "PIPELINE:", "PASS:")
        for (kind in expectedKinds) {
            val node = index.nodesById.values.firstOrNull { it.id.contains(kind) }
            assertNotNull(node) { "Expected a node with id containing $kind" }
            val expected = node!!.name ?: error("$kind node has no name")
            assertNameRangeIs(index, source, node.id, expected)
        }
        val constDecl = index.nodesById.values.firstOrNull { it.type == "VARIABLE_DECL" && it.name == "SCALE" }
        assertNotNull(constDecl) { "Expected the pipeline-level const SCALE as a VARIABLE_DECL" }
        assertNameRangeIs(index, source, constDecl!!.id, "SCALE")
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

        // PIPELINE: line/column is the keyword, nameLine/nameColumn the name.
        assertNameRangeIs(index, source, "PIPELINE:0", "ShaderIoTest")

        // ATTRIBUTE_DECL: line/column points at the type ("float4"), nameLine/nameColumn the name.
        assertNameRangeIs(index, source, "ATTRIBUTE_DECL:0", "position")

        // MEMBER_ACCESS: line/column is the dot, nameLine/nameColumn the member.
        val memberAccessRefs = root.referenceIndex!!.references.filter { it.role == "output" || it.role == "input" }
        assertTrue(memberAccessRefs.isNotEmpty(), "Expected output/input MEMBER_ACCESS edges in the pipeline probe")
        for (ref in memberAccessRefs) {
            val node = index.nodesById[ref.from]!!
            assertEquals("MEMBER_ACCESS", node.type)
            assertNameRangeIs(index, source, ref.from, node.member!!)
        }
    }
}
