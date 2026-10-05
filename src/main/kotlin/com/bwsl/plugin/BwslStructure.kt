package com.bwsl.plugin

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.Language
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.tree.IElementType
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import javax.swing.Icon

/** What a declaration in the outline of a file is. */
enum class OutlineKind(val icon: Icon) {
    MODULE(AllIcons.Nodes.Module),
    SUBMODULE(AllIcons.Nodes.Module),
    PIPELINE(AllIcons.Nodes.Package),
    STRUCT(AllIcons.Nodes.Class),
    ENUM(AllIcons.Nodes.Enum),
    PASS(AllIcons.Nodes.Package),
    STAGE(AllIcons.Nodes.Function),
    FUNCTION(AllIcons.Nodes.Function),
    METHOD(AllIcons.Nodes.Method),
    CONSTANT(AllIcons.Nodes.Constant),
    FIELD(AllIcons.Nodes.Field)
}

/**
 * A declaration in the outline of a file: [nameOffset] is where its name starts and [range] spans the
 * whole declaration (a block's header to its closing brace). [children] are what it holds.
 */
class OutlineNode(val name: String, val kind: OutlineKind, val nameOffset: Int, val range: TextRange) {
    val children = ArrayList<OutlineNode>()
    var parent: OutlineNode? = null

    override fun equals(other: Any?): Boolean =
        other is OutlineNode && other.name == name && other.kind == kind && other.nameOffset == nameOffset
    override fun hashCode(): Int = 31 * name.hashCode() + nameOffset
}

private val CONTAINERS: Map<IElementType, OutlineKind> = mapOf(
    BwslTokenTypes.KW_MODULE to OutlineKind.MODULE,
    BwslTokenTypes.KW_SUBMODULE to OutlineKind.SUBMODULE,
    BwslTokenTypes.KW_PIPELINE to OutlineKind.PIPELINE,
    BwslTokenTypes.KW_STRUCT to OutlineKind.STRUCT,
    BwslTokenTypes.KW_ENUM to OutlineKind.ENUM,
    BwslTokenTypes.KW_PASS to OutlineKind.PASS
)

private val STAGES: Set<IElementType> = setOf(BwslTokenTypes.KW_VERTEX, BwslTokenTypes.KW_FRAGMENT, BwslTokenTypes.KW_COMPUTE)

/**
 * The declarations of [file], as a tree: modules, pipelines, structs, enums and passes hold what is
 * declared in them (functions and methods, constants, struct fields, passes and stages); a function or a
 * stage is not looked into. Found from the tokens, so it follows the text as it is typed and does not
 * need the file to compile.
 */
fun collectOutline(file: PsiFile): List<OutlineNode> {
    val leaves = collectLeaves(file)
    val closeOf = HashMap<Int, Int>()
    val open = ArrayList<Int>()
    for ((i, leaf) in leaves.withIndex()) {
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> open += i
            BwslTokenTypes.RBRACE -> open.removeLastOrNull()?.let { closeOf[it] = i }
        }
    }

    fun unquote(text: String) = text.removeSurrounding("\"")

    fun parse(from: Int, to: Int, container: OutlineKind?, parent: OutlineNode?): List<OutlineNode> {
        val found = ArrayList<OutlineNode>()
        var i = from
        while (i <= to) {
            // The statement starting here ends at its `;`, or at the `}` of the block it opens.
            var parentheses = 0
            var j = i
            var blockOpen = -1
            while (j <= to) {
                when (leaves[j].type) {
                    BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> parentheses++
                    BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> parentheses--
                }
                if (parentheses <= 0 && leaves[j].type == BwslTokenTypes.SEMI) break
                if (parentheses <= 0 && leaves[j].type == BwslTokenTypes.LBRACE) { blockOpen = j; break }
                j++
            }
            val first = leaves[i]
            if (first.type == BwslTokenTypes.KW_IMPORT || first.type == BwslTokenTypes.KW_USING) {
                // No `;` ends these: the name, and the alias after `as`.
                i += if (leaves.getOrNull(i + 2)?.type == BwslTokenTypes.KW_AS) 4 else 2
                continue
            }
            val end = if (blockOpen >= 0) closeOf[blockOpen] ?: to else minOf(j, to)
            val headerEnd = if (blockOpen >= 0) blockOpen - 1 else if (leaves[end].type == BwslTokenTypes.SEMI) end - 1 else end
            val range = TextRange(first.range.startOffset, leaves[end].range.endOffset)
            val header = (i..headerEnd).map { leaves[it] }

            var node: OutlineNode? = null
            val declarationLeaf = header.firstOrNull { it.type == BwslTokenTypes.FUNCTION_DECLARATION }
            when {
                declarationLeaf != null && header.any { it.type == BwslTokenTypes.COLONCOLON } ->
                    node = OutlineNode(
                        declarationLeaf.node.text,
                        if (container == OutlineKind.STRUCT) OutlineKind.METHOD else OutlineKind.FUNCTION,
                        declarationLeaf.range.startOffset, range
                    )
                first.type in CONTAINERS && blockOpen >= 0 -> {
                    val name = leaves.getOrNull(i + 1)?.takeIf { it.range.startOffset < leaves[blockOpen].range.startOffset }
                    if (name != null) {
                        node = OutlineNode(unquote(name.node.text), CONTAINERS.getValue(first.type), name.range.startOffset, range)
                        node.children += parse(blockOpen + 1, (closeOf[blockOpen] ?: to + 1) - 1, node.kind, node)
                    }
                }
                first.type in STAGES && blockOpen >= 0 -> {
                    val label = header.firstOrNull { it.type == BwslTokenTypes.STRING_LIT }
                    node = OutlineNode(
                        first.node.text + (label?.let { " ${unquote(it.node.text)}" } ?: ""), OutlineKind.STAGE, first.range.startOffset, range
                    )
                }
                blockOpen < 0 && first.type == BwslTokenTypes.KW_CONST -> {
                    val equals = header.indexOfFirst { it.type == BwslTokenTypes.EQ }
                    val name = if (equals > 0) header[equals - 1] else null
                    if (name != null) node = OutlineNode(name.node.text, OutlineKind.CONSTANT, name.range.startOffset, range)
                }
                blockOpen < 0 && container == OutlineKind.STRUCT && leaves[end].type == BwslTokenTypes.SEMI && header.size >= 2 -> {
                    val name = header.last()
                    if (name.type == BwslTokenTypes.IDENTIFIER) node = OutlineNode(name.node.text, OutlineKind.FIELD, name.range.startOffset, range)
                }
            }
            node?.let {
                it.parent = parent
                found += it
            }
            i = end + 1
        }
        return found
    }

    return parse(0, leaves.lastIndex, null, null)
}

