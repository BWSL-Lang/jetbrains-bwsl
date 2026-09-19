package com.bwsl.plugin.completion

import com.bwsl.plugin.*

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Shells out to the real bwslc compiler to produce an [AstRoot] for a snippet of BWSL source. */
object BwslcAstHelper {
    private val COMPILER_PATH = System.getProperty("bwslc.path")
        ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")

    fun parse(source: String): AstRoot {
        val json = runBwslc(source)
        return Gson().fromJson(json, AstRoot::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
    }

    /** The same bwslc -ast-json output as [parse], parsed generically instead of into the typed model. */
    fun parseRaw(source: String): JsonObject {
        val json = runBwslc(source)
        return Gson().fromJson(json, JsonObject::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
    }

    private fun runBwslc(source: String): String {
        val tempDir = Files.createTempDirectory("bwsl_ast_test_").toFile()
        val tempFile = tempDir.resolve("test.bwsl")
        try {
            tempFile.writeText(source)
            val process = ProcessBuilder(COMPILER_PATH, tempFile.absolutePath, "-ast-json").start()
            val rawBytes = process.inputStream.readBytes()
            val stderrText = process.errorStream.bufferedReader().readText()
            check(process.waitFor(15, TimeUnit.SECONDS)) { "bwslc -ast-json timed out" }
            check(rawBytes.isNotEmpty()) { "bwslc -ast-json produced no output (exit ${process.exitValue()}): $stderrText" }
            val hasUtf16Bom = rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte()
            return if (hasUtf16Bom) String(rawBytes, Charsets.UTF_16) else String(rawBytes, Charsets.UTF_8)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
