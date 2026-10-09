package com.bwsl.plugin

import com.google.gson.annotations.SerializedName
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import java.nio.file.Files

data class Diagnostic(
    val severity: String = "error",
    val message: String = "",
    val line: Int? = null,
    val column: Int? = null,
    @SerializedName("endLine")   val endLine: Int? = null,
    @SerializedName("endColumn") val endColumn: Int? = null,
    val code: String? = null
)

data class CompilerOutput(
    val success: Boolean = true,
    val diagnostics: List<Diagnostic> = emptyList()
)

/** The editor text to check, and the directories bwslc searches for the modules it imports. */
data class DiagnosticsRequest(
    val fileContent: String,
    val modulePaths: List<String> = emptyList(),
    /** The file the text belongs to, so the modules beside it are found; null for text with no file. */
    val filePath: String? = null
)

class BwslExternalAnnotator : ExternalAnnotator<DiagnosticsRequest, List<Diagnostic>>() {

    override fun collectInformation(file: PsiFile): DiagnosticsRequest? {
        if (resolveCompilerPath() == null) return null
        return DiagnosticsRequest(file.text, collectModulePaths(file.project), file.virtualFile?.takeIf { it.isInLocalFileSystem }?.path)
    }

    override fun doAnnotate(request: DiagnosticsRequest): List<Diagnostic> {
        val compilerPath = resolveCompilerPath() ?: return emptyList()
        request.filePath?.let { path ->
            return try {
                collectDiagnostics(compilerPath, path, request.modulePaths, stdinText = request.fileContent)
            } catch (_: Exception) {
                emptyList()
            }
        }
        val tempFile = Files.createTempFile("bwsl_", ".bwsl").toFile()
        try {
            tempFile.writeText(request.fileContent)
            // collectDiagnostics passes -check: diagnostics only. Without it bwslc also writes
            // <stem>.<stage>.spv for every pass into the working directory, and nothing here would
            // ever delete them.
            return collectDiagnostics(compilerPath, tempFile.absolutePath, request.modulePaths)
        } catch (_: Exception) {
            return emptyList()
        } finally {
            tempFile.delete()
        }
    }

    override fun apply(file: PsiFile, diagnostics: List<Diagnostic>, holder: AnnotationHolder) {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        for (diag in diagnostics) {
            val range = diag.toTextRange(document)
            val severity = when (diag.severity.lowercase()) {
                "error"   -> HighlightSeverity.ERROR
                "warning" -> HighlightSeverity.WARNING
                else      -> HighlightSeverity.WEAK_WARNING
            }
            holder.newAnnotation(severity, diag.message)
                .range(range)
                .create()
        }
    }
}

private fun Diagnostic.toTextRange(document: Document): TextRange {
    if (line == null) return TextRange(0, 0)
    val lineIdx = (line - 1).coerceIn(0, document.lineCount - 1)
    val lineStart = document.getLineStartOffset(lineIdx)
    val lineEnd = document.getLineEndOffset(lineIdx)
    val startCol = ((column ?: 1) - 1).coerceAtLeast(0)
    val start = (lineStart + startCol).coerceAtMost(lineEnd)

    if (endLine != null && endColumn != null) {
        val endLineIdx = (endLine - 1).coerceIn(0, document.lineCount - 1)
        val endLineStart = document.getLineStartOffset(endLineIdx)
        val endLineEnd = document.getLineEndOffset(endLineIdx)
        val end = (endLineStart + (endColumn - 1).coerceAtLeast(0)).coerceAtMost(endLineEnd)
        return TextRange(start, maxOf(start, end))
    }

    return TextRange(start, lineEnd)
}
