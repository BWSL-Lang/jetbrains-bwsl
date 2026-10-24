package com.bwsl.plugin

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/** The command line and output of a build started from the IDE: what was run, what it printed and how it ended. */
class BwslBuildOutputTest : BasePlatformTestCase() {

    private lateinit var directory: File

    private val compiler: String get() = System.getProperty("bwslc.path") ?: error("System property 'bwslc.path' is not set")

    override fun setUp() {
        super.setUp()
        directory = Files.createTempDirectory("bwsl_build_test_").toFile()
    }

    override fun tearDown() {
        try {
            directory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private val pipeline = "pipeline P {\n    attributes { position: float4 }\n    pass \"Main\" {\n        use attributes { position }\n" +
        "        outputs { c: float4 }\n        vertex { output.pos = attributes.position; }\n        fragment { output.c = float4(1.0); }\n    }\n}\n"

    private fun writeSource(text: String): File = File(directory, "Build.bwsl").also { it.writeText(text) }

    fun testAnArgumentWithASpaceOrAQuoteIsQuotedSoTheLineCanBePasted() {
        val line = formatCommandLine(listOf("C:\\Program Files\\bwslc.exe", "a.bwsl", "-modules", "", "say \"hi\""))

        assertEquals("\"C:\\Program Files\\bwslc.exe\" a.bwsl -modules \"\" \"say \\\"hi\\\"\"", line)
    }

    fun testASuccessfulBuildKeepsTheCommandTheOutputAndTheExitCode() {
        val source = writeSource(pipeline)
        val command = buildCompileCommand(compiler, source.path, BwslOutputFormat.SPIRV_ONLY, emitDebugNames = true, modulePaths = emptyList())

        val run = runCompilerProcess(command, directory.path)

        assertTrue("it succeeded, got: ${run.output}", run.isSuccess)
        assertTrue("-debug-names is in the command that ran", "-debug-names" in run.command)
        assertTrue(run.output.contains("compiled"))
        val description = describeRun(run)
        assertTrue(description, description.contains("Working directory: ${directory.path}"))
        assertTrue(description, description.contains("-debug-names"))
        assertTrue(description, description.contains("Finished with exit code 0"))
    }

    fun testAFailedBuildKeepsTheWholeOutput() {
        val source = writeSource("pipeline P {\n    pass \"Main\" {\n        vertex { float x = ; }\n    }\n}\n")
        val command = buildCompileCommand(compiler, source.path, BwslOutputFormat.SPIRV_ONLY, emitDebugNames = false, modulePaths = emptyList())

        val run = runCompilerProcess(command, directory.path)

        assertFalse(run.isSuccess)
        assertNotNull(run.exitCode)
        assertTrue("the compiler output is kept", run.output.isNotBlank())
        assertTrue("summary: ${summariseFailure(run)}", run.output.contains(summariseFailure(run)))
        assertTrue(describeRun(run).contains("Finished with exit code ${run.exitCode}"))
    }

    fun testACompilerThatCannotBeStartedSaysSo() {
        val run = runCompilerProcess(listOf(File(directory, "no-such-bwslc.exe").path, "a.bwsl"), directory.path)

        assertNull(run.exitCode)
        assertNotNull(run.startFailure)
        assertTrue(summariseFailure(run).startsWith("Failed to start the compiler"))
        assertTrue(describeRun(run).contains("Could not start"))
    }

    fun testABuildThatTakesTooLongIsStoppedAndSaysSo() {
        val sleeper = if (System.getProperty("os.name").lowercase().contains("win")) listOf("ping", "-n", "30", "127.0.0.1") else listOf("sleep", "30")

        val run = runCompilerProcess(sleeper, directory.path, timeoutSeconds = 1)

        assertTrue(run.didTimeOut)
        assertEquals("The compiler did not finish in time.", summariseFailure(run))
        assertTrue(describeRun(run).contains("Timed out"))
    }

    fun testTheConsoleHoldsEveryBuildInOrder() {
        val first = CompilerRun(listOf("bwslc", "A.bwsl"), directory.path, "all good\n", 0, 12)
        val second = CompilerRun(listOf("bwslc", "B file.bwsl", "-debug-names"), directory.path, "Error: broken\n", 1, 34)
        val build = project.service<BwslBuildConsole>()

        build.show(first, reveal = false)
        build.show(second, reveal = false)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val console = build.console as ConsoleViewImpl
        console.component
        console.flushDeferredText()
        val text = try { console.text } finally { com.intellij.openapi.util.Disposer.dispose(console) }

        assertTrue(text, text.indexOf("bwslc A.bwsl") in 0 until text.indexOf("bwslc \"B file.bwsl\" -debug-names"))
        assertTrue(text, text.contains("all good"))
        assertTrue(text, text.contains("Error: broken"))
        assertTrue(text, text.contains("Finished with exit code 1 in 34 ms"))
    }
    private val twoErrors = "pipeline P {\n    helper :: (float x) -> float { return x * scale; }\n    attributes { position: float4 }\n    pass \"Main\" {\n        use attributes { position }\n        outputs { color: float4 }\n        vertex { output.position = attributes.position; }\n        fragment { output.color = float4(helpr(1.0)); }\n    }\n}\n"

    fun testEachErrorsFileIsALocationWithItsLineAndColumn() {
        val source = writeSource(twoErrors)
        val run = runCompilerProcess(buildCompileCommand(compiler, source.path, BwslOutputFormat.SPIRV_ONLY, false, emptyList()), directory.path)

        val locations = collectOutputLocations(run.output)

        assertEquals(listOf(2 to 47, 8 to 42), locations.map { it.line to it.column })
        for (location in locations) {
            assertEquals(source.path, location.path)
            assertEquals("the range is the path in the output", source.path, run.output.substring(location.range))
        }
    }

    fun testAFileNameWithoutALineBeforeItIsNoLocation() {
        assertEquals(emptyList<OutputLocation>(), collectOutputLocations("  | File: C:/a.bwsl\nDone\n"))
    }

    fun testTheConsoleLinksEachErrorToItsLineInTheFile() {
        val source = writeSource(twoErrors)
        val run = runCompilerProcess(buildCompileCommand(compiler, source.path, BwslOutputFormat.SPIRV_ONLY, false, emptyList()), directory.path)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(source)
        // A console of its own: the project's build console is shared by the tests, and another one disposes it.
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console as ConsoleViewImpl
        console.component

        printRun(console, project, run)
        console.flushDeferredText()
        try {
            val text = console.text
            val hyperlinks = console.getHyperlinks()!!
            val links = hyperlinks.getAllHyperlinks(0, text.length).map { text.substring(it.startOffset, it.endOffset) to EditorHyperlinkSupport.getHyperlinkInfo(it) }

            assertEquals(listOf(source.path, source.path), links.map { it.first })
            val first = links.first().second as OpenFileHyperlinkInfo
            assertEquals("the link opens the file at the error", twoErrors.indexOf("scale;"), first.descriptor!!.offset)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
    }
}
