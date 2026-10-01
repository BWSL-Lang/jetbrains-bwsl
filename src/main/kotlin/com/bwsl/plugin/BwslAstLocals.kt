package com.bwsl.plugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** A parameter, local variable, constant or loop variable in scope at some position. */
data class VisibleLocal(val name: String, val type: String?, val kind: Kind) {
    enum class Kind(val label: String) {
        PARAMETER("parameter"),
        VARIABLE("variable"),
        CONSTANT("constant"),
        LOOP_VARIABLE("loop variable")
    }
}

/**
 * The parameters, locals and constants in scope at the 1-based ([line], [column]) of the compiled
 * file, read from the cached AST ([rawJson], with [root] saying which top-level declarations are
 * the compiled file's own).
 *
 * This is for completion, which runs on half-typed code and so cannot ask the reference index what
 * a name refers to: it needs "what could the user type here". The walk only follows what holds the
 * position (a module, function, stage or block with a range around it); within a block a variable
 * counts once it has been declared, and a variable declared in a block that has closed does not.
 * Module-, pipeline- and pass-level consts are visible regardless of where they are declared.
 *
 * Positions come from the last successfully compiled text, so they can drift from the editor while
 * the file has unsaved edits.
 */
fun visibleLocalsAt(root: AstRoot, rawJson: JsonObject, line: Int, column: Int): List<VisibleLocal> {
    val own = root.roots.toSet()
    val found = LinkedHashMap<String, VisibleLocal>()
    for (key in listOf("modules", "pipelines")) {
        val entries = rawJson.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        for (entry in entries) {
            val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (obj.str("id") in own) LocalsWalker(line, column, found).visit(obj, inBlock = false)
        }
    }
    return found.values.toList()
}

private class LocalsWalker(
    private val line: Int,
    private val column: Int,
    private val out: MutableMap<String, VisibleLocal>
) {
    fun visit(element: JsonElement, inBlock: Boolean) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { visit(it, inBlock) }
            element.isJsonObject -> visitObject(element.asJsonObject, inBlock)
        }
    }

    private fun visitObject(o: JsonObject, inBlock: Boolean) {
        val type = o.str("type")

        // A node with a source range only matters if the position is inside it: a block that has
        // closed, or one that starts later, has nothing in scope here.
        if (o.hasRange() && !o.rangeContainsCaret()) return

        when (type) {
            "FUNCTION" -> o.get("parameters")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { p ->
                val param = p.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                param.str("name")?.let { add(it, param.str("dataType"), VisibleLocal.Kind.PARAMETER) }
            }

            "VARIABLE_DECL" -> {
                // Inside a block a variable is visible only once it has been declared; consts in a
                // module/pipeline/pass `consts` array are visible throughout it.
                if (!inBlock || startsBeforeCaret(o)) {
                    val kind = if (o.get("isConst")?.takeIf { it.isJsonPrimitive }?.asBoolean == true)
                        VisibleLocal.Kind.CONSTANT else VisibleLocal.Kind.VARIABLE
                    o.str("name")?.let { add(it, o.str("declaredType"), kind) }
                }
                return
            }

            "FOR_RANGE", "FOR_COLLECTION", "FOR_CSTYLE" -> {
                // A loop's variables are in scope from the loop's header to the end of its body.
                if (!loopContainsCaret(o)) return
                o.obj("iteratorVar")?.str("name")?.let { add(it, null, VisibleLocal.Kind.LOOP_VARIABLE) }
            }
        }

        val childrenInBlock = inBlock || type == "BLOCK"
        for ((_, value) in o.entrySet()) visit(value, childrenInBlock)
    }

    private fun add(name: String, type: String?, kind: VisibleLocal.Kind) {
        out[name] = VisibleLocal(name, type?.takeIf { it.isNotBlank() }, kind)
    }

    private fun startsBeforeCaret(o: JsonObject): Boolean {
        val l = o.int("line") ?: return false
        val c = o.int("column") ?: return false
        return l < line || (l == line && c < column)
    }

    private fun loopContainsCaret(loop: JsonObject): Boolean {
        val headerStarts = listOf(loop, loop.obj("iteratorVar"), loop.obj("init")).mapNotNull { startOf(it) }
        val (startLine, startColumn) = headerStarts.minWithOrNull(compareBy({ it.first }, { it.second })) ?: return false
        if (line < startLine || (line == startLine && column < startColumn)) return false

        // The end is the end of the body block; a multi-range `foreach` nests one loop in another's body.
        var body = loop.obj("body")
        while (body != null && !body.hasRange()) body = body.obj("body")
        val endLine = body?.int("endLine") ?: return true
        val endColumn = body.int("endColumn") ?: return true
        return line < endLine || (line == endLine && column <= endColumn)
    }

    private fun startOf(node: JsonObject?): Pair<Int, Int>? {
        val l = node?.int("line") ?: return null
        val c = node.int("column") ?: return null
        return l to c
    }

    private fun JsonObject.rangeContainsCaret(): Boolean {
        val startLine = int("line") ?: return true
        val startColumn = int("column") ?: return true
        val endLine = int("endLine") ?: return true
        val endColumn = int("endColumn") ?: return true
        if (line < startLine || (line == startLine && column < startColumn)) return false
        return line < endLine || (line == endLine && column <= endColumn)
    }

    private fun JsonObject.hasRange(): Boolean = has("endLine") && has("endColumn") && has("line") && has("column")
}

private fun JsonObject.str(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.int(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject
