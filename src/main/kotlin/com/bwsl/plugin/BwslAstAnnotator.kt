package com.bwsl.plugin

import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile

private val log = logger<BwslAstAnnotator>()

/**
 * What the compile of one file needs, taken when it is collected: [candidateInputs] is the saved text
 * of every file bwslc could read (see [snapshotCandidateInputs]), which is what it is about to compile.
 */
data class AstCollectedInfo(
    val compilerPath: String,
    val filePath: String,
    val modulePaths: List<String>,
    val candidateInputs: Map<String, Int>,
    /** The editor's text, compiled in place of the file when it differs from the saved one. */
    val editorText: String,
    /** Whether the editor holds changes that are not saved. */
    val hasUnsavedChanges: Boolean
)

/**
 * Compiles the edited file with bwslc to keep its AST cached (see [compileAndCache]). While the editor holds
 * unsaved changes it compiles that text instead and keeps the result only for completion (see
 * [compileAndCacheLive]). It shows nothing itself: the compiler's own diagnostics are shown by
 * [BwslExternalAnnotator].
 */
class BwslAstAnnotator : ExternalAnnotator<AstCollectedInfo, Unit>() {

    override fun collectInformation(file: PsiFile): AstCollectedInfo? {
        val virtualFile = file.virtualFile ?: return null
        val compilerPath = resolveCompilerPath()
        if (compilerPath == null) {
            log.warn("bwslc compiler path is not set; skipping AST collection for ${virtualFile.path}")
            return null
        }
        val modulePaths = collectModulePaths(file.project)
        val inputs = snapshotCandidateInputs(virtualFile, modulePaths)
        val text = file.text
        val hasUnsavedChanges = FileDocumentManager.getInstance().isFileModified(virtualFile) ||
            inputs[normalizePathKey(virtualFile.path)] != BwslAstCache.hashText(text)
        return AstCollectedInfo(compilerPath, virtualFile.path, modulePaths, inputs, text, hasUnsavedChanges)
    }

    override fun doAnnotate(info: AstCollectedInfo): Unit? {
        try {
            if (info.hasUnsavedChanges) {
                // Unsaved changes: the saved file's AST stays as it is (the index and rename read that), and
                // the AST of the editor's text is kept for completion.
                compileAndCacheLive(info.compilerPath, info.filePath, info.editorText, info.modulePaths)
            } else {
                BwslAstCache.clearLive(info.filePath)
                compileAndCache(info.compilerPath, info.filePath, info.modulePaths, info.candidateInputs)
            }
        } catch (e: Exception) {
            log.warn("bwslc -ast-json failed for ${info.filePath}", e)
        }
        return null
    }
}
