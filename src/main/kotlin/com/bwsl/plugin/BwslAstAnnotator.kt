package com.bwsl.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
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
 * What a compile found out about the file: the declarations that shadow another, positioned in the
 * text bwslc compiled, whose hash is [compiledTextHash] - they are only shown while the editor still
 * holds that text.
 */
data class AstAnnotationResult(val shadowing: List<ShadowingDeclaration>, val compiledTextHash: Int?)

/**
 * Compiles the edited file with bwslc to keep its AST cached (see [compileAndCache]) and, from that
 * AST, warns about declarations that shadow another. While the editor holds unsaved changes it compiles
 * that text instead and keeps the result only for completion (see [compileAndCacheLive]).
 */
class BwslAstAnnotator : ExternalAnnotator<AstCollectedInfo, AstAnnotationResult>() {

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

    override fun doAnnotate(info: AstCollectedInfo): AstAnnotationResult? =
        try {
            if (info.hasUnsavedChanges) {
                // Unsaved changes: the saved file's AST stays as it is (the index and rename read that), and
                // the AST of the editor's text is kept for completion. It is also exactly what the editor holds,
                // so the shadowing warnings can be shown from it.
                if (compileAndCacheLive(info.compilerPath, info.filePath, info.editorText, info.modulePaths)) {
                    val root = BwslAstCache.findRootForCompletion(info.filePath)
                    val rawJson = BwslAstCache.findRawRootForCompletion(info.filePath)
                    if (root == null || rawJson == null) null
                    else AstAnnotationResult(collectShadowingDeclarations(root, rawJson), BwslAstCache.hashText(info.editorText))
                } else {
                    null
                }
            } else {
                BwslAstCache.clearLive(info.filePath)
                annotateSaved(info)
            }
        } catch (e: Exception) {
            log.warn("bwslc -ast-json failed for ${info.filePath}", e)
            null
        }

    private fun annotateSaved(info: AstCollectedInfo): AstAnnotationResult? {
        return if (compileAndCache(info.compilerPath, info.filePath, info.modulePaths, info.candidateInputs)) {
            val root = BwslAstCache.findRoot(info.filePath)
            val rawJson = BwslAstCache.findRawRoot(info.filePath)
            if (root == null || rawJson == null) null
            else AstAnnotationResult(
                collectShadowingDeclarations(root, rawJson),
                info.candidateInputs[normalizePathKey(info.filePath)]
            )
        } else {
            null
        }
    }

    override fun apply(file: PsiFile, result: AstAnnotationResult, holder: AnnotationHolder) {
        val text = file.text
        if (BwslAstCache.hashText(text) != result.compiledTextHash) return
        for ((range, message) in buildShadowingWarnings(text, result.shadowing)) {
            holder.newAnnotation(HighlightSeverity.WEAK_WARNING, message).range(range).create()
        }
    }
}

/** The warning for each shadowing declaration: the range of its name in [text], and what to say. */
internal fun buildShadowingWarnings(text: String, shadowing: List<ShadowingDeclaration>): List<Pair<TextRange, String>> {
    val positions = SourcePositions(text)
    return shadowing.mapNotNull { declaration ->
        val start = positions.toOffset(declaration.line, declaration.column) ?: return@mapNotNull null
        TextRange(start, start + declaration.name.length) to declaration.describe()
    }
}
