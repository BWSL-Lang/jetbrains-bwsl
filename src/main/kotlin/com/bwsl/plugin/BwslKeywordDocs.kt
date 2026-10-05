package com.bwsl.plugin

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil

/** Where the official documentation lives: `https://www.bwsl.dev/docs/<page>`. */
const val DOCS_BASE_URL = "https://www.bwsl.dev/docs"

private const val MODULES = "language/modules"
private const val PIPELINE = "language/pipeline"
private const val PASS = "language/pass"
private const val SHADER_IO = "language/shader-io"
private const val ATTRIBUTES = "language/vertex-attributes"
private const val RESOURCES = "language/resources"
private const val VARIANTS = "language/shader-variants"
private const val LOOPS = "language/loops"
private const val EVAL = "language/eval"
private const val PASS_BLOCKS = "language/pass-blocks"
private const val COMPUTE = "language/compute-shaders"
private const val STRUCTS = "types/structs"
private const val ENUMS = "types/enums"
private const val FUNCTIONS = "language/functions"
private const val CONSTANTS = "language#variables-and-constants"

/**
 * The documentation page each keyword is explained on. A keyword that is not here (`if`, `return`, the type
 * names, ...) has no page of its own to go to. `attributes` is told apart by what follows it, see
 * [findKeywordPageFor].
 */
private val KEYWORD_PAGES: Map<IElementType, String> = mapOf(
    BwslTokenTypes.KW_MODULE to MODULES, BwslTokenTypes.KW_SUBMODULE to MODULES, BwslTokenTypes.KW_IMPORT to MODULES,
    BwslTokenTypes.KW_USING to MODULES, BwslTokenTypes.KW_AS to MODULES, BwslTokenTypes.KW_EXTENDS to MODULES,
    BwslTokenTypes.KW_PIPELINE to PIPELINE, BwslTokenTypes.KW_VERTEX to PIPELINE, BwslTokenTypes.KW_FRAGMENT to PIPELINE,
    BwslTokenTypes.KW_PASS to PASS, BwslTokenTypes.KW_PASS_BLOCK to PASS_BLOCKS,
    BwslTokenTypes.KW_USE to ATTRIBUTES,
    BwslTokenTypes.KW_INPUTS to SHADER_IO, BwslTokenTypes.KW_OUTPUTS to SHADER_IO,
    BwslTokenTypes.KW_RESOURCES to RESOURCES, BwslTokenTypes.KW_BUFFER to RESOURCES, BwslTokenTypes.KW_CBUFFER to RESOURCES,
    BwslTokenTypes.KW_SAMPLER to RESOURCES, BwslTokenTypes.KW_READONLY to RESOURCES,
    BwslTokenTypes.KW_READWRITE to RESOURCES, BwslTokenTypes.KW_WRITEONLY to RESOURCES,
    BwslTokenTypes.KW_VARIANTS to VARIANTS, BwslTokenTypes.KW_CONSTRAINT to VARIANTS, BwslTokenTypes.KW_RULES to VARIANTS,
    BwslTokenTypes.KW_REQUIRE to VARIANTS, BwslTokenTypes.KW_CONFLICT to VARIANTS,
    BwslTokenTypes.KW_FOR to LOOPS, BwslTokenTypes.KW_FOREACH to LOOPS, BwslTokenTypes.KW_WHILE to LOOPS,
    BwslTokenTypes.KW_LOOP to LOOPS, BwslTokenTypes.KW_UNTIL to LOOPS, BwslTokenTypes.KW_BY to LOOPS,
    BwslTokenTypes.KW_IN to LOOPS, BwslTokenTypes.KW_SKIP to LOOPS, BwslTokenTypes.KW_BREAK to LOOPS,
    BwslTokenTypes.KW_CONTINUE to LOOPS,
    BwslTokenTypes.KW_EVAL to EVAL,
    BwslTokenTypes.KW_STRUCT to STRUCTS, BwslTokenTypes.KW_SELF to STRUCTS, BwslTokenTypes.KW_ENUM to ENUMS,
    BwslTokenTypes.KW_COMPUTE to COMPUTE,
    BwslTokenTypes.KW_RETURN to FUNCTIONS, BwslTokenTypes.KW_CONST to CONSTANTS
)

/** The documentation pages the keywords lead to (without the section of a page), for checking that they exist. */
internal fun collectKeywordPages(): Set<String> =
    (KEYWORD_PAGES.values + ATTRIBUTES + SHADER_IO).map { it.substringBefore('#') }.toSet()

/** The next token after [element] that is not whitespace or a comment. */
private fun findNextSignificantLeaf(element: PsiElement): PsiElement? =
    generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
        .firstOrNull { it !is PsiWhiteSpace && it !is PsiComment && it.node?.elementType != BwslTokenTypes.LINE_COMMENT &&
            it.node?.elementType != BwslTokenTypes.BLOCK_COMMENT }

/**
 * The page (below [DOCS_BASE_URL]) that explains the keyword at [token], or null when it is not one that has
 * a page. `attributes` is the declaration of the pipeline's attributes when a `{` follows, and the namespace
 * of the pass's values (`attributes.position`) when a `.` does; `input` and `output`, which are ordinary
 * names to the lexer, count only as that namespace, with a `.` after them.
 */
internal fun findKeywordPageFor(token: PsiElement): String? {
    val type = token.node?.elementType ?: return null
    if (type == BwslTokenTypes.KW_ATTRIBUTES) {
        return if (findNextSignificantLeaf(token)?.node?.elementType == BwslTokenTypes.DOT) SHADER_IO else ATTRIBUTES
    }
    if (type == BwslTokenTypes.IDENTIFIER && (token.text == "input" || token.text == "output")) {
        return if (findNextSignificantLeaf(token)?.node?.elementType == BwslTokenTypes.DOT) SHADER_IO else null
    }
    return KEYWORD_PAGES[type]
}
