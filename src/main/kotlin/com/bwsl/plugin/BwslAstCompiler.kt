package com.bwsl.plugin

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonSyntaxException
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private val log = logger<BwslProjectConfig>()

/** What `bwslc -ast-json` printed for one file, as the typed model and as a generic JSON tree. */
data class CompiledAst(val root: AstRoot, val rawJson: JsonObject)

/** The configured compiler path, falling back to a previously downloaded copy if present. */
internal fun resolveCompilerPath(): String? {
    val configured = BwslSettings.getInstance().compilerPath
    if (configured.isNotBlank()) return configured
    val downloaded = BwslCompilerDownloader.getInstallPath()
    return if (downloaded.toFile().exists()) downloaded.toString() else null
}

/**
 * Runs `bwslc <filePath> -ast-json` and returns the AST, or null when bwslc produced none (the file
 * does not compile). Throws [IOException] when bwslc could not be run or timed out, which says
 * nothing about the file.
 */
internal fun compileAst(
    compilerPath: String,
    filePath: String,
    modulePaths: List<String>,
    stdinText: String? = null
): CompiledAst? {
    val moduleArgs = modulePaths.flatMap { listOf("-modules", it) }
    val input = if (stdinText == null) listOf(filePath) else listOf("--stdin", "--source-file", filePath)
    val process = ProcessBuilder(listOf(compilerPath) + input + "-ast-json" + moduleArgs).start()
    // The text is written while the process runs, for the same reason the output is read that way.
    CompletableFuture.runAsync {
        process.outputStream.use { if (stdinText != null) it.write(stdinText.toByteArray(Charsets.UTF_8)) }
    }
    // Both streams are read while the process runs: a file with many errors fills the error pipe, and
    // a process blocked on writing it never ends, so reading one stream to its end first would wait forever.
    val stdout = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw IOException("bwslc -ast-json timed out for $filePath")
    }
    val rawBytes = stdout.get()
    val stderrText = stderr.get()
    if (stderrText.isNotBlank()) {
        log.warn("bwslc -ast-json stderr for $filePath: $stderrText")
    }
    if (rawBytes.isEmpty()) {
        log.warn("bwslc -ast-json produced no output for $filePath (exit code ${process.exitValue()})")
        return null
    }
    // Detect encoding: UTF-16 LE output starts with BOM bytes FF FE
    val hasUtf16Bom = rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte()
    val json = if (hasUtf16Bom) String(rawBytes, Charsets.UTF_16) else String(rawBytes, Charsets.UTF_8)
    val root = Gson().fromJson(json, AstRoot::class.java)
    if (root == null) {
        log.warn("bwslc -ast-json returned invalid JSON for $filePath: ${json.take(200)}")
        return null
    }
    val rawJson = Gson().fromJson(json, JsonObject::class.java)
    log.warn("bwslc -ast-json parsed for $filePath: modules=${root.modules.size} pipelines=${root.pipelines.size}")
    return CompiledAst(root, rawJson)
}

/**
 * Compiles [text] as if it were saved in [filePath], for the editor's unsaved changes: bwslc reads it from
 * stdin (`--stdin`) and uses [filePath] (`--source-file`) to find the modules beside it, so nothing is
 * written to disk. Returns null when bwslc produced no AST. Throws [IOException] when bwslc could not be run.
 */
internal fun compileEditorText(compilerPath: String, filePath: String, text: String, modulePaths: List<String>): CompiledAst? =
    compileAst(compilerPath, filePath, modulePaths, stdinText = text)

/**
 * Compiles the editor's [text] of [filePath] and keeps the AST for completion ([BwslAstCache.updateLive]).
 * When bwslc produces none (the text does not parse) the previous one stays. Returns whether one was kept.
 */
internal fun compileAndCacheLive(compilerPath: String, filePath: String, text: String, modulePaths: List<String>): Boolean {
    val compiled = compileEditorText(compilerPath, filePath, text, modulePaths) ?: return false
    BwslAstCache.updateLive(filePath, compiled.root, compiled.rawJson)
    return true
}

/**
 * Runs `bwslc <filePath> -errors-json -no-validate -check` and returns its diagnostics. `-check`
 * keeps bwslc from writing output files. With [stdinText] the text is checked as if it were the content
 * of [filePath] (`--stdin --source-file`), so the modules beside that file are found. Throws [IOException] when bwslc could not be run or timed out.
 */
