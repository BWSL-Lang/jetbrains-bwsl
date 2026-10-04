package com.bwsl.plugin

import com.intellij.psi.PsiFile

private val CORE_TYPE = Regex("^(bool|u?int(64)?(x?[234])?|float[234]?|double[234]?|d?mat[234])$")
private val WORD = Regex("[A-Za-z_]\\w*")

private val ASSIGNMENT_OPERATORS = setOf(
    BwslTokenTypes.EQ, BwslTokenTypes.PLUSEQ, BwslTokenTypes.MINUSEQ, BwslTokenTypes.STAREQ, BwslTokenTypes.SLASHEQ,
    BwslTokenTypes.PERCENTEQ, BwslTokenTypes.AMPEQ, BwslTokenTypes.PIPEEQ, BwslTokenTypes.CARETEQ,
    BwslTokenTypes.LSHIFTEQ, BwslTokenTypes.RSHIFTEQ
)

/** What can stand before the type of a declaration: the end of a statement, an opening brace or parenthesis, `const`. */
private val DECLARATION_BOUNDARIES = setOf(
    BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE, BwslTokenTypes.KW_CONST, BwslTokenTypes.LPAREN
)

private val FLOAT_TYPES = listOf("float", "float2", "float3", "float4")
private val NUMERIC_TYPES = listOf("float", "int", "uint", "double").flatMap { base -> listOf(base) + (2..4).map { "$base$it" } }

/** Whether a value of type [actual] fits where one of [expected] is wanted: the same type, ignoring a module qualifier. */
fun doesTypeMatch(actual: String?, expected: Set<String>): Boolean =
    actual != null && expected.isNotEmpty() && expected.any { it.substringAfterLast("::") == actual.substringAfterLast("::") }

/**
 * The types a value written at [caretOffset] of [file] can have, from what is before the caret, or an
 * empty set when nothing is expected or it cannot be told. The cases are the initialiser of a
 * declaration (`float x = `), the right side of an assignment (`v.xy = `, `a += `), a `return`, and an
 * argument of a call to a function, a method or an intrinsic (`f(a, `): the type of the parameter there,
 * for every overload that has one.
 *
 * Read from the tokens, so it is right for text newer than the last compile; the types behind it come
 * from the cached AST. Only what directly precedes the caret counts: in `a + ` nothing is expected.
 */
fun collectExpectedTypesAt(file: PsiFile, caretOffset: Int, context: ReceiverContext): Set<String> {
    val leaves = collectLeaves(file)
    var index = leaves.indexOfLast { it.range.endOffset <= caretOffset }
    // A word that ends at the caret is what is being typed, not what precedes it.
    if (index >= 0 && leaves[index].range.endOffset == caretOffset && leaves[index].node.text.matches(WORD)) index--
    // After `value.` or `Module::` the value being written is still the one that began before them.
    index = skipBackOverMemberAccess(leaves, index) ?: return emptySet()
    val previous = leaves.getOrNull(index) ?: return emptySet()
    return when {
        previous.type in ASSIGNMENT_OPERATORS -> collectExpectedTypesForAssignment(leaves, index, context)
        previous.type == BwslTokenTypes.KW_RETURN -> collectExpectedTypesForReturn(context)
        previous.type == BwslTokenTypes.COMMA || previous.type == BwslTokenTypes.LPAREN -> collectExpectedTypesForArgument(leaves, index, context)
        else -> emptySet()
    }
}

/** The index of the token before the member access or qualifier that ends at [index] (`a.b.` or `Mod::`), or [index] if it ends in neither; null if what is before a `.` cannot be read. */
private fun skipBackOverMemberAccess(leaves: List<Leaf>, index: Int): Int? {
    val last = leaves.getOrNull(index) ?: return index
    return when (last.type) {
        BwslTokenTypes.DOT -> parseReceiver(leaves, index - 1)?.startIndex?.minus(1)
        BwslTokenTypes.COLONCOLON -> index - 2
        else -> index
    }
}

/** `Type name = ` declares `name` as `Type`; `target = ` wants whatever `target` is. */
private fun collectExpectedTypesForAssignment(leaves: List<Leaf>, operatorIndex: Int, context: ReceiverContext): Set<String> {
    val name = leaves.getOrNull(operatorIndex - 1)
    val typeLeaf = leaves.getOrNull(operatorIndex - 2)
    if (leaves[operatorIndex].type == BwslTokenTypes.EQ && name?.type == BwslTokenTypes.IDENTIFIER && typeLeaf != null) {
        val declared = readDeclaredType(leaves, operatorIndex - 2)
        if (declared != null) return setOf(declared)
    }
    val type = deduceTypeOfExpressionEndingAt(leaves, operatorIndex - 1, context) ?: return emptySet()
    return if (type.isArray) emptySet() else setOf(type.name)
}

