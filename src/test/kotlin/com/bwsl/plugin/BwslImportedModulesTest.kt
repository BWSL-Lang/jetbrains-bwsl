package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * bwslc is given `-modules` paths, so the AST for a file that imports a module also contains that
 * module - with line/column relative to *its own* file. Each top-level entry and member names that
 * file in `sourceFile`. Everything here guards against those nodes being measured
 * against the compiled file's text.
 */
class BwslImportedModulesTest {

    // Common spans lines 1-9; the main file's module M is shorter, leaving lines past M's end that
    // fall inside Common's range if the two coordinate spaces are ever mixed.
    private val common =
        "module Common {\n" +
            "    struct Box {\n" +
            "        float size;\n" +
            "    }\n" +
            "\n" +
            "    helper :: () -> float {\n" +
            "        return 1.0;\n" +
            "    }\n" +
            "}\n"

    private val main =
        "module M {\n" +
            "    import Common\n" +
            "\n" +
            "    run :: () -> float {\n" +
            "        Common::Box b;\n" +
            "        return Common::helper();\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "\n"

    private fun buildIndexForMainModule(): Pair<BwslAstIndex, AstRoot> =
        BwslcAstHelper.buildIndexAndRoot(main, mapOf("Common" to common))

    @Test
    fun testImportedModulesAppearInTheAstButNotInRoots() {
        val (_, root) = buildIndexForMainModule()
        assertEquals(listOf("M", "Common"), root.modules.map { it.name })
        assertEquals(listOf("M"), root.collectOwnModules().map { it.name })
    }

    @Test
    fun testImportedNodesAreKeptOutOfTheCompiledFilesPositions() {
        val (index, _) = buildIndexForMainModule()

        // `helper` is declared at line 6 of Common.bwsl; indexing it as if it were in main.bwsl
        // would put it on an unrelated line of main (there, line 6 is `return Common::helper();`).
        assertTrue(index.nodesById.values.none { it.type == "FUNCTION" && it.name == "helper" })
        val helper = index.externalNodesById.values.firstOrNull { it.type == "FUNCTION" && it.name == "helper" }
        assertNotNull(helper) { "Expected Common's helper in externalNodesById" }
        assertTrue(helper!!.sourceFile!!.endsWith("Common.bwsl")) { "helper's sourceFile was ${helper.sourceFile}" }

        // The compiled file's own declarations are still indexed normally.
        val run = index.nodesById.values.firstOrNull { it.type == "FUNCTION" && it.name == "run" }
        assertNotNull(run)
        assertTrue(run!!.sourceFile!!.endsWith("test.bwsl")) { "run's sourceFile was ${run.sourceFile}" }
    }

    @Test
    fun testMembersOfAnImportedStructAreExternalAndPositionedInTheirOwnFile() {
        val (index, _) = buildIndexForMainModule()
        val field = index.externalNodesById.values.firstOrNull { it.id.contains("/field:") && it.name == "size" }
        assertNotNull(field) { "Expected Common's Box.size field in externalNodesById" }
        assertTrue(field!!.sourceFile!!.endsWith("Common.bwsl"))

        val range = index.createPositionsFor(common).findNameRangeOf(field)
        assertNotNull(range) { "A struct field has a name position" }
        assertEquals(common.indexOf("size"), range!!.first)
    }

    @Test
    fun testImportedNodesArePositionedAgainstTheirOwnFilesText() {
        val (index, _) = buildIndexForMainModule()
        val helper = index.externalNodesById.values.first { it.type == "FUNCTION" && it.name == "helper" }

        val range = index.createPositionsFor(common).findNameRangeOf(helper)
        assertNotNull(range)
        assertEquals("helper", common.substring(range!!.first, range.last + 1))
        assertEquals(common.indexOf("helper ::"), range.first)
    }

    @Test
    fun testBlockContextIgnoresImportedModulesLineRanges() {
        val (_, root) = buildIndexForMainModule()
        // Line 9 of main.bwsl is past the end of module M (lines 1-8) but inside Common's own
        // 1-9 range - it must be top level, not "inside a module".
        assertEquals(BwslBlockContext.TOP_LEVEL, classifyBlockContextAt(root, 9, 1, main))
    }

    @Test
    fun testFindScopeIgnoresImportedModulesLineRanges() {
        val (_, root) = buildIndexForMainModule()
        val scope = findScope(root, 9, 1)
        assertNull(scope.module)
    }
}