internal fun collectDiagnostics(
    compilerPath: String,
    filePath: String,
    modulePaths: List<String>,
    stdinText: String? = null
): List<Diagnostic> {
    val moduleArgs = modulePaths.flatMap { listOf("-modules", it) }
    val input = if (stdinText == null) listOf(filePath) else listOf("--stdin", "--source-file", filePath)
    val process = ProcessBuilder(listOf(compilerPath) + input + listOf("-errors-json", "-no-validate", "-check") + moduleArgs)
        .redirectErrorStream(true)
        .start()
    CompletableFuture.runAsync {
        process.outputStream.use { if (stdinText != null) it.write(stdinText.toByteArray(Charsets.UTF_8)) }
    }
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroy()
        throw IOException("bwslc -errors-json timed out for $filePath")
    }
    return try {
        Gson().fromJson(output, CompilerOutput::class.java)?.diagnostics ?: emptyList()
    } catch (_: JsonSyntaxException) {
        emptyList()
    }
}

/**
 * The saved text of every file bwslc could read while compiling [file], as [normalizePathKey] ->
 * [BwslAstCache.hashText]: the file itself, the other BWSL files beside it, and the BWSL files directly
 * inside each of [modulePaths] (bwslc finds a module in `<Module>.bwsl` in those directories only).
 * Taken before the compile, so it is what bwslc is about to read.
 */
internal fun snapshotCandidateInputs(file: VirtualFile, modulePaths: List<String>): Map<String, Int> {
    val candidates = LinkedHashMap<String, VirtualFile>()
    fun addIfBwsl(candidate: VirtualFile) {
        if (!candidate.isDirectory && candidate.extension == "bwsl") candidates[normalizePathKey(candidate.path)] = candidate
    }
    addIfBwsl(file)
    file.parent?.children?.forEach { addIfBwsl(it) }
    for (directory in modulePaths) {
        LocalFileSystem.getInstance().findFileByPath(directory)?.children?.forEach { addIfBwsl(it) }
    }
    return candidates.mapValues { BwslAstCache.hashText(LoadTextUtil.loadText(it.value).toString()) }
}

/** Every `sourceFile` the payload names: the compiled file and each file a declaration was written in. */
internal fun collectSourceFiles(rawJson: JsonObject): Set<String> = collectStringFields(rawJson, "sourceFile")

/** The value of every field called [name] with a string value, anywhere in the payload. */
internal fun collectStringFields(rawJson: JsonObject, name: String): Set<String> {
    val found = LinkedHashSet<String>()
    fun visit(element: JsonElement) {
        when {
            element.isJsonObject -> for ((key, value) in element.asJsonObject.entrySet()) {
                if (key == name) value.asStringOrNull()?.let { found += it } else visit(value)
            }
            element.isJsonArray -> element.asJsonArray.forEach { visit(it) }
        }
    }
    visit(rawJson)
    return found
}

/**
 * Compiles [filePath] and caches the AST together with the files it was built from, picked out of
 * [candidateInputs] (the snapshot taken before the compile) by which files the payload names. When
 * bwslc produces no AST, records that against [candidateInputs] so the file is not retried until
 * something it could read changes. Returns whether an AST was cached. Throws [IOException] when
 * bwslc could not be run, which is not recorded.
 */
internal fun compileAndCache(
    compilerPath: String,
    filePath: String,
    modulePaths: List<String>,
    candidateInputs: Map<String, Int>
): Boolean {
    val compiled = compileAst(compilerPath, filePath, modulePaths)
    if (compiled == null) {
        BwslAstCache.recordUncompilable(filePath, candidateInputs)
        return false
    }
    val ownKey = normalizePathKey(filePath)
    val dependencyKeys = collectSourceFiles(compiled.rawJson).map { normalizePathKey(it) }.toSet()
    val inputs = candidateInputs.filterKeys { it == ownKey || it in dependencyKeys }
    BwslAstCache.update(filePath, compiled.root, compiled.rawJson, inputs)
    // Standard modules are embedded in the compiler; fetch their sources so navigation can open them.
    BwslStdlibSources.downloadInBackground(collectStringFields(compiled.rawJson, "sourceUrl"))
    return true
}
