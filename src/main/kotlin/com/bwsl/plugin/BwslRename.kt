package com.bwsl.plugin

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap

/** One occurrence of the name being renamed: the declaration or a usage. */
internal data class RenameEdit(val file: PsiFile, val range: TextRange)

/**
 * Rename for BWSL declarations: functions, methods, structs, fields, parameters, locals, constants,
 * attributes, fragment outputs and modules. Every usage is one the compiler's reference index found
 * (see [findUsagesOf]); the edits replace each occurrence's text.
 *
 * BWSL's PSI has no named elements, so the platform's default rename can't edit it: this processor
 * applies the edits itself. It works from the compiler's last result for every indexed file (see
 * [BwslProjectIndex]), so it refuses to rename when a file has unsaved changes, has changed since the
 * compiler saw it, does not compile, or has never been compiled: a usage added since would be missed
 * and a moved one would be edited at the wrong place.
 */
class BwslRenameProcessor : RenamePsiElementProcessor() {

    /** Not a standard-library declaration: its file is a read-only copy of the compiler's source. */
    override fun canProcessElement(element: PsiElement): Boolean =
        element.containingFile?.virtualFile?.path?.let { BwslStdlibSources.isCopy(it) } != true &&
            findDeclarationIdentityOf(element) != null

    /**
     * Before the rename starts, brings the compiler's view of the whole project up to date, so the
     * usages it finds include files that were never opened. Runs behind a progress dialog, and only
     * when some file has no current AST.
     */
    override fun substituteElementToRename(element: PsiElement, editor: Editor?): PsiElement? {
        BwslProjectIndex.getInstance(element.project).refreshWithProgress()
        return element
    }

    /**
     * A module is found by the compiler in a file named after it, so renaming a module that lives in
     * a file of the same name renames that file too.
     */
    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val symbol = findDeclarationIdentityOf(element)?.symbol ?: return
        val file = element.containingFile ?: return
        if (isModuleInFileOfSameName(symbol, file)) allRenames[file] = "$newName.bwsl"
    }

    /**
     * Asks the compiler whether the new name would break anything - see [collectRenameConflicts] -
     * and reports what it finds as conflicts, which the platform shows before it applies the rename.
     * Does nothing when the compiler's view is not current (the rename then refuses, with the reason)
     * or no compiler is configured.
     */
    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        val symbol = findDeclarationIdentityOf(element)?.symbol ?: return
        val project = element.project
        if (checkCompilerViewIsCurrent(project) != null) return
        val compilerPath = resolveCompilerPath() ?: return

        val file = element.containingFile
        val edits = collectRenameEdits(element, findReferences(element, GlobalSearchScope.projectScope(project), false))
        val fileRenames = if (isModuleInFileOfSameName(symbol, file)) {
            mapOf(normalizePathKey(file.virtualFile.path) to "$newName.bwsl")
        } else {
            emptyMap()
        }
        val modulePaths = collectModulePaths(project)
        val found = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<String>, RuntimeException> {
                collectRenameConflicts(compilerPath, modulePaths, edits, element.text, newName, symbol.kind == "stage-interface", fileRenames)
            },
            "Checking the rename with the compiler",
            true,
            project
        )
        found.forEach { conflicts.putValue(element, it) }
    }

    /**
     * The platform's rename scope is the project's content, which leaves out a module file in a shared
     * `-modules` directory; the indexed files ([collectIndexedFiles]) are all searched.
     */
    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
        val project = element.project
        val scope = if (searchScope is GlobalSearchScope) {
            searchScope.uniteWith(GlobalSearchScope.filesScope(project, collectIndexedFiles(project)))
        } else {
            searchScope
        }
        return findUsagesOf(element, scope)
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<UsageInfo>,
        listener: RefactoringElementListener?
    ) {
        val oldName = element.text
        val edits = collectRenameEdits(element, usages)
        (checkEdits(edits, oldName) ?: checkCompilerViewIsCurrent(element.project))?.let { throw IncorrectOperationException(it) }

        val file = element.containingFile
        val declarationOffset = element.textRange.startOffset
        applyEdits(element.project, edits, newName)
        findElementAtOffset(file, declarationOffset)?.let { listener?.elementRenamed(it) }
    }
}

/** What counts as a valid BWSL name for a rename: a single plain identifier that is not a keyword or type. */
class BwslNamesValidator : NamesValidator {

