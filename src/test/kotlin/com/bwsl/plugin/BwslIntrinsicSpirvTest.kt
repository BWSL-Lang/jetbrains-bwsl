package com.bwsl.plugin

import java.io.File

/** The SPIR-V instruction behind each intrinsic, shown in its documentation and checked against the compiler's table. */
class BwslIntrinsicSpirvTest : BwslAstFixtureTestCase() {

    fun testAnExtendedInstructionLinksToTheGlslSpecAndIsMarkedAsSuch() {
        val html = renderSpirvHtml("lerp")!!

        assertTrue(html, html.contains("GLSL.std.450 FMix"))
        assertTrue(html, html.contains("href=\"https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html\""))
    }

    fun testACoreInstructionLinksToItsAnchorInTheSpecification() {
        val html = renderSpirvHtml("mod")!!

        assertTrue(html, html.contains("href=\"https://registry.khronos.org/SPIR-V/specs/unified1/SPIRV.html#OpFMod\""))
        assertTrue(html, html.contains(">OpFMod<"))
    }

    fun testSeveralCandidatesAreAllListed() {
        assertEquals(listOf("FClamp", "SClamp", "UClamp"), findSpirvInstructionsOf("clamp"))
        assertTrue(renderSpirvHtml("clamp")!!.contains(" / "))
    }

    fun testAnIntrinsicWithNoSingleInstructionHasNoLine() {
        assertNull(renderSpirvHtml("rcp"))
        assertNull(renderSpirvHtml("log10"))
        assertNull(renderSpirvHtml("noSuchIntrinsic"))
    }

    fun testTheDocumentationPopupShowsTheInstruction() {
        myFixture.configureByText("test.bwsl", "module M {\n    f :: (float a, float b) -> float {\n        return le<caret>rp(a, b, 0.5);\n    }\n}")

        val doc = generateDocAt(myFixture.caretOffset)!!

        assertTrue(doc, doc.contains("SPIR-V:"))
        assertTrue(doc, doc.contains("GLSL.std.450 FMix"))
        assertTrue("the description is still there", doc.contains("Linear interpolation"))
    }

    // The compiler's table: `INTRINSIC_FIXED(ENUM, "name", ... SPV_MAP(spv::OpX | SPV_OP_NONE, GLSLstd450Y | SPV_EXT_NONE))`.
    private fun readCompilerTable(): Map<String, List<String>>? {
        val repository = System.getProperty("bwslc.path")?.let { File(it).parentFile?.parentFile } ?: return null
        val header = File(repository, "src/core/bwsl_stdlib.h").takeIf { it.isFile } ?: return null
        val row = Regex("""(?:INTRINSIC_FIXED|INTRINSIC_VAR|TEXTURE_INTRINSIC)\(\w+,\s*"(\w+)"""")
        val mapping = Regex("""SPV_MAP\(([^,]+),\s*([^)]+)\)""")
        val table = LinkedHashMap<String, List<String>>()
        for (line in header.readLines()) {
            val name = row.find(line)?.groupValues?.get(1) ?: continue
            val match = mapping.find(line)
            val instructions = ArrayList<String>()
            if (match != null) {
                val core = match.groupValues[1].trim().removePrefix("spv::")
                val extended = match.groupValues[2].trim().removePrefix("GLSLstd450")
                if (core.startsWith("Op")) instructions += core
                if (!extended.startsWith("SPV_EXT") && extended.isNotEmpty()) instructions += extended
            }
            table[name] = instructions
        }
        return table
    }

    fun testEveryInstructionInTheTableIsWhatTheCompilerEmits() {
        val compiler = readCompilerTable() ?: return
        assertTrue("the compiler's table was read", compiler.size > 100)

        val disagreements = ArrayList<String>()
        for ((name, ours) in collectSpirvMappedIntrinsics()) {
            val theirs = compiler[name]
            if (theirs == null) disagreements += "$name is not an intrinsic of the compiler"
            else if (theirs.isNotEmpty() && !ours.containsAll(theirs)) disagreements += "$name: compiler $theirs, here $ours"
        }
        assertEquals("the mapping disagrees with the compiler's table", emptyList<String>(), disagreements)
    }

    fun testTheIntrinsicNamesAreTheCompilersNames() {
        val compiler = readCompilerTable() ?: return

        val onlyHere = BwslIntrinsics.NAMES - compiler.keys
        val onlyThere = compiler.keys - BwslIntrinsics.NAMES

        assertEquals("names the plugin has and the compiler does not", emptySet<String>(), onlyHere)
        assertEquals("names the compiler has and the plugin does not", emptySet<String>(), onlyThere)
    }
}
