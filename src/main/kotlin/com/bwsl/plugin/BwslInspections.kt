package com.bwsl.plugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Something the inspections report: the 0-based [range] of a name in the compiled text. */
data class UnusedDeclaration(val name: String, val kind: VisibleLocal.Kind, val range: TextRange, val isWrittenOnly: Boolean) {
    fun describe(): String {
        val label = kind.label.replaceFirstChar { it.uppercase() }
        return if (isWrittenOnly) "$label '$name' is assigned but its value is never used" else "$label '$name' is never used"
    }
}

/** An `import` or `using` whose module nothing in the file refers to. */
data class UnusedImport(val module: String, val range: TextRange)

/** A `Module::` written in the file with no import that brings the module in. */
data class MissingImport(val module: String, val range: TextRange)

/** What the inspections work from: the cached AST of a file and the index over it, valid for the text it was compiled from. */
internal class InspectionInput(val index: BwslAstIndex, val text: String)

/** The cached AST of [file], or null when there is none or the editor no longer holds the text it was compiled from. */
internal fun findInspectionInput(file: PsiFile): InspectionInput? {
    val path = file.virtualFile?.path ?: return null
    val root = BwslAstCache.findRoot(path) ?: return null
    val raw = BwslAstCache.findRawRoot(path) ?: return null
    val compiled = BwslAstCache.findCompiledInputs(path)?.get(normalizePathKey(path)) ?: return null
    val text = file.text
    if (BwslAstCache.hashText(text) != compiled) return null
    return InspectionInput(BwslAstIndex(root, raw, text), text)
}

private val READ_ROLES_EXCLUDED = setOf("write")

/**
 * The parameters, locals and constants declared in the file that nothing reads. A name that is only
 * assigned counts too ([UnusedDeclaration.isWrittenOnly]). Module-, pipeline- and pass-level consts are
 * left out, as they can be used from other files, and so are loop variables, which are often written
 * only to count.
 */
internal fun collectUnusedDeclarations(input: InspectionInput): List<UnusedDeclaration> {
    val index = input.index
    val positions = SourcePositions(input.text)
    val found = ArrayList<UnusedDeclaration>()
    for (symbol in index.symbolsById.values) {
        val kind = when (symbol.kind) {
            "parameter" -> VisibleLocal.Kind.PARAMETER
            "variable" -> VisibleLocal.Kind.VARIABLE
            "constant" -> VisibleLocal.Kind.CONSTANT
            else -> continue
        }
        // A local has no owner; a member or module-level declaration does.
        if (kind != VisibleLocal.Kind.PARAMETER && symbol.owner.isNotEmpty()) continue
        val node = index.nodesById[symbol.declaration.ifEmpty { symbol.id }] ?: continue
        val range = positions.findNameRangeOf(node) ?: continue
        val incoming = index.refsByTo[symbol.id].orEmpty()
        val isWrittenOnly = incoming.isNotEmpty() && incoming.all { it.role in READ_ROLES_EXCLUDED }
        if (incoming.isNotEmpty() && !isWrittenOnly) continue
        found += UnusedDeclaration(symbol.name, kind, TextRange(range.first, range.last + 1), isWrittenOnly)
    }
    return found.sortedBy { it.range.startOffset }
}

/**
 * The imports of the file whose module nothing refers to: no `Mod::` qualifier, and nothing the module
 * declares (so a `using` that only brings in unqualified names counts as used when one is).
 */
internal fun collectUnusedImports(input: InspectionInput): List<UnusedImport> {
    val index = input.index
    val positions = SourcePositions(input.text)

    // The module each symbol belongs to, through its owners.
    fun moduleOf(symbolId: String): String? {
        var current = index.symbolsById[symbolId]
        var steps = 0
        while (current != null && steps++ < 8) {
            if (current.kind == "module") return current.id
            current = index.symbolsById[current.owner]
        }
        return null
    }

    val used = HashSet<String>()
    for (edge in index.refsByTo.values.flatten()) {
        if (edge.role == "import" || edge.role == "using") continue
        moduleOf(edge.to)?.let { used += it }
    }
    val result = ArrayList<UnusedImport>()
    for ((id, node) in index.nodesById) {
        if (!id.contains("/import:") && !id.contains("/using:")) continue
        val target = index.refsByFrom[id].orEmpty().firstOrNull { it.role == "import" || it.role == "using" }?.to ?: continue
        if (target in used) continue
        val range = positions.findNameRangeOf(node) ?: continue
        result += UnusedImport(node.name ?: continue, TextRange(range.first, range.last + 1))
    }
    // The compiler records no edge for every use of a module: `Mod.Type` in a `resources` block has none, for
    // one (see BWSL gaps). So a module whose name is written anywhere besides its own import lines is not
    // called unused: a false "unused" is worse than a missed one.
    val declarations = result.groupingBy { it.module }.eachCount()
    return result.filter { unused ->
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(unused.module) + "(?![A-Za-z0-9_])").findAll(input.text).count() <= declarations.getValue(unused.module)
    }.sortedBy { it.range.startOffset }
}

/**
 * The `Module::` qualifiers in the file that name a module the compiler could not resolve, because the
 * file does not import it: bwslc keeps the qualifier as a positioned identifier with no edge. Only a name
 * that [knownModules] holds is reported, so a typo is left to the compiler.
 */