/** The type written in a declaration whose last token is at [typeIndex] (`float`, `Light`, `Mod::Light`), or null if what is there is not a declaration's type. */
private fun readDeclaredType(leaves: List<Leaf>, typeIndex: Int): String? {
    val typeLeaf = leaves[typeIndex]
    val text = typeLeaf.node.text
    if (!text.matches(WORD)) return null
    val before = leaves.getOrNull(typeIndex - 1)
    val isQualified = before?.type == BwslTokenTypes.COLONCOLON
    val boundary = leaves.getOrNull(if (isQualified) typeIndex - 3 else typeIndex - 1)
    if (boundary != null && boundary.type !in DECLARATION_BOUNDARIES) return null
    if (!CORE_TYPE.matches(text) && typeLeaf.type != BwslTokenTypes.IDENTIFIER) return null
    return if (isQualified) leaves.getOrNull(typeIndex - 2)?.node?.text?.let { "$it::$text" } else text
}

private fun collectExpectedTypesForReturn(context: ReceiverContext): Set<String> {
    val entry = findEnclosingEntry(context.root, context.raw, context.line, context.column) ?: return emptySet()
    val functions = entry.getObjectsOrEmpty("functions") +
        entry.getObjectsOrEmpty("passes").flatMap { it.getObjectsOrEmpty("functions") } +
        entry.getObjectsOrEmpty("structs").flatMap { it.getObjectsOrEmpty("methods") }
    val enclosing = functions.filter { it.doesRangeContain(context.line, context.column) }
        .minByOrNull { (it.getIntOrNull("endLine") ?: 0) - (it.getIntOrNull("line") ?: 0) } ?: return emptySet()
    return enclosing.getStringOrNull("returnType")?.takeIf { it.isNotBlank() && it != "void" }?.let { setOf(it) }.orEmpty()
}

/** The type of the parameter the caret is at, for each overload of the callee: `f(a, ` is the second parameter of `f`. */
private fun collectExpectedTypesForArgument(leaves: List<Leaf>, previousIndex: Int, context: ReceiverContext): Set<String> {
    var argumentIndex = 0
    var open = previousIndex
    if (leaves[previousIndex].type == BwslTokenTypes.COMMA) {
        var depth = 0
        open = -1
        for (index in previousIndex downTo 0) {
            when (leaves[index].type) {
                BwslTokenTypes.RPAREN, BwslTokenTypes.RBRACKET -> depth++
                BwslTokenTypes.LPAREN, BwslTokenTypes.LBRACKET -> if (depth == 0) { open = index; break } else depth--
                BwslTokenTypes.COMMA -> if (depth == 0) argumentIndex++
                BwslTokenTypes.SEMI, BwslTokenTypes.LBRACE, BwslTokenTypes.RBRACE -> if (depth == 0) return emptySet()
            }
        }
        if (open < 0 || leaves[open].type != BwslTokenTypes.LPAREN) return emptySet()
    }

    val callee = leaves.getOrNull(open - 1)?.takeIf { it.node.text.matches(WORD) } ?: return emptySet()
    val name = callee.node.text
    val before = leaves.getOrNull(open - 2)
    val overloads = when (before?.type) {
        BwslTokenTypes.DOT -> {
            val receiver = deduceTypeOfExpressionEndingAt(leaves, open - 3, context) ?: return emptySet()
            collectCallableOverloads(name, qualifier = null, receiver = receiver, context = context)
        }
        BwslTokenTypes.COLONCOLON -> {
            val qualifier = leaves.getOrNull(open - 3)?.node?.text ?: return emptySet()
            collectCallableOverloads(name, qualifier = qualifier, receiver = null, context = context)
        }
        else -> collectCallableOverloads(name, qualifier = null, receiver = null, context = context)
    }
    if (overloads.isEmpty() && before?.type != BwslTokenTypes.DOT && before?.type != BwslTokenTypes.COLONCOLON) {
        return collectIntrinsicParameterTypes(name, argumentIndex)
    }
    return overloads.mapNotNull { overload -> overload.parameters?.getOrNull(argumentIndex)?.substringBeforeLast(' ')?.takeIf { it.isNotBlank() } }.toSet()
}

/** What parameter [argumentIndex] of the intrinsic [name] takes, with the table's generic classes (`floatN`) spelt out as types. */
private fun collectIntrinsicParameterTypes(name: String, argumentIndex: Int): Set<String> =
    BwslIntrinsics.ALL.filter { it.name == name }.flatMap { intrinsic ->
        // `min(a, b, ...)` takes as many as are given: the last parameter's class goes on.
        val parameter = intrinsic.params.getOrNull(argumentIndex)
            ?: intrinsic.params.lastOrNull()?.takeIf { argumentIndex < intrinsic.maxParams }
        parameter?.let { expandIntrinsicTypeClass(it.type) }.orEmpty()
    }.toSet()

private fun expandIntrinsicTypeClass(typeClass: String): Set<String> = when (typeClass) {
    "floatN" -> FLOAT_TYPES.toSet()
    "floatVecN" -> FLOAT_TYPES.drop(1).toSet()
    "numeric" -> NUMERIC_TYPES.toSet()
    "scalar" -> setOf("float", "int", "uint", "double", "bool")
    "matN" -> setOf("mat2", "mat3", "mat4")
    "boolVecN" -> setOf("bool2", "bool3", "bool4")
    "texture" -> setOf("texture2D", "texture3D", "texture2DArray", "textureCube")
    "T" -> emptySet()
    else -> setOf(typeClass)
}
