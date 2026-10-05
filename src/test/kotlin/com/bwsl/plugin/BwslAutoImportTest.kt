package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.nio.file.Files

/**
 * Auto-import: names from a module the file does not import yet are offered on a second Ctrl+Space
 * (or straight away after `Module::`), and choosing one writes `Module::name` and adds `import Module`.
 */
class BwslAutoImportTest : BwslAstFixtureTestCase() {

    private lateinit var originalModulePaths: MutableList<String>
    private lateinit var modulesDirectory: File

    override fun setUp() {
        super.setUp()
        originalModulePaths = BwslSettings.getInstance().modulePaths
        modulesDirectory = Files.createTempDirectory("bwsl_autoimport_test_").toFile()
        BwslSettings.getInstance().modulePaths = mutableListOf(modulesDirectory.path)
    }

    override fun tearDown() {
        try {
            BwslSettings.getInstance().modulePaths = originalModulePaths
            modulesDirectory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val shapesSource =
        "module Shapes {\n    struct Circle {\n        float radius;\n    }\n\n    const float UNIT = 1.0;\n\n" +
            "    area :: (float r) -> float { return r * r * 3.14; }\n}\n"

    private fun writeShapesModule() {
        val file = File(modulesDirectory, "Shapes.bwsl")
        file.writeText(shapesSource)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        BwslProjectIndex.getInstance(project).refreshNow()
    }

    private val body = "module M {\n    f :: (float x) -> float {\n        return <caret>x;\n    }\n}"

    private fun describeItem(item: LookupElement): LookupElementPresentation =
        LookupElementPresentation().also { item.renderElement(it) }

    private fun chooseItem(name: String, invocationCount: Int = 2) {
        val items = myFixture.complete(CompletionType.BASIC, invocationCount).orEmpty()
        val item = items.firstOrNull { it.lookupString == name } ?: error("$name is not offered, got: ${items.map { it.lookupString }}")
        (LookupManager.getActiveLookup(myFixture.editor) as LookupImpl).currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
    }

    // --- the text edit that adds an import ---------------------------------------------------------------

    private fun importInto(sourceWithCaret: String, module: String): String? {
        myFixture.configureByText("insert.bwsl", sourceWithCaret)
        val insertion = findImportInsertion(myFixture.file, myFixture.editor.caretModel.offset, module) ?: return null
        return StringBuilder(myFixture.file.text).insert(insertion.offset, insertion.text).toString()
    }

    fun testTheImportGoesFirstInAModuleThatHasNone() {
        assertEquals(
            "module M {\n    import Math\n    f :: () -> float {\n        return 1.0;\n    }\n}",
            importInto("module M {\n    f :: () -> float {\n        <caret>return 1.0;\n    }\n}", "Math")
        )
    }

    fun testTheImportGoesAfterTheLastImportAndLinesUpWithIt() {
        assertEquals(
            "module M {\n    import A\n    import B as BB\n    import Math\n    f :: () -> float {\n        return 1.0;\n    }\n}",
            importInto("module M {\n    import A\n    import B as BB\n    f :: () -> float {\n        <caret>return 1.0;\n    }\n}", "Math")
        )
    }

    fun testAModuleThatIsImportedAlreadyIsNotImportedAgain() {
        assertNull(importInto("module M {\n    import Math\n    f :: () -> float {\n        <caret>return 1.0;\n    }\n}", "Math"))
        assertNull("also under an alias", importInto("module M {\n    import Math as MM\n    f :: () -> float {\n        <caret>return 1.0;\n    }\n}", "Math"))
    }

    fun testAPipelineGetsTheImportIndentedLikeItsContents() {
        assertEquals(
            "pipeline P {\n  import Math\n  attributes {\n    position: float4\n  }\n  pass \"Main\" {\n    <caret>\n  }\n}".replace("<caret>", ""),
            importInto("pipeline P {\n  attributes {\n    position: float4\n  }\n  pass \"Main\" {\n    <caret>\n  }\n}", "Math")
        )
    }

    fun testTheImportGoesIntoTheModuleThatHoldsTheCaret() {
        assertEquals(
            "module A {\n    f :: () -> float { return 1.0; }\n}\nmodule B {\n    import Math\n    g :: () -> float {\n        return 2.0;\n    }\n}",
            importInto("module A {\n    f :: () -> float { return 1.0; }\n}\nmodule B {\n    g :: () -> float {\n        <caret>return 2.0;\n    }\n}", "Math")
        )
    }

    fun testASingleLineModuleIsSplitSoTheImportStandsOnItsOwnLine() {
        assertEquals(
            "module M {\n    import Math\n f :: () -> float { return 1.0; } }",
            importInto("module M { f :: () -> float { <caret>return 1.0; } }", "Math")
        )
    }

    fun testNothingIsInsertedOutsideAModuleOrPipeline() {
        assertNull(importInto("<caret>\nmodule M {\n}", "Math"))
    }

    fun testAModuleThatIsNotClosedYetStillGetsTheImport() {
        assertEquals(
            "module M {\n    import Math\n    f :: () -> float {\n        return 1.0;\n",
            importInto("module M {\n    f :: () -> float {\n        <caret>return 1.0;\n", "Math")
        )
    }

    // --- completion --------------------------------------------------------------------------------------

    fun testFirstCompletionDoesNotListNamesFromModulesThatAreNotImportedButTheSecondDoes() {
        writeShapesModule()
        configureAndCache(body)

        val first = myFixture.complete(CompletionType.BASIC, 1).orEmpty().map { it.lookupString }
        assertFalse("not on the first Ctrl+Space, got: $first", first.contains("area"))

        myFixture.configureByText("again.bwsl", body)
        BwslcAstHelper.parseAndCache(body.replace("<caret>", ""), myFixture.file.virtualFile.path)
        val second = myFixture.complete(CompletionType.BASIC, 2).orEmpty()
        val area = second.firstOrNull { it.lookupString == "area" }
        assertNotNull("area is offered on the second, got: ${second.map { it.lookupString }}", area)
        assertEquals("(float r) Shapes (import)", describeItem(area!!).tailText)
        assertTrue(second.map { it.lookupString }.containsAll(listOf("Circle", "area")))
    }

    fun testChoosingANameFromAnUnimportedModuleQualifiesItAndAddsTheImport() {
        writeShapesModule()
        configureAndCache(body)

        chooseItem("area")

        myFixture.checkResult("module M {\n    import Shapes\n    f :: (float x) -> float {\n        return Shapes::area(<caret>)x;\n    }\n}")
    }

    fun testChoosingAStructFromAnUnimportedModuleWritesAQualifiedType() {
        writeShapesModule()
        configureAndCache("module M {\n    f :: (float x) -> float {\n        <caret>\n        return x;\n    }\n}")

        chooseItem("Circle")

        myFixture.checkResult("module M {\n    import Shapes\n    f :: (float x) -> float {\n        Shapes::Circle<caret>\n        return x;\n    }\n}")
    }

    fun testATypedQualifierOfAnUnimportedModuleOffersItsMembersAtOnce() {
        writeShapesModule()
        configureAndCache("module M {\n    f :: (float x) -> float {\n        return Shapes::<caret>x;\n    }\n}")

        chooseItem("area", invocationCount = 1)

        myFixture.checkResult("module M {\n    import Shapes\n    f :: (float x) -> float {\n        return Shapes::area(<caret>)x;\n    }\n}")
    }

    fun testAModuleThatIsImportedIsOfferedWithoutTheImportMarker() {
        writeShapesModule()
        configureAndCache(
            "module M {\n    import Shapes\n    using Shapes\n    f :: (float x) -> float {\n        return <caret>x;\n    }\n}",
            modules = mapOf("Shapes" to shapesSource)
        )

        val items = myFixture.complete(CompletionType.BASIC, 2).orEmpty().filter { it.lookupString == "area" }

        assertEquals("one area, the imported one", 1, items.size)
        assertFalse(describeItem(items.single()).tailText.orEmpty().contains("(import)"))
    }

    fun testTheModuleTheCaretIsInIsNotOfferedForImport() {
        writeShapesModule()
        configureAndCache("module Shapes {\n    area :: (float r) -> float { return r; }\n    f :: (float x) -> float {\n        return <caret>x;\n    }\n}")

        val items = myFixture.complete(CompletionType.BASIC, 2).orEmpty().filter { it.lookupString == "area" }

        assertTrue("its own names are visible without an import", items.none { describeItem(it).tailText.orEmpty().contains("(import)") })
    }

    fun testTheCompilersStandardModulesAreOfferedOnceTheirCopiesHaveBeenCompiled() {
        val modules = System.getProperty("bwslc.path")?.let { File(it).parentFile?.parentFile?.resolve("modules") }
            ?.takeIf { it.resolve("math.bwsl").isFile } ?: return
        val originalRoot = BwslStdlibSources.cacheRoot
        val originalProbeRoot = BwslStdlibSources.probeRoot
        val cache = Files.createTempDirectory("bwsl_autoimport_stdlib_").toFile()
        try {
            val directory = File(cache, "master/modules").also { it.mkdirs() }
            modules.resolve("math.bwsl").copyTo(File(directory, "math.bwsl"))
            BwslStdlibSources.cacheRoot = cache.toPath()
            BwslStdlibSources.probeRoot = File(cache.parentFile, "bwsl_autoimport_probes_" + cache.name).toPath()
            val compiled = BwslProjectIndex.getInstance(project).refreshNow(includeStandardModules = true)
            assertEquals("the probe for Math is compiled", 1, compiled)
            configureAndCache(body)

            chooseItem("inverse_lerp")

            myFixture.checkResult("module M {\n    import Math\n    f :: (float x) -> float {\n        return Math::inverse_lerp(<caret>)x;\n    }\n}")
        } finally {
            BwslStdlibSources.cacheRoot = originalRoot
            BwslStdlibSources.probeRoot.toFile().deleteRecursively()
            BwslStdlibSources.probeRoot = originalProbeRoot
            cache.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        }
    }

    fun testNothingIsOfferedForImportWhenNoModuleHasBeenCompiled() {
        configureAndCache(body)

        val items = myFixture.complete(CompletionType.BASIC, 2).orEmpty()

        assertTrue(items.none { describeItem(it).tailText.orEmpty().contains("(import)") })
    }
}
