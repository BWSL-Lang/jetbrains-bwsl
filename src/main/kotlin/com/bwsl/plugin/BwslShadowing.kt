package com.bwsl.plugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A parameter, local, constant or loop variable whose name is already taken by something visible
 * where it is declared. [line]/[column] are the 1-based start of its name in the compiled text.
 */
data class ShadowingDeclaration(
    val name: String,
    val line: Int,
    val column: Int,
    val kind: VisibleLocal.Kind,
    val shadowed: VisibleLocal.Kind
) {
    fun describe(): String =
        "${kind.label.replaceFirstChar { it.uppercase() }} '$name' shadows a ${shadowed.label} of the same name"
}

/**
 * Every declaration in the compiled file that shadows another. bwslc allows it and says nothing, so
 * it is found here: for each parameter, local, constant and loop variable the names in scope just
 * before it are asked of [collectVisibleLocalsAt], the same answer completion offers there, so the
 * two cannot disagree about what is in scope.
 *
 * Module-, pipeline- and pass-level consts are not checked: they are visible throughout their
 * container, so "just before" is not defined for them.
 */
fun collectShadowingDeclarations(root: AstRoot, rawJson: JsonObject): List<ShadowingDeclaration> {
    val own = root.roots.toSet()
    val declarations = ArrayList<DeclarationSite>()
    for (key in listOf("modules", "pipelines")) {
        val entries = rawJson.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
        for (entry in entries) {
            val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (obj.getStringOrNull("id") in own) DeclarationCollector(declarations).visit(obj, inBlock = false)
        }
    }
    // The AST can reach one declaration by more than one path (an `if`'s block is reachable twice).
    return declarations.distinctBy { Triple(it.name, it.nameLine, it.nameColumn) }.mapNotNull { declared ->
        val visible = collectVisibleLocalsAt(root, rawJson, declared.queryLine, declared.queryColumn)
            .firstOrNull { it.name == declared.name } ?: return@mapNotNull null
        ShadowingDeclaration(declared.name, declared.nameLine, declared.nameColumn, declared.kind, visible.kind)
    }
}

/** A declared name, and the position to ask what is in scope just before it. */
private class DeclarationSite(
    val name: String,
    val kind: VisibleLocal.Kind,
    val nameLine: Int,
    val nameColumn: Int,
    val queryLine: Int,
    val queryColumn: Int
)

private class DeclarationCollector(private val out: MutableList<DeclarationSite>) {

    fun visit(element: JsonElement, inBlock: Boolean) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { visit(it, inBlock) }
            element.isJsonObject -> visitObject(element.asJsonObject, inBlock)
        }
    }

    private fun visitObject(o: JsonObject, inBlock: Boolean) {
        when (o.getStringOrNull("type")) {
            "FUNCTION" -> {
                // Just before the function, so its own parameters are not in scope yet.
                val line = o.getIntOrNull("line")
                val column = o.getIntOrNull("column")
                if (line != null && column != null) {
                    o.get("parameters")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { p ->
                        val param = p.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        add(param, VisibleLocal.Kind.PARAMETER, line, column - 1)
                    }
                }
            }

            "VARIABLE_DECL" -> {
                if (inBlock) {
                    val isConst = o.get("isConst")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                    val kind = if (isConst) VisibleLocal.Kind.CONSTANT else VisibleLocal.Kind.VARIABLE
                    val line = o.getIntOrNull("line")
                    val column = o.getIntOrNull("column")
                    if (line != null && column != null) add(o, kind, line, column)
                }
                return
            }

            "FOR_RANGE", "FOR_COLLECTION", "FOR_CSTYLE" -> o.getObjectOrNull("iteratorVar")?.let { iterator ->
                // Just before the loop variable, so neither it nor the loop's other variables are in scope.
                val line = iterator.getIntOrNull("line")
                val column = iterator.getIntOrNull("column")
                if (line != null && column != null) add(iterator, VisibleLocal.Kind.LOOP_VARIABLE, line, column - 1)
            }
        }

        val childrenInBlock = inBlock || o.getStringOrNull("type") == "BLOCK"
        for ((_, value) in o.entrySet()) visit(value, childrenInBlock)
    }

    private fun add(node: JsonObject, kind: VisibleLocal.Kind, queryLine: Int, queryColumn: Int) {
        val name = node.getStringOrNull("name") ?: return
        val nameLine = node.getIntOrNull("nameLine") ?: return
        val nameColumn = node.getIntOrNull("nameColumn") ?: return
        out += DeclarationSite(name, kind, nameLine, nameColumn, queryLine, queryColumn)
    }
}
