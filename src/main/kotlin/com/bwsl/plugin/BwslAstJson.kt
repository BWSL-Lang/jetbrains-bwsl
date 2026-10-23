package com.bwsl.plugin

import com.google.common.collect.Interners
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import com.google.gson.Strictness
import com.google.gson.internal.LazilyParsedNumber
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader

/**
 * Reads the payload of `bwslc -ast-json` once into a [JsonObject] whose strings are shared and whose small
 * integers are plain numbers, and builds the typed [AstRoot] from that tree.
 *
 * A payload is mostly the same few strings over and over (`line`, `column`, `type`, the names of types, a
 * node id that is written in a dozen places) and mostly small numbers. Gson's own tree gives each its own
 * `String`, and each number a `LazilyParsedNumber` holding another one, which made the cached trees of a
 * project take about ten times the size of the JSON.
 */
internal fun parseAstJson(json: String): CompiledAst? {
    val raw = readSharedJson(json) as? JsonObject ?: return null
    // "root" repeats the last top-level pipeline, which "pipelines" has already (the typed model has no field for it,
    // and what walks the tree skips it); "roots" lists the entries that are the compiled file's own.
    raw.remove("root")
    val root = Gson().fromJson(raw, AstRoot::class.java) ?: return null
    return CompiledAst(root, raw)
}

/** One copy of each string the payloads have, for as long as something holds it. */
private val sharedStrings = Interners.newWeakInterner<String>()

/** Strings longer than this are not shared: they are not repeated, so only the lookup would cost. */
private const val MAX_SHARED_LENGTH = 64

/**
 * [json] as a [JsonElement]; null for an empty text. Throws [JsonSyntaxException] when it is not JSON. A string
 * of up to [MAX_SHARED_LENGTH] characters (a name included) is the same `String` instance in every tree, and a
 * number that is an `Int` written the usual way is an `Int`.
 */
internal fun readSharedJson(json: String): JsonElement? {
    if (json.isBlank()) return null
    val reader = JsonReader(StringReader(json))
    reader.strictness = Strictness.LENIENT
    try {
        return readElement(reader)
    } catch (e: IOException) {
        throw JsonSyntaxException(e)
    } catch (e: IllegalStateException) {
        throw JsonSyntaxException(e)
    } catch (e: NumberFormatException) {
        throw JsonSyntaxException(e)
    }
}

private fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        val result = JsonObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = share(reader.nextName())
            result.add(name, readElement(reader))
        }
        reader.endObject()
        result
    }
    JsonToken.BEGIN_ARRAY -> {
        val result = JsonArray()
        reader.beginArray()
        while (reader.hasNext()) result.add(readElement(reader))
        reader.endArray()
        result
    }
    JsonToken.STRING -> JsonPrimitive(share(reader.nextString()))
    JsonToken.NUMBER -> JsonPrimitive(readNumber(reader.nextString()))
    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
    JsonToken.NULL -> {
        reader.nextNull()
        JsonNull.INSTANCE
    }
    else -> throw IllegalStateException("unexpected ${reader.peek()} at ${reader.path}")
}

private fun share(text: String): String = if (text.length <= MAX_SHARED_LENGTH) sharedStrings.intern(text) else text

/** The number written as [text]: an `Int` when that prints back as the same text, else what Gson itself makes of it. */
private fun readNumber(text: String): Number {
    val asInt = text.toIntOrNull()
    return if (asInt != null && asInt.toString() == text) asInt else LazilyParsedNumber(text)
}