/** The outline of [file], kept until the file changes. */
private fun findOutline(file: PsiFile): List<OutlineNode> =
    CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(collectOutline(file), PsiModificationTracker.MODIFICATION_COUNT)
    }

/** The innermost declaration of the outline whose range holds [offset]; null outside any. */
internal fun findInnermostOutlineNode(outline: List<OutlineNode>, offset: Int): OutlineNode? {
    for (node in outline) {
        if (node.range.startOffset <= offset && offset < node.range.endOffset) {
            return findInnermostOutlineNode(node.children, offset) ?: node
        }
    }
    return null
}

private fun describeNode(node: OutlineNode): String = when (node.kind) {
    OutlineKind.FUNCTION, OutlineKind.METHOD -> "${node.name}()"
    else -> node.name
}

/** File Structure (Ctrl+F12) and the Structure tool window: see [collectOutline]. */
class BwslStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile.language != BwslLanguage) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = BwslStructureViewModel(psiFile, editor)
            override fun isRootNodeShown(): Boolean = false
        }
    }
}

private class BwslStructureViewModel(private val file: PsiFile, editor: Editor?) :
    StructureViewModelBase(file, editor, BwslFileElement(file)), StructureViewModel.ElementInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean = (element.value as? OutlineNode)?.children?.isEmpty() == true

    /** The declaration the caret is in, for "autoscroll from source". */
    override fun getCurrentEditorElement(): Any? {
        val offset = editor?.caretModel?.offset ?: return null
        return findInnermostOutlineNode(findOutline(file), offset)
    }
}

private class BwslFileElement(private val file: PsiFile) : StructureViewTreeElement {
    override fun getValue(): Any = file
    override fun getChildren(): Array<TreeElement> = findOutline(file).map { BwslOutlineElement(file, it) }.toTypedArray()
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = file.name
        override fun getIcon(unused: Boolean): Icon = BwslFileType.MODULE_ICON
    }
    override fun navigate(requestFocus: Boolean) {}
    override fun canNavigate(): Boolean = false
    override fun canNavigateToSource(): Boolean = false
}

private class BwslOutlineElement(private val file: PsiFile, private val node: OutlineNode) : StructureViewTreeElement {
    override fun getValue(): Any = node
    override fun getChildren(): Array<TreeElement> = node.children.map { BwslOutlineElement(file, it) }.toTypedArray()
    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = describeNode(node)
        override fun getIcon(unused: Boolean): Icon = node.kind.icon
    }

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, node.nameOffset).navigate(requestFocus)
    }
    override fun canNavigate(): Boolean = file.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
}

/**
 * Breadcrumbs above the editor: the declarations around the caret, outermost first. The PSI is flat, so
 * the "parents" of an element are made up from the outline: an element's parent is the name token of the
 * innermost declaration around it.
 */
class BwslBreadcrumbsProvider : BreadcrumbsProvider {
    override fun getLanguages(): Array<Language> = arrayOf(BwslLanguage)

    override fun acceptElement(element: PsiElement): Boolean = findNodeNamedBy(element) != null

    override fun getElementInfo(element: PsiElement): String = findNodeNamedBy(element)?.let { describeNode(it) } ?: element.text

    override fun getElementIcon(element: PsiElement): Icon? = findNodeNamedBy(element)?.kind?.icon

    override fun getParent(element: PsiElement): PsiElement? {
        val file = element.containingFile ?: return null
        val outline = findOutline(file)
        val named = findNodeNamedBy(element)
        val offset = element.textRange.startOffset
        val target = if (named != null) named.parent else findInnermostOutlineNode(outline, offset)
        return target?.let { file.findElementAt(it.nameOffset) }
    }

    /** The declaration whose name token is [element], if it is one. */
    private fun findNodeNamedBy(element: PsiElement): OutlineNode? {
        val file = element.containingFile ?: return null
        val offset = element.textRange.startOffset
        var found: OutlineNode? = null
        fun visit(nodes: List<OutlineNode>) {
            for (node in nodes) {
                if (node.nameOffset == offset) found = node
                visit(node.children)
            }
        }
        visit(findOutline(file))
        return found
    }
}
