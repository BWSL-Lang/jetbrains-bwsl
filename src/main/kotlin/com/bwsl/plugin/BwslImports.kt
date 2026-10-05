package com.bwsl.plugin

import com.google.gson.JsonObject
import com.intellij.psi.PsiFile

private const val PROBE_MODULE_PREFIX = "BwslProbe"

/** A member of a module the file does not import, which can be written as `Module::name` once it is imported. */
data class ImportableName(val module: String, val name: DeclaredName)

/** Text to put into a file: [text] at [offset]. */
data class TextInsertion(val offset: Int, val text: String)

/**
 * The members of every module in a cached AST that is not in [excludedModules]: the module files of the
 * project and its module paths, the compiler's standard modules once they have been fetched and
 * compiled (see [BwslStdlibSources.writeProbeFiles]), and the other modules of the file being edited.
 * [excludedModules] holds the modules the file imports already and the one the caret is in, whose
 * names are visible without an import. The probe modules themselves are not offered.
 */
fun collectImportableNames(excludedModules: Set<String>): List<ImportableName> {
    val found = LinkedHashSet<ImportableName>()
    for (path in BwslAstCache.collectPaths().sorted()) {
        val root = BwslAstCache.findRoot(path) ?: continue
        val raw = BwslAstCache.findRawRoot(path) ?: continue
        for (module in raw.getObjectsOrEmpty("modules")) {
            val name = module.getStringOrNull("name") ?: continue
            if (name in excludedModules || name.startsWith(PROBE_MODULE_PREFIX)) continue
            for (member in describeMembersOfModule(module)) found += ImportableName(name, member)
        }
    }
    return found.toList()
}

/** The name of the module or pipeline of the compiled file that holds the 1-based position, or null. */
fun findEnclosingDeclarationName(root: AstRoot, rawJson: JsonObject, line: Int, column: Int): String? =
    findEnclosingEntry(root, rawJson, line, column)?.getStringOrNull("name")

/**
 * Where, and what, to insert so that [file] imports [module] at [offset]: a line `import Module` in
 * the module or pipeline that holds the offset, after its last import (with the same indent) or, if it
 * has none, as the first thing in it. Null when that module or pipeline imports it already, or the
 * offset is not inside one. Works on the text, so it is right for code typed since the last compile.
 */
fun findImportInsertion(file: PsiFile, offset: Int, module: String): TextInsertion? {
    val text = file.text
    val leaves = collectLeaves(file)
    val block = findTopLevelBlock(leaves, offset) ?: return null

    var depth = 0
    var lastImportEnd: Leaf? = null
    var lastImportStart: Leaf? = null
    for (leaf in block.inside) {
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> depth++
            BwslTokenTypes.RBRACE -> depth--
            BwslTokenTypes.KW_IMPORT -> if (depth == 0) {
                val imported = leaf.nextSignificant ?: continue
                val hasAlias = imported.nextSignificant?.type == BwslTokenTypes.KW_AS
                if (imported.node.text == module) return null
                lastImportStart = leaf
                lastImportEnd = if (hasAlias) imported.nextSignificant?.nextSignificant ?: imported else imported
            }
        }
    }

    val importLine = "import $module"
    if (lastImportStart != null && lastImportEnd != null) {
        val indent = indentOfLineAt(text, lastImportStart.range.startOffset)
        return TextInsertion(lastImportEnd.range.endOffset, "\n$indent$importLine")
    }

    // No imports yet: first in the block, indented like what is in it (or one level in from the line of the brace).
    val firstInside = block.inside.firstOrNull()
    val braceLineIndent = indentOfLineAt(text, block.open.range.startOffset)
    val indent = if (firstInside != null && firstInside.isFirstOnLine) indentOfLineAt(text, firstInside.range.startOffset)
    else "$braceLineIndent    "
    val needsLineBreakAfter = firstInside != null && !firstInside.isFirstOnLine
    return TextInsertion(block.open.range.endOffset, "\n$indent$importLine" + if (needsLineBreakAfter) "\n" else "")
}

/** The `{ ... }` of a top-level module or pipeline, and the tokens strictly between its braces. */
private class TopLevelBlock(val open: Leaf, val inside: List<Leaf>)

private fun findTopLevelBlock(leaves: List<Leaf>, offset: Int): TopLevelBlock? {
    var depth = 0
    var open: Leaf? = null
    var inside = ArrayList<Leaf>()
    for (leaf in leaves) {
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> {
                if (depth == 0) {
                    open = leaf
                    inside = ArrayList()
                } else {
                    inside += leaf
                }
                depth++
            }
            BwslTokenTypes.RBRACE -> {
                depth = (depth - 1).coerceAtLeast(0)
                if (depth == 0 && open != null) {
                    if (offset > open.range.startOffset && offset <= leaf.range.startOffset) return TopLevelBlock(open, inside)
                    open = null
                } else if (open != null) {
                    inside += leaf
                }
            }
            else -> if (open != null) inside += leaf
        }
    }
    // A block that is not closed yet (code being typed): it runs to the end of the file.
    return open?.takeIf { offset > it.range.startOffset }?.let { TopLevelBlock(it, inside) }
}

/** The whitespace at the start of the line that holds [offset]. */
private fun indentOfLineAt(text: String, offset: Int): String {
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (offset == 0) 0 else it + 1 }
    return text.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
}
