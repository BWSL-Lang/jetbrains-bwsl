package com.bwsl.plugin

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * What the formatter does when it is asked to do more than indent and space: place braces, wrap long
 * lines and align the lines of an expression. All of it is off by default (see BwslFormattingTest).
 */
class BwslFormattingOptionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            CodeStyle.dropTemporarySettings(project)
        } finally {
            super.tearDown()
        }
    }

    private fun useSettings(configure: (BwslCodeStyleSettings, CommonCodeStyleSettings) -> Unit) {
        val settings = CodeStyle.createTestSettings(CodeStyle.getSettings(project))
        configure(settings.getCustomSettings(BwslCodeStyleSettings::class.java), settings.getCommonSettings(BwslLanguage))
        CodeStyle.setTemporarySettings(project, settings)
    }

    private fun formatText(source: String): String {
        myFixture.configureByText("options.bwsl", source)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.file.text
    }

    private fun collectTokens(text: String): List<Pair<String, String>> {
        val lexer = BwslLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<Pair<String, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) tokens += lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return tokens
    }

    private val endOfLine = """
        module M {
            f :: (float a) -> float {
                if (a > 0.0) {
                    return 1.0;
                } else {
                    return 2.0;
                }
            }
            g :: () -> float { return 1.0; }
        }
    """.trimIndent()

    private val nextLine = """
        module M
        {
            f :: (float a) -> float
            {
                if (a > 0.0)
                {
                    return 1.0;
                }
                else
                {
                    return 2.0;
                }
            }
            g :: () -> float { return 1.0; }
        }
    """.trimIndent()

    // --- braces ------------------------------------------------------------------------------------------

    fun testBracesAreLeftWhereTheyAreWrittenByDefault() {
        assertEquals(nextLine, formatText(nextLine))
        assertEquals(endOfLine, formatText(endOfLine))
    }

    fun testNextLineStyleMovesTheBraceOfEveryMultiLineBlockAndTheElse() {
        useSettings { bwsl, _ -> bwsl.BRACE_STYLE = BraceStyle.NEXT_LINE }

        assertEquals(nextLine, formatText(endOfLine))
    }

    fun testEndOfLineStyleBringsTheBraceBackAndCuddlesTheElse() {
        useSettings { bwsl, _ -> bwsl.BRACE_STYLE = BraceStyle.END_OF_LINE }

        assertEquals(endOfLine, formatText(nextLine))
    }

    fun testASingleLineBlockAndALabelsBraceAreNeverMoved() {
        useSettings { bwsl, _ -> bwsl.BRACE_STYLE = BraceStyle.NEXT_LINE }
        val source = """
            pipeline P
            {
                attributes
                {
                    position: float4
                }
                f :: (int x) -> float
                {
                    float a = 0.0;
                    switch (x)
                    {
                        case 0: { a = 1.0; }
                        case 1: {
                            a = 2.0;
                        }
                    }
                    return a;
                }
                pass "Main" { use attributes { position } }
            }
        """.trimIndent()

        val result = formatText(source)

        assertTrue("case 0's block stays on its line, got:\n$result", result.contains("case 0: { a = 1.0; }"))
        assertTrue("a block after a label stays on the label's line, got:\n$result", result.contains("case 1: {\n"))
        assertTrue("a single-line block stays, got:\n$result", result.contains("pass \"Main\" { use attributes { position } }"))
    }

    fun testBraceStylesAreStableWhenFormattedAgain() {
        for (style in listOf(BraceStyle.END_OF_LINE, BraceStyle.NEXT_LINE)) {
            useSettings { bwsl, _ -> bwsl.BRACE_STYLE = style }
            val once = formatText(endOfLine)

            assertEquals("style $style", once, formatText(once))
        }
    }

    // --- wrapping ----------------------------------------------------------------------------------------

    private val longCall = "module M {\n    f :: () -> float {\n        float r = blend(alpha, beta, gamma, delta, epsilon, zeta);\n        return r;\n    }\n}"

    fun testLongLinesAreNotWrappedByDefault() {
        useSettings { _, common -> common.RIGHT_MARGIN = 40 }

        assertEquals(longCall, formatText(longCall))
    }

    fun testALongCallIsChoppedOneArgumentPerLine() {
        useSettings { bwsl, common ->
            bwsl.WRAP_CALL_ARGUMENTS = true
            common.RIGHT_MARGIN = 50
        }

        assertEquals(
            "module M {\n    f :: () -> float {\n        float r = blend(alpha,\n            beta,\n            gamma,\n            delta,\n            epsilon,\n            zeta);\n        return r;\n    }\n}",
            formatText(longCall)
        )
    }

    fun testACallThatFitsIsLeftOnOneLine() {
        useSettings { bwsl, common ->
            bwsl.WRAP_CALL_ARGUMENTS = true
            common.RIGHT_MARGIN = 120
        }

        assertEquals(longCall, formatText(longCall))
    }

    fun testWrappedLinesAreStableWhenFormattedAgain() {
        useSettings { bwsl, common ->
            bwsl.WRAP_CALL_ARGUMENTS = true
            common.RIGHT_MARGIN = 50
        }
        val once = formatText(longCall)

        assertEquals(once, formatText(once))
    }

    // --- alignment ---------------------------------------------------------------------------------------

    fun testAContinuedExpressionLinesUpWithItsStartWhenAsked() {
        useSettings { bwsl, _ -> bwsl.ALIGN_CONTINUED_EXPRESSIONS = true }
        val source = "module M {\n    f :: (float a, float b) -> float {\n        float x = a +\n            b * 2.0 +\n            a;\n        return x;\n    }\n}"

        assertEquals(
            "module M {\n    f :: (float a, float b) -> float {\n        float x = a +\n                  b * 2.0 +\n                  a;\n        return x;\n    }\n}",
            formatText(source)
        )
    }

    fun testTheLinesInsideParenthesesLineUpWithTheFirstArgumentWhenAsked() {
        useSettings { bwsl, _ -> bwsl.ALIGN_CONTINUED_EXPRESSIONS = true }
        val source = "module M {\n    f :: (float a, float b) -> float {\n        return max(a,\n            b);\n    }\n}"

        assertEquals(
            "module M {\n    f :: (float a, float b) -> float {\n        return max(a,\n                   b);\n    }\n}",
            formatText(source)
        )
    }

    fun testWithoutTheOptionContinuedLinesAreIndentedAsBefore() {
        val source = "module M {\n    f :: (float a, float b) -> float {\n        float x = a +\n            b;\n        return x;\n    }\n}"

        assertEquals(source, formatText(source))
    }

    // --- the compiler's own sources ------------------------------------------------------------------------

    /**
     * Every option on at once, over a sample of the compiler's own sources: no token may change, and a second
     * pass must change nothing, whatever the layout turns out to be.
     */
    fun testAllOptionsTogetherKeepEveryTokenAndAreStableOnTheCompilersOwnSources() {
        useSettings { bwsl, common ->
            bwsl.BRACE_STYLE = BraceStyle.NEXT_LINE
            bwsl.ALIGN_CONTINUED_EXPRESSIONS = true
            bwsl.WRAP_CALL_ARGUMENTS = true
            common.RIGHT_MARGIN = 80
        }
        val repository = System.getProperty("bwslc.path")?.let { File(it).parentFile?.parentFile } ?: return
        val files = listOf("tests", "modules").map { File(repository, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "bwsl" }.toList() }
            .sortedBy { it.path }
            .filterIndexed { index, _ -> index % 7 == 0 }
        if (files.isEmpty()) return

        val problems = ArrayList<String>()
        for (file in files) {
            val original = file.readText().replace("\r\n", "\n")
            val once = formatText(original)
            if (collectTokens(once) != collectTokens(original)) problems += "${file.name}: tokens changed"
            val twice = formatText(once)
            if (twice != once) problems += "${file.name}: not stable"
        }

        assertTrue("${problems.size} of ${files.size} files: ${problems.take(10)}", problems.isEmpty())
    }
}
