package com.bwsl.plugin

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File
import java.nio.file.Files

/**
 * The project index: every BWSL file in the project and in the module paths is compiled, so Find
 * Usages and Rename know about files that were never opened. The module directory here stands in for
 * the rest of the project - its files are on disk, outside the fixture's in-memory project, as a
 * shared `-modules` directory is.
 */
class BwslProjectIndexTest : BwslAstFixtureTestCase() {

    private lateinit var originalCompilerPath: String
    private lateinit var originalModulePaths: MutableList<String>
    private lateinit var modulesDirectory: File

    override fun setUp() {
        super.setUp()
        val settings = BwslSettings.getInstance()
        originalCompilerPath = settings.compilerPath
        originalModulePaths = settings.modulePaths
        modulesDirectory = Files.createTempDirectory("bwsl_index_test_").toFile()
        settings.compilerPath = System.getProperty("bwslc.path")
            ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")
        settings.modulePaths = mutableListOf(modulesDirectory.path)
    }

    override fun tearDown() {
        try {
            BwslSettings.getInstance().compilerPath = originalCompilerPath
            BwslSettings.getInstance().modulePaths = originalModulePaths
            modulesDirectory.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun writeModuleFile(name: String, text: String): VirtualFile {
        val file = modulesDirectory.resolve("$name.bwsl")
        file.writeText(text)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("the file system does not see ${file.path}")
    }

    /** Saves [text] as the file's content, as the IDE does when the user saves an edit. */
    private fun saveText(file: VirtualFile, text: String) {
        WriteAction.runAndWait<RuntimeException> { VfsUtil.saveText(file, text) }
    }

    private fun writeCommonAndUser(): Pair<VirtualFile, VirtualFile> {
        val common = writeModuleFile(
            "Common",
            """
            module Common {
                helper :: () -> float { return 1.0; }
            }
            """.trimIndent() + "\n"
        )
        val user = writeModuleFile(
            "User",
            """
            module User {
                import Common
                run :: () -> float { return Common::helper(); }
            }
            """.trimIndent() + "\n"
        )
        return common to user
    }

    private fun collectNamesToCompile(): Set<String> =
        BwslProjectIndex.getInstance(project).collectFilesToCompile().map { it.name }.toSet()

    /** Renames `helper` to `assist` from its declaration in [common], opened in the editor. */
    private fun renameHelperIn(common: VirtualFile): Throwable? {
        myFixture.configureFromExistingVirtualFile(common)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("helper") + 2)
        return runCatching { myFixture.renameElementAtCaret("assist") }.exceptionOrNull()
    }

    fun testRefreshCompilesEveryFileInTheModulePathsAndRecordsTheFilesEachWasBuiltFrom() {
        val (common, user) = writeCommonAndUser()
        assertEquals(setOf("Common.bwsl", "User.bwsl"), collectNamesToCompile())

        val compiled = BwslProjectIndex.getInstance(project).refreshNow()

        assertEquals(2, compiled)
        assertTrue("Common must have an AST", BwslAstCache.findRoot(common.path) != null)
        assertTrue("User must have an AST", BwslAstCache.findRoot(user.path) != null)
        val userInputs = BwslAstCache.findCompiledInputs(user.path).orEmpty().keys
        assertEquals(
            "User was built from itself and the module it imports, not from every file bwslc could have read",
            setOf(normalizePathKey(user.path), normalizePathKey(common.path)),
            userInputs
        )
        assertEquals("nothing is left to compile", emptySet<String>(), collectNamesToCompile())
        assertEquals("a second refresh has nothing to do", 0, BwslProjectIndex.getInstance(project).refreshNow())
    }

    fun testChangingAModuleMakesItsImportersStaleToo() {
        val (common, _) = writeCommonAndUser()
        BwslProjectIndex.getInstance(project).refreshNow()

        saveText(common, "module Common {\n    helper :: () -> float { return 2.0; }\n}\n")

        assertEquals("User's AST holds Common's declarations, so it is stale as well", setOf("Common.bwsl", "User.bwsl"), collectNamesToCompile())
    }

    fun testAFileThatDoesNotCompileIsNotRetriedUntilSomethingItReadsChanges() {
        val broken = writeModuleFile("Broken", "module Broken {\n    run :: () -> float { return ; }\n")
        BwslProjectIndex.getInstance(project).refreshNow()

        assertNull("no AST for a file that does not compile", BwslAstCache.findRoot(broken.path))
        assertFalse("its failure is known, so it is not compiled again", collectNamesToCompile().contains("Broken.bwsl"))

        saveText(broken, "module Broken {\n    run :: () -> float { return 1.0; }\n}\n")

        assertTrue("fixing it makes it worth compiling", collectNamesToCompile().contains("Broken.bwsl"))
    }

    fun testFindUsagesBringsTheIndexUpToDateFirstButNotWhenOnlyHighlighting() {
        val (common, _) = writeCommonAndUser()
        myFixture.configureFromExistingVirtualFile(common)
        val target = myFixture.file.findElementAt(myFixture.file.text.indexOf("helper"))!!
        val factory = BwslFindUsagesHandlerFactory()

        assertNull(factory.createFindUsagesHandler(target, true))
        assertEquals("highlighting must not compile anything", setOf("Common.bwsl", "User.bwsl"), collectNamesToCompile())

        assertNull(factory.createFindUsagesHandler(target, false))
        assertEquals("find usages waits for the index", emptySet<String>(), collectNamesToCompile())
    }

    fun testRenameChangesUsagesInAFileThatWasNeverOpened() {
        val (common, user) = writeCommonAndUser()
        BwslProjectIndex.getInstance(project).refreshNow()

        val failure = renameHelperIn(common)

        assertNull("rename should have worked, got: ${describeFailure(failure)}", failure)
        assertTrue(myFixture.file.text.contains("assist :: ()"))
        val userText = PsiManager.getInstance(project).findFile(user)!!.text
        assertTrue("the usage in User must follow, got: $userText", userText.contains("Common::assist()"))
        assertFalse(userText.contains("helper"))
    }

    fun testRenameRefusesWhileAProjectFileHasNeverBeenCompiled() {
        // A compiler that cannot be started leaves every file uncompiled.
        BwslSettings.getInstance().compilerPath = File(modulesDirectory, "no-such-bwslc.exe").path
        writeCommonAndUser()
        configureAndCache("module M {\n    hel<caret>per :: () -> float { return 1.0; }\n}\n")

        val failure = runCatching { myFixture.renameElementAtCaret("assist") }.exceptionOrNull()

        assertTrue(
            "expected the not-compiled-yet refusal, got: ${describeFailure(failure)}",
            describeFailure(failure).contains("has not been compiled yet")
        )
        assertTrue("a refused rename must leave the text alone", myFixture.file.text.contains("helper"))
    }

    fun testRenameRefusesWhileAProjectFileDoesNotCompile() {
        writeModuleFile("Broken", "module Broken {\n    run :: () -> float { return ; }\n")
        BwslProjectIndex.getInstance(project).refreshNow()
        configureAndCache("module M {\n    hel<caret>per :: () -> float { return 1.0; }\n}\n")

        val failure = runCatching { myFixture.renameElementAtCaret("assist") }.exceptionOrNull()

        val message = describeFailure(failure)
        assertTrue("expected the does-not-compile refusal, got: $message", message.contains("Broken.bwsl does not compile"))
        assertTrue("the refusal says how to get past it, got: $message", message.contains("exclude"))
        assertTrue(myFixture.file.text.contains("helper"))
    }

    fun testParseProjectConfigReadsModulePathsAndExclusions() {
        val config = parseProjectConfig("""{ "modulePaths": ["shaders/lib", "/abs/lib"], "exclude": ["scratch"] }""")

        assertEquals(listOf("shaders/lib", "/abs/lib"), config?.modulePaths)
        assertEquals(listOf("scratch"), config?.exclude)
    }

    fun testParseProjectConfigAcceptsAnEmptyObjectAndRejectsBrokenJson() {
        assertEquals(BwslProjectConfig(), parseProjectConfig("{}"))
        assertNull(parseProjectConfig("{ not json"))
    }

    fun testExclusionCoversTheEntryItselfAndEverythingBelowIt() {
        val config = BwslProjectConfig(exclude = listOf("scratch", "vendor/old.bwsl"))
        val base = "/work/project"

        assertTrue(isExcludedBy(config, base, "/work/project/scratch/a.bwsl"))
        assertTrue(isExcludedBy(config, base, "/work/project/scratch"))
        assertTrue(isExcludedBy(config, base, "/work/project/vendor/old.bwsl"))
        assertFalse(isExcludedBy(config, base, "/work/project/scratch2/a.bwsl"))
        assertFalse(isExcludedBy(config, base, "/work/project/vendor/new.bwsl"))
    }

    fun testModulePathsFromTheSettingsAreStillCollected() {
        assertTrue(collectModulePaths(project).any { normalizePathKey(it) == normalizePathKey(modulesDirectory.path) })
    }

    fun testAnAstIsCurrentForTheSavedTextItWasBuiltFromAndStaleAfterItChanges() {
        configureAndCache("module M {\n    f :: () -> float { return 1.0; }\n}\n")
        val file = myFixture.file.virtualFile
        val filesByKey = mapOf(normalizePathKey(file.path) to file)

        assertTrue(hasCurrentAst(file, filesByKey))

        saveText(file, "module M {\n    f :: () -> float { return 2.0; }\n}\n")

        assertFalse(hasCurrentAst(file, filesByKey))
    }
}
