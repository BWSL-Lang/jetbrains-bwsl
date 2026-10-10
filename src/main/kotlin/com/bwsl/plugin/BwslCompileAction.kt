package com.bwsl.plugin

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

private val pipelinePattern = Regex("""\bpipeline\b""")

class BwslCompileAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file?.extension != "bwsl" || BwslSettings.getInstance().compilerPath.isBlank()) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val hasPipeline = file.contentsToByteArray().toString(Charsets.UTF_8)
            .contains(pipelinePattern)
        e.presentation.isVisible = true
        e.presentation.isEnabled = hasPipeline
        e.presentation.description = if (hasPipeline)
            "Compile the current BWSL shader file with bwslc"
        else
            "No pipeline block found — nothing to compile"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val settings = BwslSettings.getInstance()
        val modulePaths = collectModulePaths(project)

        val outputDir = settings.outputDirectory.takeIf { it.isNotBlank() }
            ?: virtualFile.parent?.path
            ?: return

        val format = BwslOutputFormat.entries.firstOrNull { it.name == settings.outputFormat }
            ?: BwslOutputFormat.SPIRV_ONLY

        object : Task.Backgroundable(project, "Compiling ${virtualFile.name}", false) {
            override fun run(indicator: ProgressIndicator) {
                val command = buildCompileCommand(settings.compilerPath, virtualFile.path, format, settings.emitDebugNames, modulePaths, settings.emitBindings)
                val run = runCompilerProcess(command, outputDir)
                val build = project.service<BwslBuildConsole>()
                // A failed build opens the output; a successful one leaves the window as it is.
                build.show(run, reveal = !run.isSuccess)

                if (run.isSuccess) {
                    ApplicationManager.getApplication().invokeLater {
                        VirtualFileManager.getInstance().asyncRefresh(null)
                    }
                    notify(project, "${virtualFile.name} compiled successfully.", NotificationType.INFORMATION)
                } else {
                    notify(project, summariseFailure(run), NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("BWSL Compiler")
                .createNotification(content, type)
                .addAction(NotificationAction.createSimple("Show build output") { project.service<BwslBuildConsole>().reveal() })
                .notify(project)
        }
    }
}

/** The compiler command that the settings in the arguments would give, for the preview in the settings page: `<file>.bwsl` stands for the file that is compiled. */
internal fun buildCompilePreview(
    compilerPath: String,
    format: BwslOutputFormat,
    emitDebugNames: Boolean,
    modulePaths: List<String>,
    emitBindings: Boolean
): String = formatCommandLine(
    buildCompileCommand(compilerPath.ifBlank { "bwslc" }, "<file>.bwsl", format, emitDebugNames, modulePaths, emitBindings)
)

/**
 * The command that compiles [file] for the Compile action: the chosen output [format], `-debug-names` when
 * [emitDebugNames] is set, `-bindings` for the GLSL ES target when [emitBindings] is set (they only change what is written, so the checks that read an AST or diagnostics
 * leave it out), and each module path.
 */
internal fun buildCompileCommand(
    compilerPath: String,
    file: String,
    format: BwslOutputFormat,
    emitDebugNames: Boolean,
    modulePaths: List<String>,
    emitBindings: Boolean = false
): List<String> {
    val command = mutableListOf(compilerPath, file)
    format.flag?.let { command += it }
    if (emitDebugNames) command += "-debug-names"
    if (emitBindings && format == BwslOutputFormat.GLES) command += "-bindings"
    modulePaths.forEach { command += listOf("-modules", it) }
    return command
}
