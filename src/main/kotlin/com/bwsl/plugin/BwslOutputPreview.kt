package com.bwsl.plugin

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tabs of the compiled-output preview, in order, with the file name whose highlighting each uses: the one a file
 * of that kind would get from whatever is installed, a language plugin or a TextMate grammar (see [createOutputHighlighter]).
 */
enum class BwslOutputTab(val title: String, private val extension: String) {
    SPIRV("SPIR-V", "spvasm"),
    GLSL("GLSL", "glsl"),
    GLES("GLSL ES", "glsl"),
    HLSL("HLSL", "hlsl"),
    METAL("Metal", "metal"),
    BINDINGS("Bindings", "json"),
    AST("AST", "json"),
    ;

    val highlightingFileName: String get() = "preview.$extension"
}

/**
 * The highlighter of [tab]'s text: the one the IDE would use for a file named [BwslOutputTab.highlightingFileName]. Looking it
 * up by a file, not by its extension, also finds what decides per file, as the TextMate plugin does for its grammars;
 * with nothing installed for the kind, it is plain text.
 */
fun createOutputHighlighter(project: Project, tab: BwslOutputTab): EditorHighlighter =
    EditorHighlighterFactory.getInstance().createEditorHighlighter(project, LightVirtualFile(tab.highlightingFileName))

/** What one compile for the preview gave: the text of every tab it has one for, and why it failed, if it did. */
data class PreviewCompile(val texts: Map<BwslOutputTab, String>, val failure: String?, val millis: Long)

/**
 * The texts to show after [compile]: a tab it has no text for keeps [previous], so a compile that fails (a syntax error
 * while typing, say) leaves the last output in place.
 */
fun mergePreviewTexts(previous: Map<BwslOutputTab, String>, compile: PreviewCompile): Map<BwslOutputTab, String> =
    previous + compile.texts

/**
 * The command the preview compiles with: the editor's text on standard input (`--stdin`, with [sourcePath] for the
 * modules beside the file), every established target, the SPIR-V and the bindings, into [outputDirectory].
 * `-debug-names` follows the setting, as in the Compile action.
 */
fun buildPreviewCommand(
    compilerPath: String,
    sourcePath: String,
    outputDirectory: String,
    emitDebugNames: Boolean,
    modulePaths: List<String>
): List<String> {
    val command = mutableListOf(compilerPath, "--stdin", "--source-file", sourcePath, "-o", outputDirectory, "-all", "-spv", "-bindings")
    if (emitDebugNames) command += "-debug-names"
    modulePaths.forEach { command += listOf("-modules", it) }
    return command
}

private val STAGE_ORDER = listOf(".vert", ".frag", ".comp")

/** The tab a file the compiler wrote belongs to, by its name (`d.vert.spv`, `d.glsl.frag`, `d_Pass.gles.vert.json`). */
fun classifyOutputFile(name: String): BwslOutputTab? = when {
    name.endsWith(".spv") -> BwslOutputTab.SPIRV
    name.endsWith(".bindings.json") -> BwslOutputTab.BINDINGS
    name.contains(".gles.") && name.endsWith(".json") -> BwslOutputTab.BINDINGS
    name.contains(".gles.") -> BwslOutputTab.GLES
    name.contains(".glsl.") -> BwslOutputTab.GLSL
    name.endsWith(".hlsl") -> BwslOutputTab.HLSL
    name.endsWith(".metal") -> BwslOutputTab.METAL
    else -> null
}

/** The files of one tab in the order to show them: by pass, then vertex, fragment, compute. */
private fun sortOutputFiles(files: List<File>): List<File> =
    files.sortedWith(compareBy({ file -> file.name.substringBefore('.') }, { file ->
        STAGE_ORDER.indexOfFirst { file.name.contains(it) }.let { if (it < 0) STAGE_ORDER.size else it }
    }, { it.name }))

