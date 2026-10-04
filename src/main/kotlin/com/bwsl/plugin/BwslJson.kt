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
