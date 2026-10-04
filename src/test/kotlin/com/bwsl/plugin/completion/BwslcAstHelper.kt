package com.bwsl.plugin.completion

import com.bwsl.plugin.*

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Shells out to the real bwslc compiler to produce an [AstRoot] for a snippet of BWSL source.
 *
 * [modules] maps a module name to its source; each is written to `<name>.bwsl` in a directory
 * handed to bwslc as a `-modules` path (bwslc was observed to find a module only in a file named
 * after it). The AST names the file each imported declaration came from (`sourceFile`, an absolute
 * path), so those files are kept on disk until the JVM exits - navigating into an imported module
 * opens that path. The compiled file itself is deleted once bwslc has run.
 */
object BwslcAstHelper {
    private val COMPILER_PATH = System.getProperty("bwslc.path")
        ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")

    fun parse(source: String, modules: Map<String, String> = emptyMap()): AstRoot {
        val json = runBwslc(source, modules)
        return Gson().fromJson(json, AstRoot::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
    }

    /** Builds the [BwslAstIndex] for [source] from real bwslc output; positions are measured against [source]. */
    fun buildIndex(source: String, modules: Map<String, String> = emptyMap()): BwslAstIndex =
        buildIndexAndRoot(source, modules).first

    /** Like [buildIndex], also returning the typed [AstRoot] the index was built from. */
    fun buildIndexAndRoot(source: String, modules: Map<String, String> = emptyMap()): Pair<BwslAstIndex, AstRoot> {
        val root = parse(source, modules)
        val raw = parseRaw(source, modules)
        return BwslAstIndex(root, raw, source) to root
    }

    /** The same bwslc -ast-json output as [parse], parsed generically instead of into the typed model. */
    fun parseRaw(source: String, modules: Map<String, String> = emptyMap()): JsonObject {
        val json = runBwslc(source, modules)
        return Gson().fromJson(json, JsonObject::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
    }

    /**
     * Parses [source] with real bwslc and populates [BwslAstCache] for [filePath] with both the
     * typed root and the raw JSON tree - mirrors what [com.bwsl.plugin.BwslAstAnnotator] does for
     * a real file, so tests exercise the exact same cache state a real edit would produce
     * (including [com.bwsl.plugin.references.BwslAstReference], which needs both).
     */
    fun parseAndCache(source: String, filePath: String, modules: Map<String, String> = emptyMap()) {
        val json = runBwslc(source, modules)
        val root = Gson().fromJson(json, AstRoot::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
        val raw = Gson().fromJson(json, JsonObject::class.java)
            ?: error("bwslc -ast-json returned invalid JSON: ${json.take(200)}")
        BwslAstCache.update(filePath, root, raw)
    }

    private val modulesRoot: File by lazy {
        Files.createTempDirectory("bwsl_ast_test_modules_").toFile().also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
        }
    }

    private fun runBwslc(source: String, modules: Map<String, String>): String {
        val tempDir = Files.createTempDirectory("bwsl_ast_test_").toFile()
        val tempFile = tempDir.resolve("test.bwsl")
        try {
            tempFile.writeText(source)
            val command = mutableListOf(COMPILER_PATH, tempFile.absolutePath, "-ast-json")
            if (modules.isNotEmpty()) {
                val modulesDir = Files.createTempDirectory(modulesRoot.toPath(), "m").toFile()
                modules.forEach { (name, text) -> modulesDir.resolve("$name.bwsl").writeText(text) }
                command += listOf("-modules", modulesDir.absolutePath)
            }
            val process = ProcessBuilder(command).start()
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
