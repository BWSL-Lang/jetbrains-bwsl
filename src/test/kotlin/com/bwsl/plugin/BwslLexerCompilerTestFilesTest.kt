package com.bwsl.plugin

import com.intellij.psi.TokenType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class BwslLexerCompilerTestFilesTest {

    @TestFactory
    fun noBADCharacterTokens(): List<DynamicTest> {
        val testsRoot = File(javaClass.classLoader.getResource("bwsl_test_files")!!.toURI())
        val subdirs = System.getProperty("bwsl.test.subdirs").split(",")

        return subdirs.map { subdir ->
            DynamicTest.dynamicTest(subdir) {
                val dir = File(testsRoot, subdir)
                assertTrue(dir.exists()) { "Expected test directory ${dir.absolutePath} does not exist" }
                assertTrue(dir.isDirectory()) { "Expected test directory ${dir.absolutePath} is not a directory" }

                val failures = mutableListOf<String>()
                var fileCount = 0

                for (file in dir.listFiles { f -> f.extension == "bwsl" } ?: emptyArray()) {
                    fileCount++
                    val badTokens = lexForBadCharacters(file.readText())
                    if (badTokens.isNotEmpty()) {
                        val details = badTokens.joinToString(", ") { (offset, ch) ->
                            "'$ch' (U+${ch.code.toString(16).uppercase().padStart(4, '0')}) at offset $offset"
                        }
                        failures += "${file.name}: $details"
                    }
                }

                println("$subdir: $fileCount file(s)")
                assertTrue(failures.isEmpty()) {
                    "Lexer produced BAD_CHARACTER tokens in $fileCount file(s) in $subdir.\n" +
                        failures.joinToString("\n")
                }
            }
        }
    }

    private fun lexForBadCharacters(content: String): List<Pair<Int, Char>> {
        val lexer = BwslLexerAdapter()
        lexer.start(content, 0, content.length, 0)
        val result = mutableListOf<Pair<Int, Char>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType == TokenType.BAD_CHARACTER) {
                val offset = lexer.tokenStart
                result += Pair(offset, content[offset])
            }
            lexer.advance()
        }
        return result
    }
}
