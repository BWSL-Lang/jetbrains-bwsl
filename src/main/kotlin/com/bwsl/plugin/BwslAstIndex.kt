package com.bwsl.plugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A generic node position, extracted from a single raw JSON object in bwslc's -ast-json output
 * (any object carrying an "id"). Deliberately loose/untyped compared to the AstXxx data classes -
 * see [BwslAstIndex] for why a generic walk of the raw tree is needed at all.
 */
data class AstNodePos(
    val id: String,
    val type: String,
    val line: Int,
    val column: Int,
    val name: String? = null,
    val member: String? = null,
    /** Present on VARIABLE_DECL only - see FUCK_THE_LEXER.md §3. */
    val nameLine: Int? = null,
    val nameColumn: Int? = null,
    /** Present on VARIABLE_DECL only - the start of the declared-type text (node.line/column). */
    val typeLine: Int? = null,
    val typeColumn: Int? = null,
    /** Present on VARIABLE_DECL only - text length gives the declared-type span. */
    val declaredType: String? = null,
    /** Present on nodes with a body range (FUNCTION, STRUCT_DECL, MODULE, PIPELINE, PASS, ...). */
    val endLine: Int? = null,
    val endColumn: Int? = null,
    /** true for `recv.f()`-style FUNCTION_CALL nodes. */
    val hasReceiver: Boolean = false,
    /** true for `Mod::f()`-style FUNCTION_CALL nodes. */
    val hasModuleQualifier: Boolean = false
)

/**
 * A per-file index over bwslc's raw -ast-json output: a generic id -> position map (built by
 * walking every JSON object that carries an "id" - the typed [AstRoot] model doesn't, and can't
 * practically, mirror every node type), plus lookups over the compiler's own reference index
 * (symbols/references, from [AstRoot.referenceIndex]).
 *
 * See FUCK_THE_LEXER.md for the design rationale and the position-semantics table (§3) that
 * [nameRangeOf] implements. That table - and this index's correctness - is verified by
 * BwslAstIndexTest against real bwslc output; treat that test as the source of truth over this
 * file's doc comments if they ever disagree.
 */
class BwslAstIndex(root: AstRoot, rawJson: JsonObject, private val sourceText: String) {
    val nodesById: Map<String, AstNodePos>
    val symbolsById: Map<String, AstSymbol>
    val refsByFrom: Map<String, List<AstReference>>
    val refsByTo: Map<String, List<AstReference>>

    private val lineStarts: IntArray = run {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in sourceText.indices) if (sourceText[i] == '\n') starts.add(i + 1)
        starts.toIntArray()
    }

    init {
        val nodes = LinkedHashMap<String, AstNodePos>()
        // referenceIndex.symbols reuses the same id namespace as AST nodes (e.g. a module's
        // symbol entry is also "id": "MODULE:0") but carries no line/column - walking into it
        // would silently overwrite the real AST node's position with that bare stub.
        for ((key, value) in rawJson.entrySet()) {
            if (key == "referenceIndex") continue
            collectNodes(value, nodes)
        }
        nodesById = nodes

        val refIndex = root.referenceIndex
        symbolsById = refIndex?.symbols?.associateBy { it.id } ?: emptyMap()
        refsByFrom = refIndex?.references?.groupBy { it.from } ?: emptyMap()
        refsByTo = refIndex?.references?.groupBy { it.to } ?: emptyMap()
    }

    /** Converts a 1-based (line, column) AST position to a 0-based character offset in the source. */
    fun offsetOf(line: Int, column: Int): Int? {
        if (line < 1 || line > lineStarts.size) return null
        return lineStarts[line - 1] + (column - 1)
    }

    /**
     * The character range (0-based, end-exclusive) of [node]'s *name* text - node.line/column does
     * NOT uniformly point at the name (e.g. VARIABLE_DECL points at the declared type, MODULE at
     * the keyword, MEMBER_ACCESS at the dot). Returns null when the name can't be confidently
     * located rather than guessing (see the VARIABLE_DECL branch).
     */
    fun nameRangeOf(node: AstNodePos): IntRange? = when (node.type) {
        "IDENTIFIER", "FUNCTION" -> exactNameRange(node.line, node.column, node.name)
        "VARIABLE_DECL" -> {
            // Some VARIABLE_DECLs (e.g. a for-loop's `int i = 0` init clause) carry no
            // nameLine/nameColumn at all - line/column there points at the *type*, so falling
            // back to it would silently return a wrong-but-plausible range. Fail closed instead.
            val nl = node.nameLine?.takeIf { it != 0 }
            val nc = node.nameColumn?.takeIf { it != 0 }
            if (nl != null && nc != null) exactNameRange(nl, nc, node.name) else null
        }
        "MODULE", "STRUCT_DECL", "PIPELINE" -> keywordSkipNameRange(node)
        "ATTRIBUTE_DECL" -> searchLineForName(node.line, node.name)
        "MEMBER_ACCESS" -> offsetNameRange(node.line, node.column, offset = 1, node.member)
        "FUNCTION_CALL" -> functionCallNameRange(node)
        else -> null
    }

    /** The innermost indexed node (by smallest name-range span) whose name range contains [offset]. */
    fun nodeAtOffset(offset: Int): AstNodePos? =
        nodesById.values
            .mapNotNull { node -> nameRangeOf(node)?.let { it to node } }
            .filter { (range, _) -> offset in range }
            .minByOrNull { (range, _) -> range.last - range.first }
            ?.second

    /**
     * The character range (0-based, end-exclusive) of a VARIABLE_DECL's *declared-type* text -
     * distinct from [nameRangeOf], which returns its name. Null for every other node type, and
     * for VARIABLE_DECLs missing typeLine/typeColumn/declaredType (same fail-closed reasoning as
     * the for-loop-init case in [nameRangeOf]).
     */
    fun typeRangeOf(node: AstNodePos): IntRange? {
        if (node.type != "VARIABLE_DECL") return null
        val tl = node.typeLine?.takeIf { it != 0 } ?: return null
        val tc = node.typeColumn?.takeIf { it != 0 } ?: return null
        val declaredType = node.declaredType?.takeIf { it.isNotEmpty() } ?: return null
        val start = offsetOf(tl, tc) ?: return null
        return start until (start + declaredType.length)
    }

    /** The VARIABLE_DECL (if any) whose declared-type range ([typeRangeOf]) contains [offset]. */
    fun variableDeclTypeAtOffset(offset: Int): AstNodePos? =
        nodesById.values
            .filter { it.type == "VARIABLE_DECL" }
            .firstOrNull { node -> typeRangeOf(node)?.let { offset in it } == true }

    /**
     * The character range (0-based, inclusive end) spanned by [node]'s full body, per its
     * line/column..endLine/endColumn - used to bound a name search inside a declaration that has
     * no position of its own (e.g. a function parameter), not to locate [node]'s own name.
     */
    fun ownerRangeOf(node: AstNodePos): IntRange? {
        val start = offsetOf(node.line, node.column) ?: return null
        val endLine = node.endLine?.takeIf { it != 0 } ?: return null
        val endColumn = node.endColumn?.takeIf { it != 0 } ?: return null
        val end = offsetOf(endLine, endColumn) ?: return null
        return start..end
    }

    private fun exactNameRange(line: Int, column: Int, name: String?): IntRange? {
        if (name.isNullOrEmpty()) return null
        val start = offsetOf(line, column) ?: return null
        return start until (start + name.length)
    }

    private fun offsetNameRange(line: Int, column: Int, offset: Int, name: String?): IntRange? {
        if (name.isNullOrEmpty()) return null
        val base = offsetOf(line, column) ?: return null
        val start = base + offset
        return start until (start + name.length)
    }

    /** Skips the declaration keyword (`module`/`struct`/`pipeline`) and following whitespace. */
    private fun keywordSkipNameRange(node: AstNodePos): IntRange? {
        val name = node.name
        if (name.isNullOrEmpty()) return null
        var i = offsetOf(node.line, node.column) ?: return null
        while (i < sourceText.length && sourceText[i].isLetter()) i++
        while (i < sourceText.length && sourceText[i].isWhitespace()) i++
        if (!sourceText.regionMatches(i, name, 0, name.length)) return null
        return i until (i + name.length)
    }

    /** ATTRIBUTE_DECL's line/column points at the *type*, not the name - search the line instead. */
    private fun searchLineForName(line: Int, name: String?): IntRange? {
        if (name.isNullOrEmpty()) return null
        val lineStart = offsetOf(line, 1) ?: return null
        val lineEnd = if (line < lineStarts.size) lineStarts[line] else sourceText.length
        val idx = sourceText.indexOf(name, lineStart)
        if (idx < 0 || idx >= lineEnd) return null
        return idx until (idx + name.length)
    }

    /** FUNCTION_CALL's line/column is the call-site marker: the name itself, a '.', or a '::'. */
    private fun functionCallNameRange(node: AstNodePos): IntRange? = when {
        node.hasReceiver -> offsetNameRange(node.line, node.column, offset = 1, node.name)
        node.hasModuleQualifier -> offsetNameRange(node.line, node.column, offset = 2, node.name)
        else -> exactNameRange(node.line, node.column, node.name)
    }

    private fun collectNodes(element: JsonElement, into: MutableMap<String, AstNodePos>) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val id = obj.get("id")?.asStringOrNull()
                if (!id.isNullOrEmpty()) {
                    into[id] = AstNodePos(
                        id = id,
                        type = obj.get("type")?.asStringOrNull() ?: "",
                        line = obj.get("line")?.asIntOrNull() ?: 0,
                        column = obj.get("column")?.asIntOrNull() ?: 0,
                        name = obj.get("name")?.asStringOrNull(),
                        member = obj.get("member")?.asStringOrNull(),
                        nameLine = obj.get("nameLine")?.asIntOrNull(),
                        nameColumn = obj.get("nameColumn")?.asIntOrNull(),
                        typeLine = obj.get("typeLine")?.asIntOrNull(),
                        typeColumn = obj.get("typeColumn")?.asIntOrNull(),
                        declaredType = obj.get("declaredType")?.asStringOrNull(),
                        endLine = obj.get("endLine")?.asIntOrNull(),
                        endColumn = obj.get("endColumn")?.asIntOrNull(),
                        hasReceiver = obj.has("receiver"),
                        hasModuleQualifier = !obj.get("moduleName")?.asStringOrNull().isNullOrEmpty()
                    )
                }
                for ((_, value) in obj.entrySet()) collectNodes(value, into)
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) collectNodes(item, into)
            }
            else -> {}
        }
    }
}

private fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

private fun JsonElement.asIntOrNull(): Int? =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asInt else null
