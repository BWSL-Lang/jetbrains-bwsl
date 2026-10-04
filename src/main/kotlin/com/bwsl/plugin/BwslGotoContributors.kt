package com.bwsl.plugin

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import javax.swing.Icon

/** What kind of declaration a [BwslSymbolEntry] is, for the Go to Class and Go to Symbol lists. */
enum class BwslSymbolKind(val label: String, val isType: Boolean) {
    MODULE("module", true),
    PIPELINE("pipeline", true),
    STRUCT("struct", true),
    PASS("pass", false),
    FUNCTION("function", false),
    METHOD("method", false),
    CONSTANT("constant", false)
}

/** A declaration written in a project file, with where its name is. */
data class BwslSymbolEntry(
    val name: String,
    val kind: BwslSymbolKind,
    /** The module, pipeline or struct that holds it; empty at the top level. */
    val container: String,
    val file: VirtualFile,
    val offset: Int
)

private val KINDS = mapOf(
    "module" to BwslSymbolKind.MODULE,
    "pipeline" to BwslSymbolKind.PIPELINE,
    "struct" to BwslSymbolKind.STRUCT,
    "pass" to BwslSymbolKind.PASS,
    "function" to BwslSymbolKind.FUNCTION,
    "method" to BwslSymbolKind.METHOD,
    "constant" to BwslSymbolKind.CONSTANT
)

/**
 * The modules, pipelines, structs, passes, functions, methods and module-level constants written in the
 * project's files, from the compiler's AST of each. A file is read only while its text is what was
 * compiled (the saved text, or the editor's if it is the same); a file without such an AST has no
 * entries.
 */
fun collectProjectSymbols(project: Project): List<BwslSymbolEntry> {
    val entries = ArrayList<BwslSymbolEntry>()
    for (file in FileTypeIndex.getFiles(BwslFileType, GlobalSearchScope.projectScope(project))) {
        val root = BwslAstCache.findRoot(file.path) ?: continue
        val raw = BwslAstCache.findRawRoot(file.path) ?: continue
        val compiled = BwslAstCache.findCompiledInputs(file.path)?.get(normalizePathKey(file.path)) ?: continue
        val text = FileDocumentManager.getInstance().getDocument(file)?.text ?: continue
        if (BwslAstCache.hashText(text) != compiled) continue
        val index = BwslAstIndex(root, raw, text)
        val positions = SourcePositions(text)
        for (symbol in index.symbolsById.values) {
            val kind = KINDS[symbol.kind] ?: continue
            // A local constant has no owner; only members and module-level declarations are listed.
            if (kind == BwslSymbolKind.CONSTANT && symbol.owner.isEmpty()) continue
            val node = index.nodesById[symbol.declaration.ifEmpty { symbol.id }] ?: continue
            val range = positions.findNameRangeOf(node) ?: continue
            entries += BwslSymbolEntry(symbol.name, kind, index.symbolsById[symbol.owner]?.name.orEmpty(), file, range.first)
        }
    }
    return entries.sortedWith(compareBy({ it.file.path }, { it.offset }))
}

/** One row of a Go to list: shows the name, what it is and where, and opens the file at the name. */
private class BwslSymbolNavigationItem(private val project: Project, private val entry: BwslSymbolEntry) : NavigationItem {
    private val descriptor get() = OpenFileDescriptor(project, entry.file, entry.offset)

    override fun getName(): String = entry.name
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = entry.name
        override fun getLocationString(): String =
            (if (entry.container.isNotEmpty()) "${entry.container} " else "") + "(${entry.file.name}) - ${entry.kind.label}"
        override fun getIcon(unused: Boolean): Icon = if (entry.kind == BwslSymbolKind.PIPELINE) BwslFileType.PIPELINE_ICON else BwslFileType.MODULE_ICON
    }

    override fun navigate(requestFocus: Boolean) = descriptor.navigate(requestFocus)
    override fun canNavigate(): Boolean = entry.file.isValid
    override fun canNavigateToSource(): Boolean = canNavigate()
}

abstract class BwslGotoContributor(private val wanted: (BwslSymbolKind) -> Boolean) : ChooseByNameContributor {
    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
        collectProjectSymbols(project).filter { wanted(it.kind) }.map { it.name }.distinct().toTypedArray()

    override fun getItemsByName(name: String?, pattern: String?, project: Project, includeNonProjectItems: Boolean): Array<NavigationItem> =
        collectProjectSymbols(project).filter { wanted(it.kind) && it.name == name }
            .map<BwslSymbolEntry, NavigationItem> { BwslSymbolNavigationItem(project, it) }.toTypedArray()
}

/** Go to Class (Ctrl+N): modules, pipelines and structs. */
class BwslGotoClassContributor : BwslGotoContributor({ it.isType })

/** Go to Symbol (Ctrl+Alt+Shift+N): passes, functions, methods and constants. */
class BwslGotoSymbolContributor : BwslGotoContributor({ !it.isType })
