package com.bwsl.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.diagnostic.logger
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
    val candidateInputs: Map<String, Int>
)

class BwslAstAnnotator : ExternalAnnotator<AstCollectedInfo, Boolean>() {

    override fun collectInformation(file: PsiFile): AstCollectedInfo? {
        val virtualFile = file.virtualFile ?: return null
        val compilerPath = resolveCompilerPath()
        if (compilerPath == null) {
            log.warn("bwslc compiler path is not set; skipping AST collection for ${virtualFile.path}")
            return null
        }
        val modulePaths = collectModulePaths(file.project)
        return AstCollectedInfo(compilerPath, virtualFile.path, modulePaths, snapshotCandidateInputs(virtualFile, modulePaths))
    }

    override fun doAnnotate(info: AstCollectedInfo): Boolean? =
        try {
            compileAndCache(info.compilerPath, info.filePath, info.modulePaths, info.candidateInputs).takeIf { it }
        } catch (e: Exception) {
            log.warn("bwslc -ast-json failed for ${info.filePath}", e)
            null
        }

    override fun apply(file: PsiFile, result: Boolean, holder: AnnotationHolder) {
        // AST is stored in BwslAstCache; no annotations to apply here
    }
}
