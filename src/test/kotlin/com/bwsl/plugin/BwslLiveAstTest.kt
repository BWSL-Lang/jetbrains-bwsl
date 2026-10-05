package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import java.io.File
import java.nio.file.Files

/** Completion sees code typed since the last save: the editor's unsaved text is compiled in its own right. */
class BwslLiveAstTest : BwslAstFixtureTestCase() {

    private val saved = "module M {\n    f :: (float a) -> float {\n        return a;\n    }\n}"
    private val edited = "module M {\n    f :: (float a) -> float {\n        float typed = a * 2.0;\n        return a;\n    }\n}"

    private fun editorTextIs(text: String) {
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun annotateEditorText() {
        val annotator = BwslAstAnnotator()
        val info = annotator.collectInformation(myFixture.file)!!
        annotator.doAnnotate(info)
    }

    fun testTheEditorsTextIsCompiledWhenItDiffersFromTheSavedFile() {
        myFixture.configureByText("live.bwsl", saved)
        val path = myFixture.file.virtualFile.path
        BwslcAstHelper.parseAndCache(saved, path)
        editorTextIs(edited)

        annotateEditorText()

        val names = collectVisibleLocalsAt(
            BwslAstCache.findRootForCompletion(path)!!, BwslAstCache.findRawRootForCompletion(path)!!, 4, 9
        ).map { it.name }
        assertTrue("a local typed since the save is in scope, got: $names", "typed" in names)
    }

    fun testTheSavedAstIsLeftAloneForTheIndexAndRename() {
        myFixture.configureByText("live.bwsl", saved)
        val path = myFixture.file.virtualFile.path
        BwslcAstHelper.parseAndCache(saved, path)
        val savedRoot = BwslAstCache.findRoot(path)
        val savedInputs = BwslAstCache.findCompiledInputs(path)
        editorTextIs(edited)

        annotateEditorText()

        assertSame(savedRoot, BwslAstCache.findRoot(path))
        assertEquals(savedInputs, BwslAstCache.findCompiledInputs(path))
        assertNotSame(savedRoot, BwslAstCache.findRootForCompletion(path))
    }

    fun testTextThatDoesNotParseKeepsTheLastLiveAst() {
        myFixture.configureByText("live.bwsl", saved)
        val path = myFixture.file.virtualFile.path
        BwslcAstHelper.parseAndCache(saved, path)
        editorTextIs(edited)
        annotateEditorText()
        val live = BwslAstCache.findRootForCompletion(path)

        editorTextIs(edited.replace("return a;", "return a +"))
        annotateEditorText()

        assertSame(live, BwslAstCache.findRootForCompletion(path))
    }

    fun testSavingTheTextDropsTheLiveAst() {
        myFixture.configureByText("live.bwsl", saved)
        val path = myFixture.file.virtualFile.path
        BwslcAstHelper.parseAndCache(saved, path)
        editorTextIs(edited)
        annotateEditorText()
        assertNotSame(BwslAstCache.findRoot(path), BwslAstCache.findRootForCompletion(path))

        // The file on disk now holds what the editor does.
        BwslAstCache.clearLive(path)

        assertSame(BwslAstCache.findRoot(path), BwslAstCache.findRootForCompletion(path))
    }

    fun testModulesBesideTheFileAreFoundForUnsavedText() {
        val directory = Files.createTempDirectory("bwsl_live_test_").toFile()
        try {
            File(directory, "Lib.bwsl").writeText("module Lib {\n    helper :: (float v) -> float { return v; }\n}\n")
            val app = File(directory, "App.bwsl")
            app.writeText("module App {\n    import Lib\n    f :: (float x) -> float { return Lib::helper(x); }\n}\n")
            myFixture.configureFromExistingVirtualFile(LocalFileSystem.getInstance().refreshAndFindFileByIoFile(app)!!)
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// edited\n") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            annotateEditorText()

            val root = BwslAstCache.findRootForCompletion(myFixture.file.virtualFile.path)
            assertTrue("the imported module is in the live AST", root!!.modules.any { it.name == "Lib" })
        } finally {
            directory.deleteRecursively()
        }
    }

    fun testCompletionOffersALocalDeclaredSinceTheSave() {
        myFixture.configureByText("live.bwsl", saved)
        val path = myFixture.file.virtualFile.path
        BwslcAstHelper.parseAndCache(saved, path)
        // Typed since the save: a new declaration (compiled live), then the start of the next statement.
        editorTextIs(edited)
        annotateEditorText()
        editorTextIs(edited.replace("return a;", "return "))
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("return ") + "return ".length)

        val strings = myFixture.completeBasic().map { it.lookupString }

        assertTrue("'typed' should be offered, got: $strings", "typed" in strings)
        assertTrue("so should the parameter, got: $strings", "a" in strings)
    }
}
