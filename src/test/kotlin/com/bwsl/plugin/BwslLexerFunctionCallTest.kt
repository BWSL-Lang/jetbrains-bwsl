package com.bwsl.plugin

import com.intellij.psi.tree.IElementType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.File

class BwslLexerFunctionCallTest {

    @Test
    fun testRotateOnLine15IsAFunctionCall() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val token = tokens.firstOrNull { it.line == 15 && it.text == "rotate" }
        assertNotNull(token) { "Expected 'rotate' token on line 15" }
        assertEquals(BwslTokenTypes.FUNCTION_CALL, token!!.type) { "'rotate' on line 15 should be FUNCTION_CALL" }
    }

    @Test
    fun testIfIsNotAFunctionCall() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val token = tokens.firstOrNull { it.line == 12 && it.text == "if" }
        assertNotNull(token) { "Expected 'if' token on line 12" }
        assertEquals(BwslTokenTypes.KW_IF, token!!.type) { "'if' on line 12 should be KW_IF, not FUNCTION_CALL" }
    }

    @Test
    fun testFunctionDeclarationNamesAreTokenizedCorrectly() {
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
    fun testCosAndSinAreIntrinsicCalls() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val intrinsics = tokens.filter { (it.text == "cos" || it.text == "sin") && it.line != 23 }
        assertNotNull(intrinsics.firstOrNull()) { "Expected cos/sin tokens in file" }
        intrinsics.forEach { token ->
            assertEquals(BwslTokenTypes.INTRINSIC_CALL, token.type) {
                "'${token.text}' on line ${token.line} should be INTRINSIC_CALL"
            }
        }
    }

    @Test
    fun testMethodCallOnReceiverIsNotAnIntrinsic() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val cosCall = tokens.firstOrNull { it.line == 23 && it.text == "cos" }
        assertNotNull(cosCall) { "Expected 'cos' token on line 23" }
        assertEquals(BwslTokenTypes.FUNCTION_CALL, cosCall!!.type) {
            "'cos' on line 23 (values.cos()) should be FUNCTION_CALL, not INTRINSIC_CALL"
        }
    }

    @Test
    fun testLengthCallOnArrayReceiverIsStillAnIntrinsic() {
        val tokens = tokenizeResource("lexer_test_files/module.bwsl")
        val lengthCall = tokens.firstOrNull { it.line == 22 && it.text == "length" }
        assertNotNull(lengthCall) { "Expected 'length' token on line 22" }
        assertEquals(BwslTokenTypes.INTRINSIC_CALL, lengthCall!!.type) {
            "'length' on line 22 (values.length()) should remain INTRINSIC_CALL"
        }
    }

    @Test
    fun testEveryIntrinsicOfTheTableIsAnIntrinsicCall() {
        val notIntrinsic = BwslIntrinsics.NAMES.sorted().filter { name ->
            val source = "module M { f :: () -> float { return $name(1.0); } }"
            val lexer = BwslLexerAdapter()
            lexer.start(source, 0, source.length, 0)
            var type: IElementType? = null
            while (lexer.tokenType != null) {
                if (source.substring(lexer.tokenStart, lexer.tokenEnd) == name) type = lexer.tokenType
                lexer.advance()
            }
            type != BwslTokenTypes.INTRINSIC_CALL
        }

        assertEquals(emptyList<String>(), notIntrinsic) { "intrinsics the lexer takes for ordinary function calls" }
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
