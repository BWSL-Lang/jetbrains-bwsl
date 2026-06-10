package com.bwsl.plugin

import com.google.gson.Gson
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private val log = logger<BwslAstAnnotator>()

data class AstCollectedInfo(val content: String, val filePath: String)

class BwslAstAnnotator : ExternalAnnotator<AstCollectedInfo, Boolean>() {

    override fun collectInformation(file: PsiFile): AstCollectedInfo? {
        if (BwslSettings.getInstance().compilerPath.isBlank()) {
            log.warn("bwslc compiler path is not set; skipping AST collection for ${file.virtualFile?.path}")
            return null
        }
        val path = file.virtualFile?.path ?: return null
        return AstCollectedInfo(file.text, path)
    }

    override fun doAnnotate(info: AstCollectedInfo): Boolean? {
        val compilerPath = BwslSettings.getInstance().compilerPath
        val tempFile = Files.createTempFile("bwsl_ast_", ".bwsl").toFile()
        try {
            tempFile.writeText(info.content)
            val moduleArgs = BwslSettings.getInstance().modulePaths
                .flatMap { listOf("-modules", it) }
            val process = ProcessBuilder(
                listOf(compilerPath, tempFile.absolutePath, "-ast-json") + moduleArgs
            ).start()
            val rawBytes = process.inputStream.readBytes()
            val stderrText = process.errorStream.bufferedReader().readText()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroy()
                log.warn("bwslc -ast-json timed out for ${info.filePath}")
                return null
            }
            if (stderrText.isNotBlank()) {
                log.warn("bwslc -ast-json stderr for ${info.filePath}: $stderrText")
            }
            if (rawBytes.isEmpty()) {
                log.warn("bwslc -ast-json produced no output for ${info.filePath} (exit code ${process.exitValue()})")
                return null
            }
            // Detect encoding: UTF-16 LE output starts with BOM bytes FF FE
            val hasUtf16Bom = rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte()
            val json = if (hasUtf16Bom) String(rawBytes, Charsets.UTF_16) else String(rawBytes, Charsets.UTF_8)
            val root = Gson().fromJson(json, AstRoot::class.java)
            if (root == null) {
                log.warn("bwslc -ast-json returned invalid JSON for ${info.filePath}: ${json.take(200)}")
                return null
            }
            val functions = root.allFunctions()
            log.warn("bwslc -ast-json parsed for ${info.filePath}: modules=${root.modules.size} functions=${functions.map { it.name }}")
            BwslAstCache.update(info.filePath, functions)
            return true
        } catch (e: Exception) {
            log.warn("bwslc -ast-json failed for ${info.filePath}", e)
            return null
        } finally {
            tempFile.delete()
        }
    }

    override fun apply(file: PsiFile, result: Boolean, holder: AnnotationHolder) {
        // AST is stored in BwslAstCache; no annotations to apply here
    }
}
