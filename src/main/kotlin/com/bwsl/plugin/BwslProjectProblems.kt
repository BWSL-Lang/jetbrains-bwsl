package com.bwsl.plugin

import com.google.gson.JsonParser
import com.intellij.analysis.problemsView.HighlightingDuplicateProblem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Files
import javax.swing.Icon

private val log = logger<BwslProjectProblems>()

/** One error the compiler reports in a file, as the Problems tool window's Project Errors tab lists it. */
data class BwslCompilerProblem(
    override val provider: ProblemsProvider,
    override val file: VirtualFile,
    /** Zero-based, as the Problems view wants it. */
    override val line: Int,
    override val column: Int,
    override val text: String
) : HighlightingDuplicateProblem {
    // The editor's own highlighting of an open file shows the same errors; the view drops these for it.
    override val icon: Icon get() = HighlightDisplayLevel.ERROR.icon
}

/** An error of `bwslc -errors-json`: the file it is in (as the compiler names it), its 1-based line and column, its message. */
data class BatchDiagnostic(val path: String, val line: Int, val column: Int, val message: String)

/**
 * The errors in [json], what a batch `bwslc -manifest <file> -errors-json` printed: every unit's diagnostics of
 * severity `error` (its warnings and notes are the editor's to show), each in the file it names, which can be a
 * module the unit imports. Null when [json] is not such a document.
 */
fun parseBatchErrors(json: String): List<BatchDiagnostic>? {
    val root = runCatching { JsonParser.parseString(json.substring(json.indexOf('{').coerceAtLeast(0))).asJsonObject }.getOrNull() ?: return null
    // A manifest of one file gets the report of a single compile: that unit at the top, with no `files` list.
    val units = root.getAsJsonArray("files")?.toList() ?: if (root.has("diagnostics")) listOf(root) else return null
    return units.flatMap { unit ->
        val unitObject = unit.asJsonObject
        val unitPath = unitObject.get("file")?.asString
        unitObject.getAsJsonArray("diagnostics")?.mapNotNull { element ->
            val diagnostic = element.asJsonObject
            if (diagnostic.get("severity")?.asString != "error") return@mapNotNull null
            val path = diagnostic.get("file")?.asString ?: unitPath ?: return@mapNotNull null
            BatchDiagnostic(
                path,
                diagnostic.get("line")?.asInt ?: 0,
                diagnostic.get("column")?.asInt ?: 0,
                diagnostic.get("message")?.asString.orEmpty()
            )
        }.orEmpty()
    }.distinct()
}

/**
 * The compiler's errors in every indexed BWSL file ([collectIndexedFiles]), opened or not, for the Problems tool
 * window's Project Errors tab. Refreshed after the project index is (at startup, when a `.bwsl` file or `bwsl.json`
 * changes, and when the settings are applied), with one batch run of the compiler over the saved files.
 */
@Service(Service.Level.PROJECT)
class BwslProjectProblems(override val project: Project) : ProblemsProvider {

    @Volatile private var shown: Set<BwslCompilerProblem> = emptySet()

    /** Checks every indexed file and shows what the compiler reports; returns the problems. On a pooled thread. */
    fun refreshNow(): Set<BwslCompilerProblem> {
        val compilerPath = resolveCompilerPath() ?: return replaceProblems(emptySet())
        val files = collectIndexedFiles(project).filter { it.fileSystem == LocalFileSystem.getInstance() }
        if (files.isEmpty()) return replaceProblems(emptySet())
        val directory = Files.createTempDirectory("bwsl_problems_").toFile()
        try {
            // A manifest, not the paths themselves: a project's paths could pass the command line's length limit.
            val manifest = File(directory, "files.txt")
            manifest.writeText(files.joinToString("\n") { File(it.path).path })
            val command = listOf(compilerPath, "-manifest", manifest.path, "-errors-json", "-no-validate", "-check") +
                collectModulePaths(project).flatMap { listOf("-modules", it) }
            val run = runCompilerProcess(command, directory.path, timeoutSeconds = 120)
            val errors = parseBatchErrors(run.output)
            if (errors == null) {
                log.warn("bwslc -errors-json printed no report for the project's files: ${summariseFailure(run)}")
                return shown
            }
            val problems = errors.mapNotNull { error ->
                val file = LocalFileSystem.getInstance().findFileByPath(error.path.replace('\\', '/')) ?: return@mapNotNull null
                BwslCompilerProblem(this, file, (error.line - 1).coerceAtLeast(0), (error.column - 1).coerceAtLeast(0), error.message)
            }.toSet()
            return replaceProblems(problems)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Tells the Problems view which of [problems] are new and which of the shown ones are gone, on the UI thread. */
    private fun replaceProblems(problems: Set<BwslCompilerProblem>): Set<BwslCompilerProblem> {
        ApplicationManager.getApplication().invokeLater({
            val collector = ProblemsCollector.getInstance(project)
            val old = shown
            shown = problems
            (old - problems).forEach { collector.problemDisappeared(it) }
            (problems - old).forEach { collector.problemAppeared(it) }
        }, project.disposed)
        return problems
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): BwslProjectProblems = project.getService(BwslProjectProblems::class.java)
    }
}
