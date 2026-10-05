package com.bwsl.plugin

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException

private val log = logger<BwslRenameProcessor>()

private const val MAX_PARALLEL_COMPILES = 4
private const val MAX_REPORTED_PER_FILE = 5

/** What the compiler said about one file in one copy of the sources. */
private class CompileOutcome(val compiled: CompiledAst?, val diagnostics: List<Diagnostic>, val text: String)

/**
 * A copy of the sources bwslc can read for the files being checked, in a temporary directory, with
 * some files' text replaced and some files renamed. A file is compiled in the copy of its own
 * directory, and the module paths are copies too, so imports resolve as they do in the project.
 */
private class SourceMirror(
    private val root: File,
    private val modulePaths: List<String>,
    private val texts: Map<String, String>,
    private val names: Map<String, String>
) {
    private val directories = HashMap<String, File>()

    /** Where [file] is in the mirror, copying its directory first if that has not been done. */
    fun findCopyOf(file: VirtualFile): File = File(copyDirectoryOf(file.parent), nameOf(file))

    /** The mirror's counterparts of the module paths that exist. */
    fun collectModulePaths(): List<String> = modulePaths.mapNotNull { path ->
        LocalFileSystem.getInstance().findFileByPath(path)?.let { copyDirectoryOf(it).path }
    }

    private fun nameOf(file: VirtualFile): String = names[normalizePathKey(file.path)] ?: file.name

    private fun copyDirectoryOf(directory: VirtualFile): File =
        directories.getOrPut(normalizePathKey(directory.path)) {
            val copy = root.resolve("d${directories.size}").also { it.mkdirs() }
            for (child in directory.children) {
                if (child.isDirectory || child.extension != "bwsl") continue
                val text = texts[normalizePathKey(child.path)] ?: LoadTextUtil.loadText(child).toString()
                copy.resolve(nameOf(child)).writeText(text)
            }
            copy
        }
}

/**
 * What renaming would break, found by asking the compiler instead of reasoning about scopes: the
 * rename is applied to a copy of the sources, and the copy is compared with an unrenamed one. Reports
 *
 * - an error the rename introduces (two declarations with the same name in one scope, an overload
 *   that already exists, a stage value whose type conflicts with the one it is merged into), and
 * - a name that would resolve to a different declaration than before (it is captured by a closer
 *   declaration of the new name, or the new name was already used by something else it now meets).
 *
 * Both rely on the compiler: a file that compiled before and does not now has a conflict, and the
 * reference index of the two copies has the same shape unless the meaning changed, because node ids
 * follow the source order, not the names.
 *
 * [fileRenames] maps a file's [normalizePathKey] to the file name it gets (a module declared in a
 * file of the same name moves with it). Returns nothing when the compiler cannot be run.
 */
internal fun collectRenameConflicts(
    compilerPath: String,
    modulePaths: List<String>,
    edits: List<RenameEdit>,
    oldName: String,
    newName: String,
    isStageValue: Boolean,
    fileRenames: Map<String, String>
): List<String> {
    val editsByFile = edits.groupBy { it.file.virtualFile ?: return emptyList() }
    val files = editsByFile.keys.toList()
    val originalTexts = files.associateWith { LoadTextUtil.loadText(it).toString() }
    val renamedTexts = files.associateWith { applyEditsToText(originalTexts.getValue(it), editsByFile.getValue(it), newName) }

    val root = Files.createTempDirectory("bwsl_rename_check_").toFile()
    try {
        val before = SourceMirror(root.resolve("before"), modulePaths, emptyMap(), emptyMap())
        val after = SourceMirror(
            root.resolve("after"),
            modulePaths,
            files.associate { normalizePathKey(it.path) to renamedTexts.getValue(it) },
            fileRenames
        )
        // Every directory is copied before anything is compiled, on this thread.
        val copies = files.associateWith { before.findCopyOf(it) to after.findCopyOf(it) }
        val beforeModules = before.collectModulePaths()
        val afterModules = after.collectModulePaths()

        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("BWSL rename check", MAX_PARALLEL_COMPILES)
        val outcomes = copies.mapValues { (file, copy) ->
            executor.submit(Callable { compileCopy(compilerPath, copy.first, beforeModules, originalTexts.getValue(file)) }) to
                executor.submit(Callable { compileCopy(compilerPath, copy.second, afterModules, renamedTexts.getValue(file)) })
        }
        return files.flatMap { file ->
            val (beforeOutcome, afterOutcome) = outcomes.getValue(file)
            describeConflicts(file.name, oldName, newName, isStageValue, beforeOutcome.get(), afterOutcome.get())
        }
    } catch (e: ExecutionException) {
        if (e.cause !is IOException) throw e
        log.warn("The rename could not be checked with bwslc", e.cause)
        return emptyList()
    } catch (e: IOException) {
        log.warn("The rename could not be checked with bwslc", e)
        return emptyList()
    } finally {
        root.deleteRecursively()
    }
}

