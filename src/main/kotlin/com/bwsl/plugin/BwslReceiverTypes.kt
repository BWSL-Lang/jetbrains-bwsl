package com.bwsl.plugin

import com.google.gson.JsonObject
import com.intellij.psi.PsiFile

/** The type of the expression before a `.`: a type's name as written (`float3`, `Light`, `Mod::Light`), and whether it is an array of it. */
data class ReceiverType(val name: String, val isArray: Boolean = false)

private val VECTOR_TYPE = Regex("^(float|int|uint|double)([234])$")
private val MATRIX_TYPE = Regex("^(d?mat)([234])$")
private val ELEMENT_TYPE = Regex("""^([A-Za-z_]\w*(?:::[A-Za-z_]\w*)?)\s*\[""")
private const val POSITION_COMPONENTS = "xyzw"
private const val COLOR_COMPONENTS = "rgba"
private const val MAX_SWIZZLE_LENGTH = 4

/** What is known about the file at the caret, for working out a receiver's type and what it can be followed by. */
class ReceiverContext(
    val root: AstRoot,
    val raw: JsonObject,
    val line: Int,
    val column: Int,
    /** `import Module as Alias`: the alias, and the module's name. */
    val aliases: Map<String, String>,
    /** The text of the file the position is in, where an array local's element type is written. */
    val text: String
)

/** One step of an expression before a `.`: where it starts, then what is taken from it one `.` or `[ ]` at a time. */
private sealed interface Step {
    /** `x`, `self`, or `Module::CONSTANT`. */
    data class Name(val name: String, val qualifier: String?) : Step

    /** `f(...)`, `Module::f(...)`, `Struct(...)` or `float3(...)`. */
    data class Call(val name: String, val qualifier: String?) : Step

    /** `.field` or a swizzle. */
    data class Field(val name: String) : Step

    /** `.method(...)`. */
    data class Method(val name: String) : Step

    /** `[ ... ]`. */
    data object Index : Step
}

/**
 * What the expression ending at [receiverEnd] (the token before a `.`) can be followed by: a struct's
 * fields and methods, a vector's swizzles, an array's `length`. [typedPrefix] is what has been typed
 * after the dot: a swizzle in progress (`xy`) is offered with each component that can follow it.
 *
 * The expression is read from the tokens, so it is right for code typed since the last compile; the
 * types of the names in it come from the last compile's AST, as for the locals. Empty when the type
 * of the expression cannot be worked out: there is no guess.
 */
fun collectReceiverMembers(file: PsiFile, dotOffset: Int, context: ReceiverContext, typedPrefix: String): List<DeclaredName> {
    val leaves = collectLeaves(file)
    val dotIndex = leaves.indexOfFirst { it.range.startOffset == dotOffset && it.type == BwslTokenTypes.DOT }
    if (dotIndex <= 0) return emptyList()
    val steps = parseReceiver(leaves, dotIndex - 1) ?: return emptyList()
    val type = deduceType(steps, context) ?: return emptyList()
    return collectMembersOfType(type, context, typedPrefix)
}

/** The members of a value of [type]. */
internal fun collectMembersOfType(type: ReceiverType, context: ReceiverContext, typedPrefix: String): List<DeclaredName> {
    if (type.isArray) return listOf(DeclaredName("length", DeclaredName.Kind.FUNCTION, "int", emptyList()))
    VECTOR_TYPE.matchEntire(type.name)?.let { match ->
        return collectSwizzles(match.groupValues[1], match.groupValues[2].toInt(), typedPrefix)
    }
    val struct = findStruct(context, type.name) ?: return emptyList()
    return describeFieldsOf(struct) + describeMethodsOf(struct)
}

/**
 * The swizzles of an [components]-component vector of [scalar]: each component of either family
 * (`xyzw` or `rgba`, which do not mix), the prefixes of each family that are as long as the vector
 * allows, and the swizzle being typed ([typedPrefix]) followed by each component that can come next.
 */
private fun collectSwizzles(scalar: String, components: Int, typedPrefix: String): List<DeclaredName> {
    val families = listOf(POSITION_COMPONENTS.take(components), COLOR_COMPONENTS.take(components))
    val names = LinkedHashSet<String>()
    for (family in families) {
        family.forEach { names += it.toString() }
        for (length in 2..family.length) names += family.take(length)
    }
    val family = families.firstOrNull { typedPrefix.isNotEmpty() && typedPrefix.all { c -> c in it } }
    if (family != null && typedPrefix.length < MAX_SWIZZLE_LENGTH) {
        names += typedPrefix
        family.forEach { names += typedPrefix + it }
    }
    return names.map { DeclaredName(it, DeclaredName.Kind.FIELD, if (it.length == 1) scalar else "$scalar${it.length}") }
}

