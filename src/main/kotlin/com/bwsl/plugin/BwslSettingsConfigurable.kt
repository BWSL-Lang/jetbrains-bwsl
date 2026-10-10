package com.bwsl.plugin

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class BwslSettingsConfigurable : Configurable {

    private var panel: DialogPanel? = null
    private val moduleListModel = DefaultListModel<String>().also { model ->
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent) = refreshPreview()
            override fun intervalRemoved(e: ListDataEvent) = refreshPreview()
            override fun contentsChanged(e: ListDataEvent) = refreshPreview()
        })
    }
    private var formatCombo: ComboBox<BwslOutputFormat>? = null
    private var compilerField: com.intellij.openapi.ui.TextFieldWithBrowseButton? = null
    private var debugNamesBox: javax.swing.JCheckBox? = null
    private var bindingsBox: javax.swing.JCheckBox? = null
    private val preview = JBTextArea(2, 60).apply { isEditable = false; lineWrap = true; wrapStyleWord = false }

    /** Shows the command the compile action would run with what is in the page now (not yet applied). */
    private fun refreshPreview() {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        val paths = moduleListModel.elements().toList()
        preview.text = buildCompilePreview(
            compilerField?.text.orEmpty(),
            formatCombo?.selectedItem as? BwslOutputFormat ?: BwslOutputFormat.SPIRV_ONLY,
            debugNamesBox?.isSelected ?: false,
            if (project != null) collectModulePaths(project, paths) else paths,
            bindingsBox?.isSelected ?: false
        )
    }

    override fun getDisplayName(): String = "BWSL"

    override fun createComponent(): JComponent {
        val settings = BwslSettings.getInstance()
        moduleListModel.clear()
        settings.modulePaths.forEach { moduleListModel.addElement(it) }

        formatCombo = ComboBox(BwslOutputFormat.entries.toTypedArray()).apply {
            renderer = textListCellRenderer("") {  it.displayName }
            selectedItem = BwslOutputFormat.entries.firstOrNull { it.name == settings.outputFormat }
                ?: BwslOutputFormat.SPIRV_ONLY
        }

        formatCombo!!.addActionListener { refreshPreview() }

        val moduleList = JBList(moduleListModel)
        val decorator = ToolbarDecorator.createDecorator(moduleList)
            .setAddAction {
                val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                val initialDir = project?.basePath?.let {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
                }
                val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
                    .withTitle("Select Module Directory")
                val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, initialDir)
                if (chosen != null) {
                    val dir = if (chosen.isDirectory) chosen else chosen.parent
                    if (dir != null && !moduleListModel.elements().toList().contains(dir.path)) {
                        moduleListModel.addElement(dir.path)
                    }
                }
            }
            .setRemoveAction { moduleList.selectedValuesList.forEach { moduleListModel.removeElement(it) } }
            .createPanel()

        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        return panel {
            row("Compiler path:") {
                @Suppress("UnstableApiUsage")
                val field = textFieldWithBrowseButton(
                    FileChooserDescriptor(true, false, false, false, false, false)
                        .withTitle("Select BWSL Compiler"),
                    project
                ).bindText(settings::compilerPath)
                compilerField = field.component
                field.component.textField.document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) = refreshPreview()
                })
                button("Download Latest...") {
                    val installed = try {
                        ProgressManager.getInstance().runProcessWithProgressSynchronously<java.nio.file.Path, Exception>(
                            { BwslCompilerDownloader.downloadLatest() },
                            "Downloading BWSL Compiler",
                            true,
                            project
                        )
                    } catch (e: Exception) {
                        Messages.showErrorDialog(project, "Failed to download bwslc: ${e.message}", "BWSL")
                        return@button
                    }
                    field.component.text = installed.toString()
                }
            }
            row {
                checkBox("Check for a newer compiler release on startup")
                    .bindSelected(settings::checkForCompilerUpdates)
            }
            row("Output format:") {
                cell(formatCombo!!)
            }
            row {
                val box = checkBox("Emit debug names in the SPIR-V output (-debug-names)")
                    .bindSelected(settings::emitDebugNames)
                debugNamesBox = box.component
                box.component.addActionListener { refreshPreview() }
            }
            row {
                val box = checkBox("Write resource bindings for the GLSL ES / WebGL target (-bindings)")
                    .bindSelected(settings::emitBindings)
                bindingsBox = box.component
                box.component.addActionListener { refreshPreview() }
            }
            row("Output directory:") {
                @Suppress("UnstableApiUsage")
                textFieldWithBrowseButton(
                    FileChooserDescriptor(false, true, false, false, false, false)
                        .withTitle("Select Output Directory"),
                    project
                ).bindText(settings::outputDirectory)
                    .comment("Defaults to the source file's directory if empty")
            }
            row("Command preview:") {
                cell(preview).align(AlignX.FILL)
                    .comment("The Compile action runs this in the output directory. <file> is the file being compiled.")
            }
            row("Module paths:") {}
            row {
                cell(decorator).align(Align.FILL)
            }.resizableRow()
        }.also { panel = it; refreshPreview() }
    }

    override fun isModified(): Boolean {
        if (panel?.isModified() == true) return true
        val settings = BwslSettings.getInstance()
        val currentFormat = (formatCombo?.selectedItem as? BwslOutputFormat)?.name ?: BwslOutputFormat.SPIRV_ONLY.name
        return currentFormat != settings.outputFormat || settings.modulePaths != moduleListModel.elements().toList()
    }

    override fun apply() {
        panel?.apply()
        val settings = BwslSettings.getInstance()
        settings.outputFormat = (formatCombo?.selectedItem as? BwslOutputFormat)?.name
            ?: BwslOutputFormat.SPIRV_ONLY.name
        settings.modulePaths = moduleListModel.elements().toList().toMutableList()
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart("BWSL settings changed")
        }
    }

    override fun reset() {
        panel?.reset()
        val settings = BwslSettings.getInstance()
        formatCombo?.selectedItem = BwslOutputFormat.entries.firstOrNull { it.name == settings.outputFormat }
            ?: BwslOutputFormat.SPIRV_ONLY
        moduleListModel.clear()
        settings.modulePaths.forEach { moduleListModel.addElement(it) }
        refreshPreview()
    }

    override fun disposeUIResources() {
        panel = null
        formatCombo = null
        compilerField = null
        debugNamesBox = null
        bindingsBox = null
    }
}
