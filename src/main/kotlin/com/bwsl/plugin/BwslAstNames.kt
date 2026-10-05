package com.bwsl.plugin

import com.google.gson.JsonObject
import com.intellij.psi.PsiFile

/** A function, struct, enum, constant, module or enum value that can be written at some position. */
data class DeclaredName(
    val name: String,
    val kind: Kind,
    /** A function's return type, a constant's type, or the enum an enum value belongs to. */
    val type: String? = null,
    /** A function's parameters as `type name`; null for anything that is not a function. */
    val parameters: List<String>? = null
) {
    enum class Kind(val label: String) {
        FUNCTION("function"),
        STRUCT("struct"),
        ENUM("enum"),
        CONSTANT("constant"),
        MODULE("module"),
        ENUM_VALUE("enum value"),
        FIELD("field")
    }
}

/** One `import Module` or `import Module as Alias` written in a file. */
data class ImportDeclaration(val module: String, val alias: String?) {
    /** What the module is called where the import is. */
    val visibleName: String get() = alias ?: module
}

/**
 * The imports written in [file], read from its tokens. bwslc's AST keeps an import's module but not
 * its alias, and completion works on text that may be newer than the last compile, so the imports are
 * read from what is in the editor.
 */
fun collectImportDeclarationsOf(file: PsiFile): List<ImportDeclaration> {
    val imports = ArrayList<ImportDeclaration>()
    for (leaf in collectLeaves(file)) {
        if (leaf.type != BwslTokenTypes.KW_IMPORT) continue
        val module = leaf.nextSignificant?.takeIf { it.type != BwslTokenTypes.KW_AS } ?: continue
        val alias = module.nextSignificant?.takeIf { it.type == BwslTokenTypes.KW_AS }?.nextSignificant
        imports += ImportDeclaration(module.node.text, alias?.node?.text)
    }
    return imports
}

/**
 * The functions, structs, enums and imported names that can be written at the 1-based ([line],
 * [column]) of the compiled file, from the cached AST ([rawJson], with [root] saying which top-level
 * declarations are the file's own): what the enclosing module or pipeline declares, what the enclosing
 * pass declares, and the functions and constants of a module that a `using` makes available without a
 * qualifier. The names of the modules the enclosing declaration imports come last.
 *
 * Like [collectVisibleLocalsAt] this answers "what could be typed here", not "what does this name
 * refer to", and it reads the last compile, so something declared since is not offered until the
 * file has been saved and compiled. Constants the enclosing declaration declares are not repeated
 * here: [collectVisibleLocalsAt] has them.
 */
fun collectNamesVisibleAt(root: AstRoot, rawJson: JsonObject, line: Int, column: Int): List<DeclaredName> {
    val entry = findEnclosingEntry(root, rawJson, line, column) ?: return emptyList()
    val names = ArrayList<DeclaredName>()
    names += describeFunctionsOf(entry) + describeTypesOf(entry)

    entry.getObjectsOrEmpty("passes").firstOrNull { it.doesRangeContain(line, column) }?.let { pass ->
        names += describeFunctionsOf(pass)
    }

    // Inside a struct (its methods): the struct's own fields and methods need no qualifier.
    findEnclosingStruct(entry, line, column)?.let { struct -> names += describeFieldsOf(struct) + describeMethodsOf(struct) }

    for (using in entry.getObjectsOrEmpty("usingImports")) {
        val module = findModuleNamed(rawJson, using.getStringOrNull("name")) ?: continue
        names += describeFunctionsOf(module) + describeConstantsOf(module)
    }
    for (import in entry.getObjectsOrEmpty("imports")) {
        import.getStringOrNull("name")?.let { names += DeclaredName(it, DeclaredName.Kind.MODULE) }
    }
    return names.distinct()
}

/**
 * What `Qualifier::` can be followed by, from the cached AST: the functions, structs, enums and
 * constants of the module [qualifier] names (or the module an `import ... as` gave that alias,
 * from [aliases]), or the values of the enum it names. Empty when it names neither.
 */