/** `spirv-dis` from the Vulkan SDK or the `PATH`, or null. The compiler writes binary SPIR-V only. */
fun findSpirvDisassembler(): String? {
    val executable = if (System.getProperty("os.name").lowercase().contains("win")) "spirv-dis.exe" else "spirv-dis"
    val directories = listOfNotNull(System.getenv("VULKAN_SDK")?.let { "$it${File.separator}Bin" }, System.getenv("VULKAN_SDK")?.let { "$it${File.separator}bin" }) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator)
    return directories.asSequence().filter { it.isNotBlank() }.map { File(it, executable) }.firstOrNull { it.isFile }?.path
}

private val prettyJson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

private fun formatJson(text: String): String = runCatching { prettyJson.toJson(JsonParser.parseString(text)) }.getOrDefault(text)

private fun renderFiles(files: List<File>, read: (File) -> String): String =
    sortOutputFiles(files).joinToString("\n") { "// ${it.name}\n" + read(it).trimEnd() + "\n" }

/**
 * Compiles [text] (the editor's, saved or not) as [sourcePath] for every target, and its AST, for the preview. Never
 * throws: what could not be done is in [PreviewCompile.failure] or in the text of a tab.
 */
fun compilePreview(
    compilerPath: String,
    sourcePath: String,
    text: String,
    modulePaths: List<String>,
    emitDebugNames: Boolean,
    disassembler: String? = findSpirvDisassembler()
): PreviewCompile {
    val started = System.nanoTime()
    val directory = Files.createTempDirectory("bwsl_preview_").toFile()
    try {
        val texts = LinkedHashMap<BwslOutputTab, String>()
        val run = runCompilerProcess(
            buildPreviewCommand(compilerPath, sourcePath, directory.path, emitDebugNames, modulePaths),
            directory.path,
            stdinText = text
        )
        if (run.isSuccess) {
            val files = directory.listFiles().orEmpty().filter { it.isFile }.groupBy { classifyOutputFile(it.name) }
            for (tab in BwslOutputTab.entries) {
                if (tab == BwslOutputTab.AST) continue
                val tabFiles = files[tab].orEmpty()
                texts[tab] = when {
                    tabFiles.isEmpty() -> "// No ${tab.title} output: the file has no pipeline to compile.\n"
                    tab == BwslOutputTab.SPIRV && disassembler == null ->
                        "// The SPIR-V is binary: install the Vulkan SDK (or put spirv-dis on the PATH) to see its disassembly.\n" +
                            tabFiles.joinToString("") { "// ${it.name}: ${it.length()} bytes\n" }
                    tab == BwslOutputTab.SPIRV -> renderFiles(tabFiles) { file ->
                        runCompilerProcess(listOf(disassembler!!, file.path), directory.path).output
                    }
                    tab == BwslOutputTab.BINDINGS -> renderFiles(tabFiles) { formatJson(it.readText()) }
                    else -> renderFiles(tabFiles) { it.readText() }
                }
            }
        }
        val ast = runCatching { readAstJsonText(compilerPath, sourcePath, modulePaths, text) }.getOrNull()
        if (ast != null) texts[BwslOutputTab.AST] = formatJson(ast)
        val failure = if (run.isSuccess) null else summariseFailure(run)
        return PreviewCompile(texts, failure, (System.nanoTime() - started) / 1_000_000)
    } finally {
        directory.deleteRecursively()
    }
}

/**
 * The compiled output of a BWSL file, a tab per target: compiled while it is showing, again a moment after the
 * text changes, from the editor's text (saved or not). A compile that fails leaves the last output in place.
 */
class BwslOutputPreviewEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val tabs = JBTabbedPane()
    private val status = JBLabel()
    private val settingsLink = ActionLink("Configure the compiler") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, BwslSettingsConfigurable::class.java)
    }.apply { isVisible = false }
    private val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
        add(status)
        add(settingsLink)
    }
    private val panel = JPanel(BorderLayout()).apply {
        add(statusRow, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }
    private val viewers = BwslOutputTab.entries.associateWith { tab ->
        val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(""), project) as EditorEx
        editor.highlighter = createOutputHighlighter(project, tab)
        editor.settings.isLineNumbersShown = true
        editor.settings.isFoldingOutlineShown = false
        tabs.addTab(tab.title, editor.component)
        editor
    }
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private var texts: Map<BwslOutputTab, String> = emptyMap()
    @Volatile private var isStale = true
    private val source: Document? = FileDocumentManager.getInstance().getDocument(file)

    init {
        status.text = "The output appears here when the file is compiled."
        source?.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = scheduleCompile(TYPING_DELAY_MILLIS)
        }, this)
        // A compiler path or module path set in the settings changes what the file compiles to, or whether it can at all.
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(BwslSettingsListener.TOPIC, BwslSettingsListener { scheduleCompile(0) })
        panel.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && panel.isShowing && isStale) scheduleCompile(0)
        }
    }

    /** Compiles after [delayMillis], replacing a compile already waiting; only while the preview is showing. */
    private fun scheduleCompile(delayMillis: Int) {
        isStale = true
        if (!panel.isShowing) return
        alarm.cancelAllRequests()
        alarm.addRequest({ compileNow() }, delayMillis)
    }

    /** Compiles the editor's text now and shows the result (on a pooled thread; tests call it directly). */
    internal fun compileNow() {
        val compilerPath = resolveCompilerPath()
        if (compilerPath == null) {
            showStatus("No BWSL compiler is configured.", isError = true, showsSettingsLink = true)
            return
        }
        val text = ReadAction.computeBlocking<String, RuntimeException> { source?.text ?: "" }
        isStale = false
        val compile = compilePreview(compilerPath, file.path, text, collectModulePaths(project), BwslSettings.getInstance().emitDebugNames)
        ApplicationManager.getApplication().invokeLater({ showCompile(compile) }, ModalityState.any(), project.disposed)
    }

    private fun showCompile(compile: PreviewCompile) {
        // A document holds `\n` line breaks only; the compiler and spirv-dis write the platform's.
        texts = mergePreviewTexts(texts, compile).mapValues { (_, text) -> StringUtil.convertLineSeparators(text) }
        ApplicationManager.getApplication().runWriteAction {
            for ((tab, text) in texts) {
                val viewer = viewers.getValue(tab)
                if (viewer.document.text == text) continue
                val scroll = viewer.scrollingModel.verticalScrollOffset
                viewer.document.setText(text)
                viewer.scrollingModel.scrollVertically(scroll)
            }
        }
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (compile.failure != null) showStatus("$time  The compile failed: ${compile.failure}  (showing the last output)", isError = true)
        else showStatus("$time  Compiled in ${compile.millis} ms", isError = false)
    }

    private fun showStatus(text: String, isError: Boolean, showsSettingsLink: Boolean = false) {
        ApplicationManager.getApplication().invokeLater({
            status.text = text
            status.foreground = if (isError) JBColor.RED else JBColor.foreground()
            settingsLink.isVisible = showsSettingsLink
        }, ModalityState.any(), project.disposed)
    }

    /** Whether the link to the settings is shown, for tests. */
    internal val isSettingsLinkShown: Boolean get() = settingsLink.isVisible

    /** The status line as shown, for tests. */
    internal val shownStatus: String get() = status.text

    /** The text of [tab] as shown, for tests. */
    internal fun findShownText(tab: BwslOutputTab): String = viewers.getValue(tab).document.text

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = tabs
    override fun getName(): String = "Compiled Output"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun getFile(): VirtualFile = file
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        viewers.values.forEach { EditorFactory.getInstance().releaseEditor(it) }
    }

    private companion object {
        /** How long after the last change the preview compiles: long enough not to compile at every key. */
        const val TYPING_DELAY_MILLIS = 600
    }
}

/**
 * A BWSL file opens in the editor with the compiled output beside it, hidden until shown with the editor's
 * Editor / Split / Preview buttons, like a Markdown preview.
 */
class BwslSplitEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, BwslFileType)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val text = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        return TextEditorWithPreview(text, BwslOutputPreviewEditor(project, file), "BWSL", TextEditorWithPreview.Layout.SHOW_EDITOR)
    }

    override fun getEditorTypeId(): String = "bwsl-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
