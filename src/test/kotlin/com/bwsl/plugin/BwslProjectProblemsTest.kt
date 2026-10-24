package com.bwsl.plugin

import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import java.nio.file.Files

/** The compiler's errors in every indexed file, opened or not, in the Problems tool window's Project Errors tab. */
class BwslProjectProblemsTest : BwslAstFixtureTestCase() {

    private lateinit var originalModulePaths: MutableList<String>
    private lateinit var modulesDirectory: File

    override fun setUp() {
        super.setUp()
        val settings = BwslSettings.getInstance()
        originalModulePaths = settings.modulePaths
        modulesDirectory = Files.createTempDirectory("bwsl_problems_test_").toFile()
        settings.modulePaths = mutableListOf(modulesDirectory.path)
    }

    override fun tearDown() {
        try {
            BwslSettings.getInstance().modulePaths = originalModulePaths
            modulesDirectory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun writeModuleFile(name: String, text: String) = modulesDirectory.resolve("$name.bwsl").also { it.writeText(text) }
        .let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) ?: error("no ${it.path}") }

    fun testTheBatchReportsErrorsOfEveryFileAndNotItsNotes() {
        val json = """
            {"batch": true, "files": [
              {"file": "C:\\p\\A.bwsl", "diagnostics": [
                {"severity": "error", "message": "Unknown identifier 'scale'", "file": "C:\\p\\A.bwsl", "line": 2, "column": 47},
                {"severity": "note", "message": "Variable 'x' shadows parameter", "file": "C:\\p\\A.bwsl", "line": 3, "column": 5}
              ]},
              {"file": "C:\\p\\B.bwsl", "diagnostics": [
                {"severity": "error", "message": "Unknown function 'f'", "line": 4, "column": 1}
              ]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(BatchDiagnostic("C:\\p\\A.bwsl", 2, 47, "Unknown identifier 'scale'"), BatchDiagnostic("C:\\p\\B.bwsl", 4, 1, "Unknown function 'f'")),
            parseBatchErrors(json)
        )
        assertNull("not a report", parseBatchErrors("Diagnostics: 1 error(s)"))
        // A manifest of one file: the compiler prints the report of that one compile.
        assertEquals(
            listOf(BatchDiagnostic("C:/p/A.bwsl", 2, 42, "Unknown identifier 'scale'")),
            parseBatchErrors("""{"file": "C:/p/A.bwsl", "diagnostics": [{"severity": "error", "message": "Unknown identifier 'scale'", "line": 2, "column": 42}]}""")
        )
    }

    fun testAnErrorInAFileThatIsNotOpenIsAProjectProblemAtItsLine() {
        writeModuleFile("Good", "module Good {\n    f :: (float x) -> float { return x; }\n}\n")
        val broken = writeModuleFile("Broken", "module Broken {\n    import Good\n    g :: (float x) -> float { return Good::f(x) * scale; }\n}\n")

        val problems = BwslProjectProblems.getInstance(project).refreshNow()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val problem = problems.single()
        assertEquals(broken, problem.file)
        assertEquals("zero-based line 2 is the third", 2, problem.line)
        assertTrue(problem.text, problem.text.contains("scale"))
        assertTrue("the Problems view has it", ProblemsCollector.getInstance(project).getFileProblems(broken).contains(problem))
    }

    fun testAFixedErrorLeavesTheProblemsView() {
        val broken = writeModuleFile("Broken", "module Broken {\n    g :: (float x) -> float { return x * scale; }\n}\n")
        BwslProjectProblems.getInstance(project).refreshNow()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(1, ProblemsCollector.getInstance(project).getFileProblemCount(broken))

        modulesDirectory.resolve("Broken.bwsl").writeText("module Broken {\n    g :: (float x) -> float { return x; }\n}\n")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(modulesDirectory.resolve("Broken.bwsl"))!!.refresh(false, false)
        val problems = BwslProjectProblems.getInstance(project).refreshNow()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(emptySet<BwslCompilerProblem>(), problems)
        assertEquals(0, ProblemsCollector.getInstance(project).getFileProblemCount(broken))
    }
}
