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
    /** The node kind (`"FUNCTION_CALL"`, ...); empty for id-bearing objects without one (fields, parameters, ...). */
    val type: String,
    val line: Int,
    val column: Int,
    val name: String? = null,
    val member: String? = null,
    /** The name as written, when it differs from [name] (an aliased `using`). */
    val writtenName: String? = null,
    /** Where the node's name text starts - present on every named node. */
    val nameLine: Int? = null,
    val nameColumn: Int? = null,
    /** Present on VARIABLE_DECL only - the start of the declared-type text. */
    val typeLine: Int? = null,
    val typeColumn: Int? = null,
    /** Present on VARIABLE_DECL only - text length gives the declared-type span. */
    val declaredType: String? = null,
    /** Present on FUNCTION only - where the return type is written, and its text (qualified, as written). */
    val returnTypeLine: Int? = null,
    val returnTypeColumn: Int? = null,
    val returnType: String? = null,
    /** Present on parameters and struct fields - the declared type's text (qualified, as written). */
    val dataType: String? = null,
    /** Present on nodes with a body range (FUNCTION, STRUCT_DECL, MODULE, PIPELINE, PASS, ...). */
    val endLine: Int? = null,
    val endColumn: Int? = null,
    /** Present on ASSIGNMENT only: `DEFAULT`, `FLAT` or `NO_PERSPECTIVE`. */
    val interpolation: String? = null,
    /**
     * The file this node was written in: its own `sourceFile`, or the nearest enclosing one. Its
     * line/column are relative to that file. Null for nodes below the level that records one.
     */
    val sourceFile: String? = null
)

/**
 * Line/column -> offset math and the name/type range rules for ONE source text. A node's
 * line/column are relative to the file it was written in, so a node from another file must be
 * measured against *that* file's text, not the compiled file's - hence one of these per file
 * rather than baking the text into [BwslAstIndex].
 */
class SourcePositions(private val text: String) {
    private val lineStarts: IntArray = run {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
        starts.toIntArray()
    }

    /** Converts a 1-based (line, column) AST position to a 0-based character offset in [text]. */
    fun toOffset(line: Int, column: Int): Int? {
        if (line < 1 || line > lineStarts.size) return null
        return lineStarts[line - 1] + (column - 1)
    }

    /**
     * The character range (0-based, end-exclusive) of [node]'s *name* text, from its
     * `nameLine`/`nameColumn`. Node `line`/`column` is NOT the name (a VARIABLE_DECL points at its
     * type, a MODULE at its keyword, a MEMBER_ACCESS at the dot), so this fails closed - null -
     * when the node has no name position, rather than falling back to the wrong one.
     */
    fun findNameRangeOf(node: AstNodePos): IntRange? {
        val line = node.nameLine?.takeIf { it != 0 } ?: return null
        val column = node.nameColumn?.takeIf { it != 0 } ?: return null
        val name = (node.member ?: node.writtenName ?: node.name)?.takeIf { it.isNotEmpty() } ?: return null
        val start = toOffset(line, column) ?: return null
        return start until (start + name.length)
    }

    /**
     * The character range (0-based, end-exclusive) of the *type* text a declaration carries -
     * distinct from [findNameRangeOf], which returns its name: a VARIABLE_DECL's declared type, a
     * FUNCTION's return type, or a parameter's or struct field's type. Null for a node with no type
     * text, or missing its position.
     */
    fun findTypeRangeOf(node: AstNodePos): IntRange? {
        val (line, column, text) = when {
            node.type == "FUNCTION" -> Triple(node.returnTypeLine, node.returnTypeColumn, node.returnType)
            node.declaredType != null -> Triple(node.typeLine, node.typeColumn, node.declaredType)
            node.dataType != null -> Triple(node.typeLine, node.typeColumn, node.dataType)
            else -> return null
        }
        val tl = line?.takeIf { it != 0 } ?: return null
        val tc = column?.takeIf { it != 0 } ?: return null
        val typeText = text?.takeIf { it.isNotEmpty() } ?: return null
        val start = toOffset(tl, tc) ?: return null
        return start until (start + typeText.length)
    }