fun collectMembersOf(
    root: AstRoot,
    rawJson: JsonObject,
    line: Int,
    column: Int,
    qualifier: String,
    aliases: Map<String, String> = emptyMap()
): List<DeclaredName> {
    findModuleNamed(rawJson, aliases[qualifier] ?: qualifier)?.let { module -> return describeMembersOfModule(module) }
    val enclosing = findEnclosingEntry(root, rawJson, line, column)
    val enums = listOfNotNull(enclosing).flatMap { it.getObjectsOrEmpty("enums") } +
        rawJson.getObjectsOrEmpty("modules").flatMap { it.getObjectsOrEmpty("enums") }
    val enum = enums.firstOrNull { it.getStringOrNull("name") == qualifier } ?: return emptyList()
    return enum.getObjectsOrEmpty("variants").mapNotNull { variant ->
        variant.getStringOrNull("name")?.let { DeclaredName(it, DeclaredName.Kind.ENUM_VALUE, type = qualifier) }
    }
}

/** The compiled file's own module or pipeline whose range holds the position. */
internal fun findEnclosingEntry(root: AstRoot, rawJson: JsonObject, line: Int, column: Int): JsonObject? {
    val own = root.roots.toSet()
    return listOf("modules", "pipelines").asSequence()
        .flatMap { rawJson.getObjectsOrEmpty(it).asSequence() }
        .firstOrNull { it.getStringOrNull("id") in own && it.doesRangeContain(line, column) }
}

/** The struct of [entry] whose range holds the position, which is where a method body is. */
internal fun findEnclosingStruct(entry: JsonObject, line: Int, column: Int): JsonObject? =
    entry.getObjectsOrEmpty("structs").firstOrNull { it.doesRangeContain(line, column) }

internal fun findModuleNamed(rawJson: JsonObject, name: String?): JsonObject? =
    if (name == null) null else rawJson.getObjectsOrEmpty("modules").firstOrNull { it.getStringOrNull("name") == name }

/** The functions, structs, enums and constants a module declares. */
internal fun describeMembersOfModule(module: JsonObject): List<DeclaredName> =
    describeFunctionsOf(module) + describeTypesOf(module) + describeConstantsOf(module)

/** A struct's fields, with their types as written (an array's elements' type, as `float[4]`). */
internal fun describeFieldsOf(struct: JsonObject): List<DeclaredName> =
    struct.getObjectsOrEmpty("fields").mapNotNull { field ->
        val name = field.getStringOrNull("name") ?: return@mapNotNull null
        val type = field.getStringOrNull("dataType")
        val arraySize = field.getIntOrNull("arraySize") ?: 0
        DeclaredName(name, DeclaredName.Kind.FIELD, if (type != null && arraySize > 0) "$type[$arraySize]" else type)
    }

/** A struct's methods, as functions. */
internal fun describeMethodsOf(struct: JsonObject): List<DeclaredName> = describeFunctionsIn(struct.getObjectsOrEmpty("methods"))

private fun describeFunctionsOf(container: JsonObject): List<DeclaredName> = describeFunctionsIn(container.getObjectsOrEmpty("functions"))

private fun describeFunctionsIn(functions: List<JsonObject>): List<DeclaredName> =
    functions.mapNotNull { function ->
        val name = function.getStringOrNull("name") ?: return@mapNotNull null
        val parameters = function.getObjectsOrEmpty("parameters").map { "${it.getStringOrNull("dataType").orEmpty()} ${it.getStringOrNull("name").orEmpty()}".trim() }
        DeclaredName(name, DeclaredName.Kind.FUNCTION, function.getStringOrNull("returnType"), parameters)
    }

private fun describeTypesOf(container: JsonObject): List<DeclaredName> =
    container.getObjectsOrEmpty("structs").mapNotNull { it.getStringOrNull("name")?.let { name -> DeclaredName(name, DeclaredName.Kind.STRUCT) } } +
        container.getObjectsOrEmpty("enums").mapNotNull { it.getStringOrNull("name")?.let { name -> DeclaredName(name, DeclaredName.Kind.ENUM) } }

private fun describeConstantsOf(container: JsonObject): List<DeclaredName> =
    container.getObjectsOrEmpty("consts").mapNotNull { const ->
        const.getStringOrNull("name")?.let { DeclaredName(it, DeclaredName.Kind.CONSTANT, const.getStringOrNull("declaredType")) }
    }
