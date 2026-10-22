package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * The conditional jumps `skip if (c);`, `break if (c);`, `continue if (c);` and `return if (c) value;`: a jump
 * keyword, then an `if` with a condition and no block. They are statements that end at their `;`, not block
 * headers, which is what the formatter, the structure view and Complete Current Statement must treat them as.
 */
class BwslConditionalJumpTest : BwslAstFixtureTestCase() {

    private fun inFunction(vararg lines: String): String =
        "module M {\n    f :: (float x, float y) -> float {\n" + lines.joinToString("") { "        $it\n" } + "        return x;\n    }\n}\n"

    private fun inLoop(vararg lines: String): String =
        inFunction("for (int i = 0; i < 4; i++) {", *lines.map { "    $it" }.toTypedArray(), "}")

    private fun formatText(source: String): String {
        myFixture.configureByText("jump.bwsl", source)
        WriteCommandAction.runWriteCommandAction(project) { CodeStyleManager.getInstance(project).reformat(myFixture.file) }
        return myFixture.file.text
    }

    private fun collectTokenTypes(text: String): List<String> {
        val lexer = BwslLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val types = ArrayList<String>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) types += lexer.tokenType.toString()
            lexer.advance()
        }
        return types
    }

    fun testTheJumpKeywordsAndTheIfAreKeywordTokens() {
        assertEquals(
            listOf("KW_SKIP", "KW_IF", "LPAREN", "IDENTIFIER", "LT", "IDENTIFIER", "RPAREN", "SEMI"),
            collectTokenTypes("skip if (x < y);")
        )
        assertEquals("KW_BREAK", collectTokenTypes("break if (x < y);").first())
        assertEquals("KW_CONTINUE", collectTokenTypes("continue if (x < y);").first())
        assertEquals(listOf("KW_RETURN", "KW_IF"), collectTokenTypes("return if (x < y);").take(2))
    }

    fun testTheFormatterSpacesAConditionalJumpLikeAnyOtherStatement() {
        val source = inLoop("skip if(x < y);", "break if (x>=y);", "continue if(  x==y );")

        val result = formatText(source)

        assertEquals(inLoop("skip if (x < y);", "break if (x >= y);", "continue if (x == y);"), result)
    }

    fun testAReturnWithAConditionAndAValueKeepsItsSpacing() {
        assertEquals(inFunction("return if (x < y) 0.0;"), formatText(inFunction("return if(x < y) 0.0;")))
        assertEquals(inFunction("return if (x < y);"), formatText(inFunction("return if(x < y);")))
    }

    fun testAConditionalJumpDoesNotIndentWhatFollowsIt() {
        val source = inLoop("skip if (x < y);", "float z = x;", "break if (z > y);", "z += 1.0;")

        assertEquals(source, formatText(source))
    }

    fun testReformattingIsStable() {
        val once = formatText(inLoop("skip if(x < y);", "return if(x > y) 1.0;"))

        assertEquals(once, formatText(once))
    }

    private fun completeStatement(sourceWithCaret: String) {
        myFixture.configureByText("enter.bwsl", sourceWithCaret)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
    }

    fun testCompleteStatementAddsTheSemicolonNotABlock() {
        completeStatement(inLoop("skip if (x < y<caret>"))
        assertTrue(myFixture.file.text, myFixture.file.text.contains("skip if (x < y);"))
        assertFalse(myFixture.file.text, myFixture.file.text.contains("skip if (x < y) {"))

        completeStatement(inFunction("return if (x < y<caret>"))
        assertTrue(myFixture.file.text, myFixture.file.text.contains("return if (x < y);"))
    }

    fun testAConditionalJumpIsNotAStructureEntryAndDoesNotHideTheDeclarationsAfterIt() {
        val file = myFixture.configureByText("outline.bwsl", inLoop("skip if (x < y);") + "module Other {\n    g :: () -> float { return 1.0; }\n}\n")

        val names = collectOutline(file).flatMap { listOf(it.name) + it.children.map { child -> child.name } }

        assertEquals(listOf("M", "f", "Other", "g"), names)
    }

    fun testSelectionAroundAConditionalJumpIsTheWholeStatement() {
        val text = inLoop("skip if (x <caret>< y);")
        myFixture.configureByText("select.bwsl", text)

        val ranges = collectSelectionRanges(myFixture.file, myFixture.editor.caretModel.offset)

        assertTrue(ranges.map { myFixture.file.text.substring(it.startOffset, it.endOffset) }.contains("skip if (x < y);"))
    }

    fun testTheCompilerAcceptsThemAndGivesThemTheirOwnNodes() {
        val source = inLoop("skip if (x < y);", "break if (x > y);", "continue if (x == y);", "return if (x < y) 0.0;")

        val types = collectNodeTypes(BwslcAstHelper.parseRaw(source))

        assertTrue(types.toString(), "SKIP_STATEMENT" in types && "BREAK_STATEMENT" in types && "RETURN" in types)
    }

    private fun collectNodeTypes(element: com.google.gson.JsonElement, into: MutableSet<String> = HashSet()): Set<String> {
        if (element.isJsonObject) {
            element.asJsonObject.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.let { into += it }
            for ((_, value) in element.asJsonObject.entrySet()) collectNodeTypes(value, into)
        } else if (element.isJsonArray) {
            for (item in element.asJsonArray) collectNodeTypes(item, into)
        }
        return into
    }

    fun testANameInTheConditionOfAConditionalJumpNavigatesToItsDeclaration() {
        val text = configureAndCache(inLoop("skip if (x < y);", "break if (y > x);", "return if (x < y) y;"))
        val index = buildAstIndex(myFixture.file)!!
        val parameterX = text.indexOf("(float x") + "(float ".length
        val parameterY = text.indexOf("float y") + "float ".length

        for ((use, declaration) in listOf("skip if (x" to parameterX, "break if (y" to parameterY, "return if (x" to parameterX, ") y" to parameterY)) {
            val offset = text.indexOf(use) + use.length - 1
            val targets = resolveSymbolAt(myFixture.file, index, offset).map { it.textOffset }

            assertEquals(use, listOf(declaration), targets)
        }
    }
}
