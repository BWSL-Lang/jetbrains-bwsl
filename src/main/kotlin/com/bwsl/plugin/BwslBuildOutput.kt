package com.bwsl.plugin

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
 * Never throws: a process that cannot be started or does not finish says so in the result.
 */
fun runCompilerProcess(command: List<String>, workingDirectory: String, timeoutSeconds: Long = 30): CompilerRun {
    val started = System.nanoTime()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    val process = try {
        ProcessBuilder(command).directory(File(workingDirectory)).redirectErrorStream(true).start()
    } catch (e: Exception) {
        return CompilerRun(command, workingDirectory, "", null, elapsed(), startFailure = e.message ?: e.javaClass.simpleName)
    }
    process.outputStream.close()
    val output = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        val partial = runCatching { output.get(2, TimeUnit.SECONDS) }.getOrDefault("")
        return CompilerRun(command, workingDirectory, partial, null, elapsed(), didTimeOut = true)
    }
    return CompilerRun(command, workingDirectory, output.get(), process.exitValue(), elapsed())
}

/** The first line of the compiler's output that says something, for a notification; the rest is in the build output. */
fun summariseFailure(run: CompilerRun): String = when {
    run.startFailure != null -> "Failed to start the compiler: ${run.startFailure}"
    run.didTimeOut -> "The compiler did not finish in time."
    else -> run.output.lineSequence().map { it.trim() }.firstOrNull { it.contains("Error", ignoreCase = true) || it.contains("error") }
        ?: "Compilation failed (exit ${run.exitCode})."
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
            if (console.contentSize > 0) console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
            console.print("Working directory: ${run.workingDirectory}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            console.print("$ ${formatCommandLine(run.command)}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            if (run.output.isNotBlank()) {
                console.print(run.output.trimEnd() + "\n", if (run.isSuccess) ConsoleViewContentType.NORMAL_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
            }
            val ending = describeRun(run.copy(output = "")).lines().last { it.isNotBlank() }
            console.print(ending + "\n", if (run.isSuccess) ConsoleViewContentType.SYSTEM_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT)
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