private fun deduceType(steps: List<Step>, context: ReceiverContext): ReceiverType? {
    var type = deduceBaseType(steps.first(), context)
    for (step in steps.drop(1)) {
        val current = type ?: return null
        type = when (step) {
            is Step.Field -> deduceFieldType(current, step.name, context)
            is Step.Method -> deduceMethodType(current, step.name, context)
            is Step.Index -> deduceIndexedType(current)
            else -> null
        }
    }
    return type
}

private fun deduceBaseType(step: Step, context: ReceiverContext): ReceiverType? {
    val enclosingStruct = findEnclosingEntry(context.root, context.raw, context.line, context.column)
        ?.let { findEnclosingStruct(it, context.line, context.column) }
    when (step) {
        is Step.Name -> {
            if (step.qualifier != null) return null
            if (step.name == "self") return enclosingStruct?.getStringOrNull("name")?.let { ReceiverType(it) }
            collectVisibleLocalsAt(context.root, context.raw, context.line, context.column)
                .firstOrNull { it.name == step.name }?.let { local ->
                    val type = if (local.isArray && local.type == "array") readElementType(context, local.typePosition) else local.type
                    return type?.let { ReceiverType(it, local.isArray) }
                }
            enclosingStruct?.let { describeFieldsOf(it) }?.firstOrNull { it.name == step.name }?.let { return parseReceiverType(it.type) }
            return collectNamesVisibleAt(context.root, context.raw, context.line, context.column)
                .firstOrNull { it.kind == DeclaredName.Kind.CONSTANT && it.name == step.name }?.let { parseReceiverType(it.type) }
        }
        is Step.Call -> return deduceCallType(step, context)
        else -> return null
    }
}

private fun deduceCallType(call: Step.Call, context: ReceiverContext): ReceiverType? {
    val candidates = if (call.qualifier != null) {
        findModuleNamed(context.raw, context.aliases[call.qualifier] ?: call.qualifier)?.let { describeMembersOfModule(it) }.orEmpty()
    } else {
        collectNamesVisibleAt(context.root, context.raw, context.line, context.column)
    }
    val returnTypes = candidates.filter { it.kind == DeclaredName.Kind.FUNCTION && it.name == call.name }.mapNotNull { it.type }.distinct()
    if (returnTypes.size == 1) return parseReceiverType(returnTypes.single())
    if (returnTypes.isNotEmpty()) return null // overloads that return different types
    // A constructor: `Light(...)` or `float3(...)`.
    if (VECTOR_TYPE.matches(call.name) || findStruct(context, call.name) != null) return ReceiverType(call.name)
    return null
}

private fun deduceFieldType(receiver: ReceiverType, name: String, context: ReceiverContext): ReceiverType? {
    if (receiver.isArray) return null
    VECTOR_TYPE.matchEntire(receiver.name)?.let { match ->
        val components = match.groupValues[2].toInt()
        val isSwizzle = name.isNotEmpty() && name.length <= MAX_SWIZZLE_LENGTH &&
            (name.all { it in POSITION_COMPONENTS.take(components) } || name.all { it in COLOR_COMPONENTS.take(components) })
        val scalar = match.groupValues[1]
        return if (isSwizzle) ReceiverType(if (name.length == 1) scalar else "$scalar${name.length}") else null
    }
    val struct = findStruct(context, receiver.name) ?: return null
    return describeFieldsOf(struct).firstOrNull { it.name == name }?.let { parseReceiverType(it.type) }
}

private fun deduceMethodType(receiver: ReceiverType, name: String, context: ReceiverContext): ReceiverType? {
    if (receiver.isArray) return if (name == "length") ReceiverType("int") else null
    val struct = findStruct(context, receiver.name) ?: return null
    val returnTypes = describeMethodsOf(struct).filter { it.name == name }.mapNotNull { it.type }.distinct()
    return returnTypes.singleOrNull()?.let { parseReceiverType(it) }
}