    /**
     * The character range (0-based, inclusive end) spanned by [node]'s full body, per its
     * line/column..endLine/endColumn.
     */
    fun findBodyRangeOf(node: AstNodePos): IntRange? {
        val start = toOffset(node.line, node.column) ?: return null
        val endLine = node.endLine?.takeIf { it != 0 } ?: return null
        val endColumn = node.endColumn?.takeIf { it != 0 } ?: return null
        val end = toOffset(endLine, endColumn) ?: return null
        return start..end
    }
}

/**
 * A per-file index over bwslc's raw -ast-json output: a generic id -> position map (built by
 * walking every JSON object that carries an "id" - the typed [AstRoot] model doesn't, and can't
 * practically, mirror every node type), plus lookups over the compiler's own reference index
 * (symbols/references, from [AstRoot.referenceIndex]).
 *
 * The payload also contains every imported module (bwslc is given `-modules` paths), and members a
 * `submodule` merged into its parent from other files. A node's line/column are relative to the
 * file it was written in, which `sourceFile` names on every top-level entry and member. So the node
 * map is split by file: [nodesById] holds only what the compiled file itself wrote (safe to measure
 * against [sourceText]); everything else lands in [externalNodesById], each carrying its own
 * [AstNodePos.sourceFile] to measure against.
 *
 * Which entries are the compiled file's own comes from [AstRoot.roots], not from comparing paths:
 * the compiled file's `sourceFile` is as it was passed to bwslc, an imported one is as bwslc found
 * it.
 *
 * That this index's positions are right is verified by BwslAstIndexTest against real bwslc output;
 * treat that test as the source of truth over this file's doc comments if they ever disagree.
 */
class BwslAstIndex(root: AstRoot, rawJson: JsonObject, sourceText: String) {
    /** Nodes written by the compiled file itself. */
    val nodesById: Map<String, AstNodePos>

    /** Nodes written in another file - positions are relative to their [AstNodePos.sourceFile]. */
    val externalNodesById: Map<String, AstNodePos>

    val symbolsById: Map<String, AstSymbol>
    val refsByFrom: Map<String, List<AstReference>>
    val refsByTo: Map<String, List<AstReference>>

    /** ASSIGNMENT id -> the id of its target expression (a MEMBER_ACCESS for `output.x = ...`). */
    private val assignmentTargets: Map<String, String>
    private val own = SourcePositions(sourceText)

    init {
        val rootIds = root.roots.toSet()
        val nodes = LinkedHashMap<String, AstNodePos>()
        val external = LinkedHashMap<String, AstNodePos>()
        val targets = HashMap<String, String>()

        for ((key, value) in rawJson.entrySet()) {
            when (key) {
                // referenceIndex.symbols reuses the same id namespace as AST nodes (e.g. a module's
                // symbol entry is also "id": "MODULE:0") but carries no line/column - walking into
                // it would silently overwrite the real AST node's position with that bare stub.
                // "root" repeats one of the entries below (the last top-level pipeline), so walking it
                // would index that pipeline's nodes twice; "roots" is the full list.
                "referenceIndex", "root" -> {}
                "modules", "pipelines" -> {
                    if (!value.isJsonArray) continue
                    for (entry in value.asJsonArray) {
                        val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val bucket = LinkedHashMap<String, AstNodePos>()
                        collectNodes(entry, bucket, targets, file = null)
                        if (obj.get("id")?.asStringOrNull() in rootIds) {
                            val entryFile = obj.get("sourceFile")?.asStringOrNull()
                            for ((id, node) in bucket) {
                                if (node.sourceFile == null || node.sourceFile == entryFile) nodes[id] = node
                                else external[id] = node
                            }
                        } else {
                            external.putAll(bucket)
                        }
                    }
                }
                else -> collectNodes(value, nodes, targets, file = null)
            }
        }
        nodesById = nodes
        externalNodesById = external
        assignmentTargets = targets

        val refIndex = root.referenceIndex
        symbolsById = refIndex?.symbols?.associateBy { it.id } ?: emptyMap()
        refsByFrom = refIndex?.references?.groupBy { it.from } ?: emptyMap()
        refsByTo = refIndex?.references?.groupBy { it.to } ?: emptyMap()
    }