    override fun isKeyword(name: String, project: Project?): Boolean =
        isWord(name) && findSingleTokenType(name) != BwslTokenTypes.IDENTIFIER

    override fun isIdentifier(name: String, project: Project?): Boolean =
        isWord(name) && findSingleTokenType(name) == BwslTokenTypes.IDENTIFIER

    private fun isWord(name: String): Boolean = name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))

    /** The type of the only token [text] lexes to, or null if it lexes to more than one. */
    private fun findSingleTokenType(text: String): com.intellij.psi.tree.IElementType? {
        val lexer = BwslLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val type = lexer.tokenType
        if (lexer.tokenEnd != text.length) return null
        lexer.advance()
        return if (lexer.tokenType == null) type else null
    }
}

/** A module is found by the compiler in a file named after it, so that file is renamed with the module. */
private fun isModuleInFileOfSameName(symbol: AstSymbol, file: PsiFile): Boolean =
    symbol.kind == "module" && file.name == "${symbol.name}.bwsl"

private fun collectRenameEdits(declaration: PsiElement, usages: Array<UsageInfo>): List<RenameEdit> {
    val edits = ArrayList<RenameEdit>()
    edits += RenameEdit(declaration.containingFile, declaration.textRange)
    for (usage in usages) {
        val file = usage.file?.takeIf { it.language == BwslLanguage } ?: continue
        val segment = usage.segment ?: continue
        edits += RenameEdit(file, TextRange(segment.startOffset, segment.endOffset))
    }
    return edits.distinct()
}

private fun collectRenameEdits(declaration: PsiElement, references: Collection<PsiReference>): List<RenameEdit> {
    val edits = ArrayList<RenameEdit>()
    edits += RenameEdit(declaration.containingFile, declaration.textRange)
    for (reference in references) {
        val file = reference.element.containingFile?.takeIf { it.language == BwslLanguage } ?: continue
        edits += RenameEdit(file, reference.rangeInElement.shiftRight(reference.element.textRange.startOffset))
    }
    return edits.distinct()
}

/**
 * Checks that every edit is safe to apply and returns what is wrong, or null if all are. An edit is
 * unsafe when its file has unsaved changes (the compiler's positions are from the saved file), or
 * when the text at its position is not [oldName] (the file has drifted from what the compiler saw).
 */
private fun checkEdits(edits: List<RenameEdit>, oldName: String): String? {
    val documents = FileDocumentManager.getInstance()
    for (edit in edits) {
        val virtualFile = edit.file.virtualFile ?: return "${edit.file.name} is not backed by a file."
        if (documents.isFileModified(virtualFile)) {
            return "${edit.file.name} has unsaved changes. Save it and let the compiler re-check it, then rename."
        }
        val text = edit.file.text
        val matches = edit.range.endOffset <= text.length && text.substring(edit.range.startOffset, edit.range.endOffset) == oldName
        if (!matches) {
            return "${edit.file.name} no longer matches what the compiler last saw. Save it and let the compiler re-check it, then rename."
        }
    }
    return null
}

/**
 * Checks that the compiler's view of every indexed BWSL file ([collectIndexedFiles]) is current -
 * see [checkCompilerViewOf] - and returns what is wrong, or null if all are. A file the compiler
 * has not seen, or has not seen in its present state, may hold usages a rename would silently leave
 * behind.
 */
private fun checkCompilerViewIsCurrent(project: Project): String? {
    val files = collectIndexedFiles(project)
    val filesByKey = files.associateBy { normalizePathKey(it.path) }
    val modulePaths = collectModulePaths(project)
    return files.firstNotNullOfOrNull { checkCompilerViewOf(it, filesByKey, modulePaths) }
}

/** Replaces each edit's text with [newName]; within a file the edits go last-to-first so offsets stay valid. */
private fun applyEdits(project: Project, edits: List<RenameEdit>, newName: String) {
    val psiDocuments = PsiDocumentManager.getInstance(project)
    for ((file, fileEdits) in edits.groupBy { it.file }) {
        val document = psiDocuments.getDocument(file) ?: continue
        for (edit in fileEdits.sortedByDescending { it.range.startOffset }) {
            document.replaceString(edit.range.startOffset, edit.range.endOffset, newName)
        }
        psiDocuments.commitDocument(document)
    }
}
