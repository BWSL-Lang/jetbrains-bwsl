package com.bwsl.plugin

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiFile

/** Where in a BWSL file the caret is, for the live templates: which kind of block holds it. */
enum class BwslTemplateScope {
    /** Outside every block: where a `module` or `pipeline` goes. */
    TOP_LEVEL,
    MODULE,
    PIPELINE,
    PASS,
    STRUCT,
    /** A function's or a stage's body, or a block inside one: where statements go. */
    STATEMENT,
    /** A block that is a list of entries (`attributes`, `resources`, `outputs`, an `enum`, ...): no template fits. */
    OTHER,
}

/** The keywords whose block is a list of entries, not declarations or statements. */
private val ENTRY_BLOCK_KEYWORDS = setOf(
    BwslTokenTypes.KW_ATTRIBUTES, BwslTokenTypes.KW_RESOURCES, BwslTokenTypes.KW_VARIANTS, BwslTokenTypes.KW_OUTPUTS,
    BwslTokenTypes.KW_INPUTS, BwslTokenTypes.KW_ENUM, BwslTokenTypes.KW_USE, BwslTokenTypes.KW_CONSTRAINT,
    BwslTokenTypes.KW_RULES, BwslTokenTypes.KW_COMPUTE_GRAPH,
)

/**
 * The scope at [offset] of [file], read from its tokens (so it follows text being typed, like folding): the innermost
 * open `{` and what owns it, by the first token of the statement it ends. A block inside a statement block is a
 * statement block too. Null inside a comment or a string, where no template belongs.
 */
fun findTemplateScope(file: PsiFile, offset: Int): BwslTemplateScope? {
    val leaves = collectLeaves(file)
    val before = leaves.lastOrNull { it.range.startOffset < offset }
    if (before != null && (before.isComment || before.type == BwslTokenTypes.STRING_LIT)) {
        // A line comment runs to the end of its line, so the caret right after its text is still in it.
        val isAtLineCommentEnd = before.type == BwslTokenTypes.LINE_COMMENT && offset == before.range.endOffset
        if (offset < before.range.endOffset || isAtLineCommentEnd) return null
    }

    val scopes = ArrayList<BwslTemplateScope>()
    // The `(` and `[` still open in the innermost block: in an argument list or an index no template belongs.
    var openGroups = 0
    for ((index, leaf) in leaves.withIndex()) {
        if (leaf.range.startOffset >= offset) break
        when (leaf.type) {
            BwslTokenTypes.LBRACE -> {
                scopes += classifyBlock(leaves, index, scopes.lastOrNull())
                openGroups = 0
            }
            BwslTokenTypes.RBRACE -> if (scopes.isNotEmpty()) scopes.removeAt(scopes.size - 1)
            BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> openGroups++
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> openGroups = (openGroups - 1).coerceAtLeast(0)
        }
    }
    if (openGroups > 0) return null
    return scopes.lastOrNull() ?: BwslTemplateScope.TOP_LEVEL
}

/** What the `{` at [open] opens, inside a block of scope [parent]. */
private fun classifyBlock(leaves: List<Leaf>, open: Int, parent: BwslTemplateScope?): BwslTemplateScope {
    if (parent == BwslTemplateScope.STATEMENT) return BwslTemplateScope.STATEMENT
    if (parent == BwslTemplateScope.OTHER) return BwslTemplateScope.OTHER
    // The first token of the statement the `{` ends: after the last `;`, `{` or `}` before it, outside parentheses.
    var depth = 0
    var start = open
    for (i in open - 1 downTo 0) {
        val type = leaves[i].type
        if (leaves[i].isComment) continue
        when (type) {
            BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> depth++
            BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> depth--
            BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE -> if (depth == 0) break
        }
        start = i
    }
    val first = leaves.getOrNull(start)?.takeIf { start < open }?.type
    return when (first) {
        BwslTokenTypes.KW_MODULE, BwslTokenTypes.KW_SUBMODULE -> BwslTemplateScope.MODULE
        BwslTokenTypes.KW_PIPELINE -> BwslTemplateScope.PIPELINE
        BwslTokenTypes.KW_PASS -> BwslTemplateScope.PASS
        BwslTokenTypes.KW_STRUCT -> BwslTemplateScope.STRUCT
        in ENTRY_BLOCK_KEYWORDS -> BwslTemplateScope.OTHER
        // A function's body (`name :: (...) -> T {`), a stage's (`vertex {`), or any other block of code.
        else -> BwslTemplateScope.STATEMENT
    }
}

/**
 * A BWSL live-template context: a BWSL file, and with [scopes] only where the caret is in one of them. The contexts
 * are registered in plugin.xml (`liveTemplateContext`), the templates in `liveTemplates/BWSL.xml`.
 */
abstract class BwslTemplateContext(presentableName: String, private val scopes: Set<BwslTemplateScope>?) : TemplateContextType(presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file
        if (file.language != BwslLanguage) return false
        val scope = findTemplateScope(file, templateActionContext.startOffset) ?: return false
        return scopes == null || scope in scopes
    }
}

/** Anywhere in a BWSL file; the parent of the others in the settings. */
class BwslAnyTemplateContext : BwslTemplateContext("BWSL", null)

/** Outside every block: `module` and `pipeline`. */
class BwslTopLevelTemplateContext : BwslTemplateContext("Top level", setOf(BwslTemplateScope.TOP_LEVEL))

/** Directly in a pipeline: `pass`. */
class BwslPipelineTemplateContext : BwslTemplateContext("Pipeline", setOf(BwslTemplateScope.PIPELINE))

/** Where a function can be declared: in a module, a pipeline, a pass or a struct (a method). */
class BwslDeclarationTemplateContext : BwslTemplateContext(
    "Declaration",
    setOf(BwslTemplateScope.MODULE, BwslTemplateScope.PIPELINE, BwslTemplateScope.PASS, BwslTemplateScope.STRUCT)
)

/** In a function's or stage's body: the loops. */
class BwslStatementTemplateContext : BwslTemplateContext("Statement", setOf(BwslTemplateScope.STATEMENT))
