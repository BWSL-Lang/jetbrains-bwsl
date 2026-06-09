package com.bwsl.plugin

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File

class BwslLexerFunctionCallTest {

    @Test
    fun rotateOnLine15IsAFunctionCall() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val token = tokens.firstOrNull { it.line == 15 && it.text == "rotate" }
        assertNotNull(token) { "Expected 'rotate' token on line 15" }
        assertEquals(BwslTokenTypes.FUNCTION_CALL, token!!.type) { "'rotate' on line 15 should be FUNCTION_CALL" }
    }

    @Test
    fun ifIsNotAFunctionCall() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val token = tokens.firstOrNull { it.line == 12 && it.text == "if" }
        assertNotNull(token) { "Expected 'if' token on line 12" }
        assertEquals(BwslTokenTypes.KW_IF, token!!.type) { "'if' on line 12 should be KW_IF, not FUNCTION_CALL" }
    }

    @Test
    fun functionDeclarationNamesAreTokenizedCorrectly() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        listOf(2 to "rotate", 11 to "rotate90DegreesAroundOrigo").forEach { (line, name) ->
            val token = tokens.firstOrNull { it.line == line && it.text == name }
            assertNotNull(token) { "Expected '$name' token on line $line" }
            assertEquals(BwslTokenTypes.FUNCTION_DECLARATION, token!!.type) {
                "'$name' on line $line should be FUNCTION_DECLARATION"
            }
        }
    }

    @Test
    fun cosAndSinAreIntrinsicCalls() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val intrinsics = tokens.filter { it.text == "cos" || it.text == "sin" }
        assertNotNull(intrinsics.firstOrNull()) { "Expected cos/sin tokens in file" }
        intrinsics.forEach { token ->
            assertEquals(BwslTokenTypes.INTRINSIC_CALL, token.type) {
                "'${token.text}' on line ${token.line} should be INTRINSIC_CALL"
            }
        }
    }

    private data class Token(val line: Int, val text: String, val type: IElementType)

    private fun tokenizeResource(path: String): List<Token> {
        val content = File(javaClass.classLoader.getResource(path)!!.toURI()).readText()
        val lineStartOffsets = buildLineStartOffsets(content)
        val lexer = BwslLexerAdapter()
        lexer.start(content, 0, content.length, 0)
        val result = mutableListOf<Token>()
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val text = content.substring(start, lexer.tokenEnd)
            val line = lineStartOffsets.indexOfLast { it <= start } + 1
            result += Token(line, text, lexer.tokenType!!)
            lexer.advance()
        }
        return result
    }

    private fun buildLineStartOffsets(content: String): List<Int> {
        val offsets = mutableListOf(0)
        content.forEachIndexed { i, c -> if (c == '\n') offsets += i + 1 }
        return offsets
    }
}
