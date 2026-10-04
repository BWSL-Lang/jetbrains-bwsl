package com.bwsl.plugin

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException

/** One occurrence of the name being renamed: the declaration or a usage. */
private data class RenameEdit(val file: PsiFile, val range: TextRange)

/**
 * Rename for BWSL declarations: functions, methods, structs, fields, parameters, locals, constants,
 * attributes, fragment outputs and modules. Every usage is one the compiler's reference index found
 * (see [findUsagesOf]); the edits replace each occurrence's text.
 *
 * BWSL's PSI has no named elements, so the platform's default rename can't edit it: this processor
 * applies the edits itself. It works from the compiler's last result, so it refuses to rename when
 * a file has unsaved changes, or any compiled file no longer matches the text the compiler saw: a
 * usage added since would be missed and a moved one would be edited at the wrong place.
 */
class BwslRenameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = findDeclarationIdentityOf(element) != null

    /**
     * A module is found by the compiler in a file named after it, so renaming a module that lives in
     * a file of the same name renames that file too.
     */
    override fun prepareRenaming(element: PsiElement, newName: String, allRenames: MutableMap<PsiElement, String>) {
        val symbol = findDeclarationIdentityOf(element)?.symbol ?: return
        val file = element.containingFile ?: return
        if (symbol.kind == "module" && file.name == "${symbol.name}.bwsl") allRenames[file] = "$newName.bwsl"
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
 * Checks that every compiled BWSL file in the project is still exactly the text its cached AST was
 * built from, and returns what is wrong, or null if all are. A file that has changed may hold usages
 * the compiler has not seen, which a rename would silently leave behind.
 */
private fun checkCompilerViewIsCurrent(project: Project): String? {
    for (file in collectPayloadFiles(project)) {
        val path = file.virtualFile?.path ?: continue
        if (!BwslAstCache.doesTextMatchCompiledText(path, file.text)) {
            return "${file.name} has changed since the compiler last checked it. Save it and let the compiler re-check it, then rename."
        }
    }
    return null
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
