package com.bwsl.plugin

import com.intellij.ide.hierarchy.CallHierarchyBrowserBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.SimpleTextAttributes
import java.util.Comparator
import java.util.Objects
import javax.swing.JTree

/**
 * Call Hierarchy (Ctrl+Alt+H) for BWSL functions and methods, from the compiler's `call` edges: the callers are the
 * usages Find Usages finds, grouped by the function, method or stage each is written in; the callees are the `call`
 * edges out of the function's body. An intrinsic is a callee with nothing below it.
 */
class BwslCallHierarchyProvider : HierarchyProvider {
    override fun getTarget(dataContext: DataContext): PsiElement? {
        val file = CommonDataKeys.PSI_FILE.getData(dataContext)?.takeIf { it.language == BwslLanguage } ?: return null
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return null
        return findCallHierarchyTarget(file, editor.caretModel.offset)
    }

    override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser = BwslCallHierarchyBrowser(target.project, target)

    override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
        (hierarchyBrowser as BwslCallHierarchyBrowser).changeView(CallHierarchyBrowserBase.getCallerType())
    }
}

private val CALLABLE_KINDS = setOf("function", "method")

/**
 * The function or method the hierarchy is shown for, as the token of its name: the one called at [offset], the one
 * whose name [offset] is on, or else the one [offset] is inside. Null when there is none, or no AST of [file].
 */
fun findCallHierarchyTarget(file: PsiFile, offset: Int): PsiElement? {
    val index = buildAstIndex(file) ?: return null
    for (declaration in collectDeclarationIdsAt(index, offset)) {
        if (index.symbolsById[declaration]?.kind in CALLABLE_KINDS) return resolveDeclarationPosition(file, index, declaration)
    }
    val node = index.findNodeAtOffset(offset)
    if (node != null && node.type == "FUNCTION") return findNameElementOf(file, index, node)
    return index.findEnclosingNodeOfType("FUNCTION", offset)?.let { findNameElementOf(file, index, it) }
}

private fun findNameElementOf(file: PsiFile, index: BwslAstIndex, node: AstNodePos): PsiElement? =
    index.findNameRangeOf(node)?.let { findElementAtOffset(file, it.first) }

/**
 * One row of the hierarchy: a function or method (at its name), a stage that calls something (at its keyword), or an
 * intrinsic (at the call). [callSites] are the calls the row stands for: where a caller calls, or where a callee is
 * called; opening the row goes to the first.
 */