/** What `value[i]` is: an array's element, a matrix's column, a vector's component. */
private fun deduceIndexedType(receiver: ReceiverType): ReceiverType? {
    if (receiver.isArray) return ReceiverType(receiver.name)
    MATRIX_TYPE.matchEntire(receiver.name)?.let { match ->
        val scalar = if (match.groupValues[1] == "dmat") "double" else "float"
        return ReceiverType("$scalar${match.groupValues[2]}")
    }
    VECTOR_TYPE.matchEntire(receiver.name)?.let { return ReceiverType(it.groupValues[1]) }
    return null
}

/** The element type of an array local, read where the declaration writes it (`Light` in `Light[2] lights;`). */
private fun readElementType(context: ReceiverContext, typePosition: Pair<Int, Int>?): String? {
    val offset = typePosition?.let { SourcePositions(context.text).toOffset(it.first, it.second) } ?: return null
    return ELEMENT_TYPE.find(context.text.substring(offset.coerceIn(0, context.text.length)))?.groupValues?.get(1)
}

/** `float[4]` is an array of `float`; anything else is the type itself. */
private fun parseReceiverType(type: String?): ReceiverType? {
    if (type.isNullOrBlank()) return null
    return if (type.endsWith("]")) ReceiverType(type.substringBefore('['), isArray = true) else ReceiverType(type)
}

/**
 * The struct called [typeName] (`Light`, or `Module::Light`): in the module or pipeline the caret is in,
 * or in the module the qualifier names, or in any module of the compiled payload.
 */
private fun findStruct(context: ReceiverContext, typeName: String): JsonObject? {
    val module = typeName.substringBeforeLast("::", "")
    val name = typeName.substringAfterLast("::")
    val containers = if (module.isNotEmpty()) {
        listOfNotNull(findModuleNamed(context.raw, context.aliases[module] ?: module))
    } else {
        listOfNotNull(findEnclosingEntry(context.root, context.raw, context.line, context.column)) +
            context.raw.getObjectsOrEmpty("modules")
    }
    return containers.asSequence().flatMap { it.getObjectsOrEmpty("structs").asSequence() }
        .firstOrNull { it.getStringOrNull("name") == name }
}

// --- reading the expression before the dot ------------------------------------------------------------

/** The steps of the expression whose last token is at [lastIndex], or null when it is not one this can read (a parenthesised or arithmetic expression). */
private fun parseReceiver(leaves: List<Leaf>, lastIndex: Int): List<Step>? {
    val steps = ArrayDeque<Step>()
    var index = lastIndex
    while (true) {
        if (index < 0) return null
        while (leaves[index].type == BwslTokenTypes.RBRACKET) {
            val open = findOpening(leaves, index, BwslTokenTypes.RBRACKET, BwslTokenTypes.LBRACKET) ?: return null
            steps.addFirst(Step.Index)
            index = open - 1
            if (index < 0) return null
        }
        val isCall = leaves[index].type == BwslTokenTypes.RPAREN
        val nameIndex = if (isCall) (findOpening(leaves, index, BwslTokenTypes.RPAREN, BwslTokenTypes.LPAREN) ?: return null) - 1 else index
        val name = leaves.getOrNull(nameIndex)?.takeIf { it.node.text.matches(Regex("[A-Za-z_]\\w*")) } ?: return null
        val before = leaves.getOrNull(nameIndex - 1)
        when (before?.type) {
            BwslTokenTypes.DOT -> {
                steps.addFirst(if (isCall) Step.Method(name.node.text) else Step.Field(name.node.text))
                index = nameIndex - 2
            }
            BwslTokenTypes.COLONCOLON -> {
                val qualifier = leaves.getOrNull(nameIndex - 2)?.node?.text ?: return null
                steps.addFirst(if (isCall) Step.Call(name.node.text, qualifier) else Step.Name(name.node.text, qualifier))
                return steps.toList()
            }
            else -> {
                steps.addFirst(if (isCall) Step.Call(name.node.text, null) else Step.Name(name.node.text, null))
                return steps.toList()
            }
        }
    }
}

/** The index of the token that opens the bracket closed at [closeIndex], or null if it is not closed. */
private fun findOpening(leaves: List<Leaf>, closeIndex: Int, close: com.intellij.psi.tree.IElementType, open: com.intellij.psi.tree.IElementType): Int? {
    var depth = 0
    for (index in closeIndex downTo 0) {
        when (leaves[index].type) {
            close -> depth++
            open -> if (--depth == 0) return index
        }
    }
    return null
}
