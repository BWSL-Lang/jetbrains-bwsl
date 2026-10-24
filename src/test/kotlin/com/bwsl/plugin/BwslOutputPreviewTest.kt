package com.bwsl.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/** The compiled-output preview: what it compiles, which tab each output goes to, and that a failure keeps the last output. */
class BwslOutputPreviewTest : BasePlatformTestCase() {

    private val compiler: String get() = System.getProperty("bwslc.path") ?: error("System property 'bwslc.path' is not set")

    private val shader = """
        pipeline P {
            attributes { position: float4  uv: float2 }
            pass "Main" {
                use attributes { position, uv }
                outputs { color: float4 }
                vertex {
                    output.position = attributes.position;
                    output.uv = attributes.uv;
                }
                fragment { output.color = float4(input.uv, 0.0, 1.0); }
            }
        }
    """.trimIndent()

    private fun sourcePath(): String = Files.createTempDirectory("bwsl_preview_test_").toFile().resolve("Shader.bwsl").path

    fun testTheCommandCompilesTheEditorsTextForEveryTargetWithTheBindings() {
        val command = buildPreviewCommand("bwslc", "C:/p/Shader.bwsl", "C:/out", emitDebugNames = true, modulePaths = listOf("C:/mods"))

        assertEquals(
            listOf("bwslc", "--stdin", "--source-file", "C:/p/Shader.bwsl", "-o", "C:/out", "-all", "-spv", "-bindings", "-debug-names", "-modules", "C:/mods"),
            command
        )
    }

    fun testEachFileTheCompilerWritesGoesToItsTab() {
        assertEquals(BwslOutputTab.SPIRV, classifyOutputFile("d.vert.spv"))
        assertEquals(BwslOutputTab.GLSL, classifyOutputFile("d.glsl.frag"))
        assertEquals(BwslOutputTab.GLES, classifyOutputFile("d_Second_Pass.gles.vert"))
        assertEquals(BwslOutputTab.BINDINGS, classifyOutputFile("d.gles.frag.json"))
        assertEquals(BwslOutputTab.BINDINGS, classifyOutputFile("d_First.bindings.json"))
        assertEquals(BwslOutputTab.HLSL, classifyOutputFile("d.frag.hlsl"))
        assertEquals(BwslOutputTab.METAL, classifyOutputFile("d.vert.metal"))
        assertNull(classifyOutputFile("notes.txt"))
    }

    fun testACompileFillsEveryTabFromTheCompiler() {
        val compile = compilePreview(compiler, sourcePath(), shader, emptyList(), emitDebugNames = false, disassembler = null)

        assertNull(compile.failure)
        assertEquals(BwslOutputTab.entries.toSet(), compile.texts.keys)
        val glsl = compile.texts.getValue(BwslOutputTab.GLSL)
        assertTrue(glsl, glsl.indexOf("// Shader.glsl.vert") in 0 until glsl.indexOf("// Shader.glsl.frag"))
        assertTrue(glsl, glsl.contains("#version 450"))
        assertTrue(compile.texts.getValue(BwslOutputTab.HLSL).contains("Shader.frag.hlsl"))
        assertTrue(compile.texts.getValue(BwslOutputTab.METAL).contains("Shader.vert.metal"))
        assertTrue(compile.texts.getValue(BwslOutputTab.BINDINGS).contains("Shader.bindings.json"))
        assertTrue("the AST is pretty-printed", compile.texts.getValue(BwslOutputTab.AST).contains("\n  \"schema\""))
        assertTrue("no disassembler: the SPIR-V is described", compile.texts.getValue(BwslOutputTab.SPIRV).contains("Shader.vert.spv:"))
    }

    fun testTheSpirvTabShowsTheDisassemblyWhenSpirvDisIsThere() {
        val disassembler = findSpirvDisassembler() ?: return
        val compile = compilePreview(compiler, sourcePath(), shader, emptyList(), emitDebugNames = true, disassembler = disassembler)

        val spirv = compile.texts.getValue(BwslOutputTab.SPIRV)
        assertTrue(spirv, spirv.contains("OpEntryPoint"))
        assertTrue("the debug names are in it", spirv.contains("OpName"))
    }