private fun applyEditsToText(text: String, edits: List<RenameEdit>, newName: String): String {
    val renamed = StringBuilder(text)
    for (edit in edits.sortedByDescending { it.range.startOffset }) {
        renamed.replace(edit.range.startOffset, edit.range.endOffset, newName)
    }
    return renamed.toString()
}

private fun compileCopy(compilerPath: String, file: File, modulePaths: List<String>, text: String): CompileOutcome =
    CompileOutcome(
        compileAst(compilerPath, file.path, modulePaths),
        collectDiagnostics(compilerPath, file.path, modulePaths),
        text
    )

private fun describeConflicts(
    fileName: String,
    oldName: String,
    newName: String,
    isStageValue: Boolean,
    before: CompileOutcome,
    after: CompileOutcome
): List<String> {
    val introduced = collectIntroducedErrors(before, after)
        .map { "$fileName${it.line?.let { line -> ":$line" }.orEmpty()}: ${it.message}" }
    if (after.compiled == null) {
        return introduced.ifEmpty { listOf("$fileName would no longer compile.") }
    }
    val beforeCompiled = before.compiled ?: return introduced

    // A stage value's symbol id carries its name (`PASS:0/interface:uv`); every other id follows the source order.
    val mapId = { id: String ->
        if (isStageValue) id.replace(Regex("/interface:${Regex.escape(oldName)}$"), "/interface:$newName") else id
    }
    val beforeEdges = beforeCompiled.root.referenceIndex?.references.orEmpty()
        .map { Triple(mapId(it.from), mapId(it.to), it.role) }.toSet()
    val afterEdges = after.compiled.root.referenceIndex?.references.orEmpty()
        .map { Triple(it.from, it.to, it.role) }.toSet()

    val beforeIndex = BwslAstIndex(beforeCompiled.root, beforeCompiled.rawJson, before.text)
    val afterIndex = BwslAstIndex(after.compiled.root, after.compiled.rawJson, after.text)

    val changedUses = (beforeEdges - afterEdges).map { it.first }.plus((afterEdges - beforeEdges).map { it.first }).distinct()
        .take(MAX_REPORTED_PER_FILE)
        .map { from ->
            val name = afterIndex.nodesById[from]?.let { afterIndex.findNameRangeOf(it) }
            val where = name?.let { "$fileName:${findLineOf(after.text, it.first)}" } ?: fileName
            val used = name?.let { after.text.substring(it.first, it.last + 1) } ?: "a name"
            "$where: '$used' would refer to " +
                "${describeTargets(afterEdges, from, afterIndex, mapId = { it })} " +
                "instead of ${describeTargets(beforeEdges, from, beforeIndex, mapId = mapId)}."
        }

    val merged = if (isStageValue) {
        val beforeCount = beforeCompiled.root.referenceIndex?.symbols.orEmpty().count { it.kind == "stage-interface" }
        val afterCount = after.compiled.root.referenceIndex?.symbols.orEmpty().count { it.kind == "stage-interface" }
        if (beforeCount != afterCount) {
            listOf("$fileName: '$newName' is already a stage value in this pass, so renaming would merge the two.")
        } else emptyList()
    } else emptyList()

    return introduced + changedUses + merged
}

/** The errors [after] reports that [before] did not, matching by message so that a moved line is not a new error. */
private fun collectIntroducedErrors(before: CompileOutcome, after: CompileOutcome): List<Diagnostic> {
    val known = before.diagnostics.filter { it.severity.equals("error", ignoreCase = true) }
        .groupingBy { it.message }.eachCount().toMutableMap()
    return after.diagnostics.filter { it.severity.equals("error", ignoreCase = true) }.filter { error ->
        val remaining = known[error.message] ?: 0
        if (remaining > 0) known[error.message] = remaining - 1
        remaining == 0
    }
}

private fun describeTargets(
    edges: Set<Triple<String, String, String>>,
    from: String,
    index: BwslAstIndex,
    mapId: (String) -> String
): String {
    val targets = edges.filter { it.first == from }.mapNotNull { edge ->
        index.symbolsById.entries.firstOrNull { mapId(it.key) == edge.second }?.value ?: index.symbolsById[edge.second]
    }
    return targets.distinctBy { it.id }.joinToString(" and ") { "the ${it.kind.replace('-', ' ')} '${it.name}'" }
        .ifEmpty { "nothing" }
}

private fun findLineOf(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1
