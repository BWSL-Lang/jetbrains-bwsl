package com.bwsl.plugin

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** The element as a string, or null if it is not a JSON string. */
internal fun JsonElement.asStringOrNull(): String? =
    if (isJsonPrimitive && asJsonPrimitive.isString) asString else null

/** The element as an int, or null if it is not a JSON number. */
internal fun JsonElement.asIntOrNull(): Int? =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asInt else null

/** The string stored under [key], or null if it is missing or not a string. */
internal fun JsonObject.getStringOrNull(key: String): String? = get(key)?.asStringOrNull()

/** The int stored under [key], or null if it is missing or not a number. */
internal fun JsonObject.getIntOrNull(key: String): Int? = get(key)?.asIntOrNull()

/** The object stored under [key], or null if it is missing or not an object. */
internal fun JsonObject.getObjectOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

/** The objects in the array stored under [key]; empty if it is missing or not an array. */
internal fun JsonObject.getObjectsOrEmpty(key: String): List<JsonObject> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()

/** Whether this node records the start and the end of its source range. */
internal fun JsonObject.hasRange(): Boolean = has("endLine") && has("endColumn") && has("line") && has("column")

/**
 * Whether the 1-based ([line], [column]) is inside this node's source range, ends included. A node
 * with no range is taken to contain everything.
 */
internal fun JsonObject.doesRangeContain(line: Int, column: Int): Boolean {
    val startLine = getIntOrNull("line") ?: return true
    val startColumn = getIntOrNull("column") ?: return true
    val endLine = getIntOrNull("endLine") ?: return true
    val endColumn = getIntOrNull("endColumn") ?: return true
    if (line < startLine || (line == startLine && column < startColumn)) return false
    return line < endLine || (line == endLine && column <= endColumn)
}