    /** The node id of the target expression of [assignmentId] (e.g. the `output.x` of `output.x = ...`). */
    fun findAssignmentTargetOf(assignmentId: String): String? = assignmentTargets[assignmentId]

    /** Measures positions against [text] - for a node from a file other than the compiled one. */
    fun createPositionsFor(text: String): SourcePositions = SourcePositions(text)

    fun findNameRangeOf(node: AstNodePos): IntRange? = own.findNameRangeOf(node)
    fun findTypeRangeOf(node: AstNodePos): IntRange? = own.findTypeRangeOf(node)
    fun findBodyRangeOf(node: AstNodePos): IntRange? = own.findBodyRangeOf(node)

    /** The innermost indexed node (by smallest name-range span) whose name range contains [offset]. */
    fun findNodeAtOffset(offset: Int): AstNodePos? =
        nodesById.values
            .mapNotNull { node -> findNameRangeOf(node)?.let { it to node } }
            .filter { (range, _) -> offset in range }
            .minByOrNull { (range, _) -> range.last - range.first }
            ?.second

    /** The innermost node of kind [type] (`PASS`, `FRAGMENT_STAGE`, ...) whose body range contains [offset]. */
    fun findEnclosingNodeOfType(type: String, offset: Int): AstNodePos? =
        nodesById.values
            .filter { it.type == type }
            .mapNotNull { node -> findBodyRangeOf(node)?.let { it to node } }
            .filter { (range, _) -> offset in range }
            .minByOrNull { (range, _) -> range.last - range.first }
            ?.second

    /** The declaration (variable, function, parameter or field) whose type text ([findTypeRangeOf]) contains [offset]. */
    fun findTypedDeclarationAtOffset(offset: Int): AstNodePos? =
        nodesById.values
            .firstOrNull { node -> findTypeRangeOf(node)?.let { offset in it } == true }

    private fun collectNodes(
        element: JsonElement,
        into: MutableMap<String, AstNodePos>,
        assignmentTargets: MutableMap<String, String>,
        file: String?
    ) {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                val objFile = obj.get("sourceFile")?.asStringOrNull() ?: file
                val id = obj.get("id")?.asStringOrNull()
                if (!id.isNullOrEmpty()) {
                    val kind = obj.get("type")?.asStringOrNull() ?: ""
                    into[id] = AstNodePos(
                        id = id,
                        type = kind,
                        line = obj.get("line")?.asIntOrNull() ?: 0,
                        column = obj.get("column")?.asIntOrNull() ?: 0,
                        name = obj.get("name")?.asStringOrNull(),
                        member = obj.get("member")?.asStringOrNull(),
                        writtenName = obj.get("writtenName")?.asStringOrNull(),
                        nameLine = obj.get("nameLine")?.asIntOrNull(),
                        nameColumn = obj.get("nameColumn")?.asIntOrNull(),
                        typeLine = obj.get("typeLine")?.asIntOrNull(),
                        typeColumn = obj.get("typeColumn")?.asIntOrNull(),
                        declaredType = obj.get("declaredType")?.asStringOrNull(),
                        returnTypeLine = obj.get("returnTypeLine")?.asIntOrNull(),
                        returnTypeColumn = obj.get("returnTypeColumn")?.asIntOrNull(),
                        returnType = obj.get("returnType")?.asStringOrNull(),
                        dataType = obj.get("dataType")?.asStringOrNull(),
                        endLine = obj.get("endLine")?.asIntOrNull(),
                        endColumn = obj.get("endColumn")?.asIntOrNull(),
                        interpolation = obj.get("interpolation")?.asStringOrNull(),
                        sourceFile = objFile
                    )
                    if (kind == "ASSIGNMENT") {
                        obj.get("target")?.takeIf { it.isJsonObject }?.asJsonObject
                            ?.get("id")?.asStringOrNull()?.let { assignmentTargets[id] = it }
                    }
                }
                for ((_, value) in obj.entrySet()) collectNodes(value, into, assignmentTargets, objFile)
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) collectNodes(item, into, assignmentTargets, file)
            }
            else -> {}
        }
    }
}