class BwslCallHierarchyNodeDescriptor(
    project: Project,
    parent: NodeDescriptor<*>?,
    element: PsiElement,
    isBase: Boolean,
    private val label: String,
    val callSites: List<PsiElement>,
    /** Whether the row can have rows below it: not for a stage, an intrinsic, or a function already above it. */
    val canExpand: Boolean
) : HierarchyNodeDescriptor(project, parent, element, isBase) {

    override fun update(): Boolean {
        val changed = super.update()
        val old = myHighlightedText
        myHighlightedText = CompositeAppearance()
        myHighlightedText.ending.addText(label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (callSites.size > 1) myHighlightedText.ending.addText("  (${callSites.size} usages)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        psiElement?.containingFile?.name?.let { myHighlightedText.ending.addText("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        myName = myHighlightedText.text
        return changed || !Objects.equals(old, myHighlightedText)
    }
}

class BwslCallHierarchyBrowser(project: Project, target: PsiElement) : CallHierarchyBrowserBase(project, target) {

    override fun createTrees(trees: MutableMap<in String, in JTree>) {
        trees[getCallerType()] = createTree(false)
        trees[getCalleeType()] = createTree(false)
    }

    override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? = descriptor.psiElement

    override fun getOpenFileElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? =
        (descriptor as? BwslCallHierarchyNodeDescriptor)?.callSites?.firstOrNull() ?: descriptor.psiElement

    override fun isApplicableElement(element: PsiElement): Boolean = element.containingFile?.language == BwslLanguage

    override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure? = when (type) {
        getCallerType() -> BwslCallerTreeStructure(myProject, psiElement)
        getCalleeType() -> BwslCalleeTreeStructure(myProject, psiElement)
        else -> null
    }

    // The rows stay in the order they are written in.
    override fun getComparator(): Comparator<NodeDescriptor<*>>? = null
}

/** The label of the function or method whose name is [element]: `Owner::name(Type a, Type b)`. */
private fun describeCallable(element: PsiElement): String {
    val identity = findDeclarationIdentityOf(element) ?: return element.text
    val index = findIndexOfIdentity(element, identity)
    val signature = index?.let { buildFunctionSignature(it, identity.symbol) }
    val owner = index?.symbolsById?.get(identity.symbol.owner)?.takeIf { it.kind == "struct" }?.name
    val name = if (owner != null) "$owner::${identity.symbol.name}" else identity.symbol.name
    return name + "(" + signature?.params.orEmpty().joinToString(", ") + ")"
}

private fun findIndexOfIdentity(element: PsiElement, identity: DeclarationIdentity): BwslAstIndex? {
    val file = element.containingFile ?: return null
    if (file.virtualFile?.path == identity.payloadPath) return buildAstIndex(file)
    return collectPayloadFiles(element.project).firstOrNull { it.virtualFile?.path == identity.payloadPath }?.let { buildAstIndex(it) }
}

private fun isOnPathAbove(descriptor: HierarchyNodeDescriptor, element: PsiElement): Boolean =
    generateSequence(descriptor.parentDescriptor) { it.parentDescriptor }
        .any { (it as? HierarchyNodeDescriptor)?.psiElement == element }

private fun createBaseDescriptor(project: Project, element: PsiElement) =
    BwslCallHierarchyNodeDescriptor(project, null, element, true, describeCallable(element), emptyList(), canExpand = true)

/** The functions, methods and stages that call the base, each with the calls it makes, in the order of the files. */
class BwslCallerTreeStructure(project: Project, element: PsiElement) :
    HierarchyTreeStructure(project, createBaseDescriptor(project, element)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val node = descriptor as? BwslCallHierarchyNodeDescriptor ?: return emptyArray()
        val target = node.psiElement ?: return emptyArray()
        if (!node.canExpand) return emptyArray()
        val callers = LinkedHashMap<PsiElement, MutableList<PsiElement>>()
        val labels = HashMap<PsiElement, Pair<String, Boolean>>()
        for (reference in findUsagesOf(target, GlobalSearchScope.everythingScope(myProject))) {
            val site = reference.element
            val file = site.containingFile ?: continue
            val index = buildAstIndex(file) ?: continue
            val offset = site.textRange.startOffset + reference.rangeInElement.startOffset
            val function = index.findEnclosingNodeOfType("FUNCTION", offset)
            val caller: PsiElement
            if (function != null) {
                caller = findNameElementOf(file, index, function) ?: continue
                labels.getOrPut(caller) { describeCallable(caller) to (!isOnPathAbove(node, caller) && caller != target) }
            } else {
                val stage = STAGE_TYPES.firstNotNullOfOrNull { index.findEnclosingNodeOfType(it, offset) } ?: continue
                val start = index.findBodyRangeOf(stage)?.first ?: continue
                caller = findElementAtOffset(file, start) ?: continue
                labels.getOrPut(caller) { describeStage(index, stage) to false }
            }
            callers.getOrPut(caller) { ArrayList() } += site
        }
        return callers.map { (caller, sites) ->
            val (label, canExpand) = labels.getValue(caller)
            BwslCallHierarchyNodeDescriptor(myProject, descriptor, caller, false, label, sites, canExpand)
        }.toTypedArray()
    }
}

private val STAGE_TYPES = listOf("VERTEX_STAGE", "FRAGMENT_STAGE", "COMPUTE_STAGE")

/** `vertex stage of pass "Main"`, for a call written in a stage. */
private fun describeStage(index: BwslAstIndex, stage: AstNodePos): String {
    val kind = stage.type.substringBefore('_').lowercase()
    val offset = index.findBodyRangeOf(stage)?.first ?: return "$kind stage"
    val pass = index.findEnclosingNodeOfType("PASS", offset)?.name
    return if (pass != null) "$kind stage of pass \"$pass\"" else "$kind stage"
}

/** The functions, methods and intrinsics the base calls, each with its calls, in the order they are first called. */
class BwslCalleeTreeStructure(project: Project, element: PsiElement) :
    HierarchyTreeStructure(project, createBaseDescriptor(project, element)) {

    override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
        val node = descriptor as? BwslCallHierarchyNodeDescriptor ?: return emptyArray()
        val caller = node.psiElement ?: return emptyArray()
        if (!node.canExpand) return emptyArray()
        val file = caller.containingFile ?: return emptyArray()
        // Only a function written in a file with an AST of its own can say what it calls (not one in a fetched copy of a standard module).
        val index = buildAstIndex(file) ?: return emptyArray()
        val function = index.nodesById.values.firstOrNull { it.type == "FUNCTION" && index.findNameRangeOf(it)?.first == caller.textOffset }
            ?: return emptyArray()
        val body = index.findBodyRangeOf(function) ?: return emptyArray()

        data class Callee(val element: PsiElement, val label: String, val canExpand: Boolean)
        val callees = LinkedHashMap<Any, Pair<Callee, MutableList<PsiElement>>>()
        val calls = index.refsByFrom.values.flatten()
            .filter { it.role == "call" }
            .mapNotNull { edge -> index.nodesById[edge.from]?.let { from -> index.findNameRangeOf(from)?.let { range -> Triple(edge, from, range.first) } } }
            .filter { (_, _, start) -> start in body }
            .sortedBy { (_, _, start) -> start }
        for ((edge, _, start) in calls) {
            val site = findElementAtOffset(file, start) ?: continue
            val symbol = index.symbolsById[edge.to]
            val declaration = symbol?.declaration?.ifEmpty { symbol.id } ?: edge.to
            val entry = if (declaration.startsWith("builtin:")) {
                val name = symbol?.name?.ifEmpty { null } ?: declaration.substringAfterLast(':')
                callees.getOrPut(declaration) { Callee(site, "$name (intrinsic)", false) to ArrayList() }
            } else {
                if (symbol?.kind !in CALLABLE_KINDS) continue
                val target = resolveDeclarationPosition(file, index, declaration) ?: continue
                callees.getOrPut(target) { Callee(target, describeCallable(target), !isOnPathAbove(node, target) && target != caller) to ArrayList() }
            }
            entry.second += site
        }
        return callees.values.map { (callee, sites) ->
            BwslCallHierarchyNodeDescriptor(myProject, descriptor, callee.element, false, callee.label, sites, callee.canExpand)
        }.toTypedArray()
    }
}