internal fun collectMissingImports(input: InspectionInput, knownModules: Set<String>): List<MissingImport> {
    val index = input.index
    val positions = SourcePositions(input.text)
    val result = ArrayList<MissingImport>()
    for (node in index.nodesById.values) {
        if (node.type != "IDENTIFIER") continue
        val name = node.name ?: continue
        if (name !in knownModules || index.refsByFrom[node.id].orEmpty().isNotEmpty()) continue
        val range = positions.findNameRangeOf(node) ?: continue
        if (!input.text.startsWith("::", range.last + 1)) continue
        result += MissingImport(name, TextRange(range.first, range.last + 1))
    }
    return result.sortedBy { it.range.startOffset }
}

private fun findTokenAt(file: PsiFile, range: TextRange): PsiElement? =
    file.findElementAt(range.startOffset)?.takeIf { it.textRange == range }

/** Reports the parameters, locals and constants that are never used. */
class BwslUnusedDeclarationInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.language != BwslLanguage) return null
        val input = findInspectionInput(file) ?: return null
        return collectUnusedDeclarations(input).mapNotNull { declaration ->
            val element = findTokenAt(file, declaration.range) ?: return@mapNotNull null
            val fixes = if (declaration.kind == VisibleLocal.Kind.PARAMETER) emptyArray() else arrayOf<LocalQuickFix>(RemoveDeclarationFix())
            manager.createProblemDescriptor(
                element, declaration.describe(), isOnTheFly, fixes, ProblemHighlightType.LIKE_UNUSED_SYMBOL
            )
        }.toTypedArray()
    }
}

/** Reports the imports nothing in the file uses. */
class BwslUnusedImportInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.language != BwslLanguage) return null
        val input = findInspectionInput(file) ?: return null
        return collectUnusedImports(input).mapNotNull { unused ->
            val element = findTokenAt(file, unused.range) ?: return@mapNotNull null
            manager.createProblemDescriptor(
                element, "Import of '${unused.module}' is never used", isOnTheFly,
                arrayOf<LocalQuickFix>(RemoveImportFix()), ProblemHighlightType.LIKE_UNUSED_SYMBOL
            )
        }.toTypedArray()
    }
}

/** Reports a `Module::` the file has not imported, and offers the import. */
class BwslMissingImportInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file.language != BwslLanguage) return null
        val input = findInspectionInput(file) ?: return null
        val known = collectImportableNames(emptySet()).map { it.module }.toSet()
        return collectMissingImports(input, known).mapNotNull { missing ->
            val element = findTokenAt(file, missing.range) ?: return@mapNotNull null
            manager.createProblemDescriptor(
                element, "Module '${missing.module}' is not imported", isOnTheFly,
                arrayOf<LocalQuickFix>(AddImportFix(missing.module)), ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            )
        }.toTypedArray()
    }
}

/** Where the statement around [leaves]`[index]` starts and ends, as the first and last token index; null when it has no `;`. */
private fun findStatementTokens(leaves: List<Leaf>, index: Int): Pair<Int, Int>? {
    var start = index
    while (start > 0 && leaves[start - 1].type !in setOf(BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE)) start--
    var end = index
    while (end < leaves.size && leaves[end].type != BwslTokenTypes.SEMI) {
        if (leaves[end].type == BwslTokenTypes.LBRACE || leaves[end].type == BwslTokenTypes.RBRACE) return null
        end++
    }
    return if (end < leaves.size) start to end else null
}

/** Removes the whole line when [range] is all that is on it, else just [range]. */
private fun removeTextRange(project: Project, file: PsiFile, range: TextRange) {
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
    val text = document.charsSequence
    var start = range.startOffset
    var end = range.endOffset
    val lineStart = text.lastIndexOf('\n', start - 1).let { it + 1 }
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
        start = lineStart
        end = if (lineEnd < text.length) lineEnd + 1 else lineEnd
    }
    document.deleteString(start, end)
    PsiDocumentManager.getInstance(project).commitDocument(document)
}

/** Deletes an unused variable's declaration, unless its initialiser calls something. */
class RemoveDeclarationFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused declaration"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val leaves = collectLeaves(file)
        val index = leaves.indexOfFirst { it.range == element.textRange }.takeIf { it >= 0 } ?: return
        val (start, end) = findStatementTokens(leaves, index) ?: return
        // An initialiser with a call may have effects of its own: the declaration is kept.
        if ((start..end).any { leaves[it].type == BwslTokenTypes.LPAREN }) return
        removeTextRange(project, file, TextRange(leaves[start].range.startOffset, leaves[end].range.endOffset))
    }
}

/** Deletes an unused `import` or `using` line. */
class RemoveImportFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove unused import"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val leaves = collectLeaves(file)
        val index = leaves.indexOfFirst { it.range == element.textRange }.takeIf { it >= 0 } ?: return
        var start = index
        while (start > 0 && leaves[start].type != BwslTokenTypes.KW_IMPORT && leaves[start].type != BwslTokenTypes.KW_USING) start--
        var end = index
        // `import Math as M2`: the alias goes with it.
        if (leaves.getOrNull(end + 1)?.type == BwslTokenTypes.KW_AS) end += 2
        if (leaves.getOrNull(end + 1)?.type == BwslTokenTypes.SEMI) end++
        removeTextRange(project, file, TextRange(leaves[start].range.startOffset, leaves[end.coerceAtMost(leaves.lastIndex)].range.endOffset))
    }
}

/** Inserts `import Module` into the module or pipeline that uses it. */
class AddImportFix(private val module: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Add import"
    override fun getName(): String = "Import '$module'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val insertion = findImportInsertion(file, element.textRange.startOffset, module) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        document.insertString(insertion.offset, insertion.text)
        documents.commitDocument(document)
    }
}