    fun testAFailedCompileGivesNoOutputButSaysWhyAndTheLastOutputStays() {
        val good = compilePreview(compiler, sourcePath(), shader, emptyList(), emitDebugNames = false, disassembler = null)
        val broken = compilePreview(compiler, sourcePath(), shader.replace("output.uv = attributes.uv;", "output.uv = ;"), emptyList(), false, null)

        assertNotNull(broken.failure)
        assertFalse(broken.texts.containsKey(BwslOutputTab.GLSL))
        val shown = mergePreviewTexts(mergePreviewTexts(emptyMap(), good), broken)
        assertEquals(good.texts.getValue(BwslOutputTab.GLSL), shown.getValue(BwslOutputTab.GLSL))
    }

    fun testAModuleFileSaysItHasNothingToCompile() {
        val compile = compilePreview(compiler, sourcePath(), "module M {\n    f :: () -> float { return 1.0; }\n}\n", emptyList(), false, null)

        assertNull(compile.failure)
        assertTrue(compile.texts.getValue(BwslOutputTab.GLSL).contains("no pipeline"))
        assertTrue("the AST is still there", compile.texts.getValue(BwslOutputTab.AST).contains("\"modules\""))
    }

    fun testABwslFileOpensInAnEditorWithThePreviewBesideIt() {
        val file = myFixture.configureByText("Shader.bwsl", shader).virtualFile
        val provider = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList.filterIsInstance<BwslSplitEditorProvider>().single()

        assertTrue(provider.accept(project, file))
        assertFalse(provider.accept(project, myFixture.configureByText("notes.txt", "text").virtualFile))
        assertEquals(FileEditorPolicy.HIDE_DEFAULT_EDITOR, provider.policy)
        val editor = provider.createEditor(project, file)
        try {
            assertTrue("got $editor", editor is TextEditorWithPreview)
            assertEquals(shader, (editor as TextEditorWithPreview).editor.document.text)
            // The split builds its UI on the next event; until then it has no layout.
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("the output is hidden until asked for", TextEditorWithPreview.Layout.SHOW_EDITOR, editor.getLayout())
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testWithNoCompilerTheStatusLinksToTheSettingsAndAfterwardsCompiles() {
        val settings = BwslSettings.getInstance()
        val original = settings.compilerPath
        val file = myFixture.configureByText("Shader.bwsl", shader).virtualFile
        val preview = BwslOutputPreviewEditor(project, file)
        try {
            settings.compilerPath = ""
            if (resolveCompilerPath() != null) return // a downloaded compiler stands in for an empty path on this machine
            preview.compileNow()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("No BWSL compiler is configured.", preview.shownStatus)
            assertTrue(preview.isSettingsLinkShown)

            settings.compilerPath = compiler
            preview.compileNow()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(preview.shownStatus, preview.shownStatus.contains("Compiled in"))
            assertFalse("the link goes once a compiler is there", preview.isSettingsLinkShown)
            assertTrue(preview.findShownText(BwslOutputTab.GLSL).contains("#version 450"))
        } finally {
            settings.compilerPath = original
            Disposer.dispose(preview)
        }
    }

    fun testApplyingTheSettingsTellsTheirListeners() {
        var told = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(BwslSettingsListener.TOPIC, BwslSettingsListener { told++ })
        val configurable = BwslSettingsConfigurable()
        configurable.createComponent()
        try {
            configurable.apply()
        } finally {
            configurable.disposeUIResources()
        }

        assertEquals(1, told)
    }

    fun testEachTabIsHighlightedAsAFileOfItsKindWouldBe() {
        assertEquals(
            listOf("preview.spvasm", "preview.glsl", "preview.glsl", "preview.hlsl", "preview.metal", "preview.json", "preview.json"),
            BwslOutputTab.entries.map { it.highlightingFileName }
        )

        // JSON is built in, so the JSON tabs show its tokens; the other kinds depend on what is installed.
        val highlighter = createOutputHighlighter(project, BwslOutputTab.AST)
        highlighter.setText("{\"schema\": 3}")
        val languages = generateSequence(highlighter.createIterator(0)) { it.advance(); if (it.atEnd()) null else it }
            .map { it.tokenType.language.id }
            .toSet()
        assertTrue("got $languages", "JSON" in languages)
    }

    override fun tearDown() {
        try {
            File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.name.startsWith("bwsl_preview_test_") }?.forEach { it.deleteRecursively() }
        } finally {
            super.tearDown()
        }
    }
}
