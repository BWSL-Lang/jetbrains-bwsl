package com.bwsl.plugin

/** The SPIR-V instruction behind each intrinsic, shown in its documentation and checked against the compiler's table. */
class BwslIntrinsicSpirvTest : BwslAstFixtureTestCase() {

    fun testAnExtendedInstructionLinksToTheGlslSpecAndIsMarkedAsSuch() {
        val html = renderSpirvHtml("lerp")!!

        assertTrue(html, html.contains("GLSL.std.450 FMix"))
        assertTrue(html, html.contains("href=\"https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html#:~:text=FMix\""))
    }

    fun testAnExtendedInstructionIsSelectedInThePageByItsName() {
        assertEquals("https://registry.khronos.org/SPIR-V/specs/unified1/GLSL.std.450.html#:~:text=Sin", buildSpirvSpecUrl("Sin"))
    }

    fun testAnInstructionWhoseNameIsAlsoInOtherProseIsToldApartByWhatFollowsIt() {
        assertTrue(buildSpirvSpecUrl("RoundEven").endsWith("#:~:text=RoundEven,-Result%20is%20the%20value"))
        assertTrue(buildSpirvSpecUrl("Degrees").endsWith("#:~:text=Degrees,-Converts%20radians"))
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

    fun testEveryMappedNameIsAnIntrinsicOfTheTable() {
        val unknown = collectSpirvMappedIntrinsics().keys - BwslIntrinsics.NAMES

        assertEquals("mapped names that are not in the intrinsic table", emptySet<String>(), unknown)
    }

    /**
     * Whether the compiler under test knows an intrinsic called [name], found out by calling it with no
     * arguments: one that needs arguments says so by name, and one that needs none resolves to the built-in
     * in the reference index. A name it does not know does neither.
     */
    private fun doesCompilerKnow(name: String): Boolean {
        val compiler = resolveCompilerPath() ?: error("no compiler configured")
        val source = "module Probe {\n    f :: () -> float { return $name(); }\n}\n"
        val path = "/probe/Probe.bwsl"
        if (collectDiagnostics(compiler, path, emptyList(), stdinText = source).any { it.message.contains("'$name'") }) return true
        val ast = compileAst(compiler, path, emptyList(), stdinText = source) ?: return false
        return ast.root.referenceIndex?.references.orEmpty().any { it.to == "builtin:function:$name" }
    }

    fun testTheProbeTellsAnIntrinsicFromAnUnknownName() {
        assertTrue(doesCompilerKnow("sin"))
        assertTrue(doesCompilerKnow("barrier"))
        assertFalse(doesCompilerKnow("noSuchIntrinsicAnywhere"))
    }

    fun testTheCompilerKnowsEveryIntrinsicTheTableLists() {
        val unknown = BwslIntrinsics.NAMES.sorted().filterNot { doesCompilerKnow(it) }

        assertEquals("intrinsics in the plugin's table that this compiler does not know", emptyList<String>(), unknown)
    }
}
