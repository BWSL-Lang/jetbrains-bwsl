package com.bwsl.plugin

import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

const val BUILD_TOOL_WINDOW_ID = "BWSL Build"

/** What one run of the compiler did, as shown in the build output. */
data class CompilerRun(
    val command: List<String>,
    val workingDirectory: String,
    /** Everything the compiler wrote, standard output and standard error together, in the order it was written. */
    val output: String,
    /** The compiler's exit code; null when it did not finish (see [startFailure] and [didTimeOut]). */
    val exitCode: Int?,
    val millis: Long,
    val didTimeOut: Boolean = false,
    val startFailure: String? = null
) {
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * [command] as one line that can be pasted into a shell: an argument with a space, a quote or nothing in it is
 * put in double quotes (with any double quote inside escaped).
 */
fun formatCommandLine(command: List<String>): String = command.joinToString(" ") { argument ->
    if (argument.isEmpty() || argument.any { it.isWhitespace() || it == '"' }) "\"" + argument.replace("\"", "\\\"") + "\"" else argument
}

/**
 * Runs [command] in [workingDirectory] and waits up to [timeoutSeconds]. Standard output and standard error are read
 * together while the process runs (a compile with many errors fills the pipe and would otherwise block it).
 * Never throws: a process that cannot be started or does not finish says so in the result. [stdinText] is the
 * compiler's standard input, for `--stdin`.
 */
fun runCompilerProcess(command: List<String>, workingDirectory: String, timeoutSeconds: Long = 30, stdinText: String? = null): CompilerRun {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val process = try {
        ProcessBuilder(command).directory(File(workingDirectory)).redirectErrorStream(true).start()
    } catch (e: Exception) {
        return CompilerRun(command, workingDirectory, "", null, elapsed(), startFailure = e.message ?: e.javaClass.simpleName)
    }
    // The text (for `--stdin`) is written while the output is read, so neither side waits for the other.
    CompletableFuture.runAsync { process.outputStream.use { if (stdinText != null) it.write(stdinText.toByteArray(Charsets.UTF_8)) } }
    val output = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        val partial = runCatching { output.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        return CompilerRun(command, workingDirectory, partial, null, elapsed(), didTimeOut = true)
    }
    return CompilerRun(command, workingDirectory, output.get(), process.exitValue(), elapsed())
}

/** A file the compiler's output names for an error: where in the output the path is, and the error's 1-based line and column. */
data class OutputLocation(val range: IntRange, val path: String, val line: Int, val column: Int)

private val ERROR_POSITION = Regex("""at line (\d+), column (\d+)""")
private val ERROR_FILE = Regex("""File: (.*\S)""")

/**
 * The locations of the errors in the compiler's [output], whose report of one reads `┌─ Error[…] at line 2, column 47`
 * and on the next line `│ File: C:\…\shader.bwsl`: each `File:` path that follows a line and column.
 */
fun collectOutputLocations(output: String): List<OutputLocation> {
    val locations = ArrayList<OutputLocation>()
    var position: Pair<Int, Int>? = null
    var lineStart = 0
    for (line in output.split('\n')) {
        val at = ERROR_POSITION.find(line)
        val file = ERROR_FILE.find(line)
        if (at != null) {
            position = at.groupValues[1].toInt() to at.groupValues[2].toInt()
        } else if (file != null && position != null) {
            val path = file.groups[1]!!
            locations += OutputLocation((lineStart + path.range.first)..(lineStart + path.range.last), path.value, position.first, position.second)
            position = null
        }
        lineStart += line.length + 1
    }
    return locations
}

/** The first line of the compiler's output that says something, for a notification; the rest is in the build output. */
fun summariseFailure(run: CompilerRun): String = when {
    run.startFailure != null -> "Failed to start the compiler: ${run.startFailure}"
    run.didTimeOut -> "The compiler did not finish in time."
    else -> run.output.lineSequence().map { it.trim() }.firstOrNull { it.contains("Error", ignoreCase = true) || it.contains("error") }
        ?: "Compilation failed (exit ${run.exitCode})."
}

/**
 * Prints [run] into [console], after what is there: where and what was run, what it printed (the file each error names
 * as a link to the error's line and column in it), and how it ended.
 */
internal fun printRun(console: ConsoleView, project: Project, run: CompilerRun) {
    if (console.contentSize > 0) console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
    console.print("Working directory: ${run.workingDirectory}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    console.print("$ ${formatCommandLine(run.command)}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    if (run.output.isNotBlank()) {
        val output = run.output.trimEnd() + "\n"
        val type = if (run.isSuccess) ConsoleViewContentType.NORMAL_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT
        var printed = 0
        for (location in collectOutputLocations(output)) {
            val path = File(location.path).let { if (it.isAbsolute) it else File(run.workingDirectory, location.path) }
            val file = LocalFileSystem.getInstance().findFileByIoFile(path) ?: continue
            console.print(output.substring(printed, location.range.first), type)
            console.printHyperlink(output.substring(location.range), OpenFileHyperlinkInfo(project, file, location.line - 1, location.column - 1))
            printed = location.range.last + 1
        }
        console.print(output.substring(printed), type)
    }
    val ending = describeRun(run.copy(output = "")).lines().last { it.isNotBlank() }
    console.print(ending + "\n", if (run.isSuccess) ConsoleViewContentType.SYSTEM_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
}

/** The text of the build output for [run]: what was run and where, what it printed, and how it ended. */
fun describeRun(run: CompilerRun): String = buildString {
    append("Working directory: ").append(run.workingDirectory).append('\n')
    append("$ ").append(formatCommandLine(run.command)).append('\n')
    if (run.output.isNotBlank()) append(run.output.trimEnd()).append('\n')
    append(
        when {
            run.startFailure != null -> "Could not start: ${run.startFailure}"
            run.didTimeOut -> "Timed out after ${run.millis / 1000} s"
            else -> "Finished with exit code ${run.exitCode} in ${run.millis} ms"
        }
    ).append('\n')
}

/**
 * The console of the BWSL Build tool window, kept by the project so that what was built before the window was
 * first opened is still there.
 */
@Service(Service.Level.PROJECT)
class BwslBuildConsole(private val project: Project) : Disposable {

    val console: ConsoleView by lazy {
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console.also { Disposer.register(this, it) }
    }

    /** Adds [run] to the output, after what is there, on the UI thread. */
    fun show(run: CompilerRun, reveal: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            printRun(console, project, run)
            if (reveal) reveal()
        }
    }

    /** Opens the BWSL Build tool window. */
    fun reveal() {
        ToolWindowManager.getInstance(project).getToolWindow(BUILD_TOOL_WINDOW_ID)?.show()
    }

    override fun dispose() {}
}

/** The BWSL Build tool window: the compiler's command line and output for each build started from the IDE. */
class BwslBuildToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = project.service<BwslBuildConsole>().console
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(console.component, "", false))
    }
}
