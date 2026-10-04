package com.bwsl.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Reformat Code and auto-indent. The formatter only moves whitespace: the tests check what it does
 * to indentation and to the spaces between tokens, and that it keeps every token in place.
 */
class BwslFormattingTest : BasePlatformTestCase() {

    private fun formatText(source: String): String {
        myFixture.configureByText("format.bwsl", source)
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        return myFixture.file.text
    }

    /** The tokens of [text] without whitespace, as (type, text) pairs. */
    private fun collectTokens(text: String): List<Pair<String, String>> {
        val lexer = BwslLexerAdapter()
        lexer.start(text, 0, text.length, 0)
        val tokens = ArrayList<Pair<String, String>>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != TokenType.WHITE_SPACE) {
                tokens += lexer.tokenType.toString() to text.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        return tokens
    }

    private fun describeFirstTokenDifference(before: String, after: String): String {
        val a = collectTokens(before)
        val b = collectTokens(after)
        val index = a.indices.firstOrNull { it >= b.size || a[it] != b[it] } ?: a.size
        return "tokens changed at #$index: ${a.drop(maxOf(0, index - 2)).take(5)} became ${b.drop(maxOf(0, index - 2)).take(5)}"
    }

    private fun describeFirstTextDifference(first: String, second: String): String {
        val index = first.indices.firstOrNull { it >= second.length || first[it] != second[it] } ?: first.length
        return "'${first.substring(maxOf(0, index - 30), minOf(first.length, index + 30)).replace("\n", "\\n")}'"
    }

    fun testLinesAreIndentedByTheBracesAroundThem() {
        val result = formatText(
            """
            module M {
            f :: (float a) -> float {
                    float b = a;
              if (b > 0.0) {
            b = 1.0;
            }
            return b;
                }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: (float a) -> float {
                    float b = a;
                    if (b > 0.0) {
                        b = 1.0;
                    }
                    return b;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testSpacesAroundTokensAreNormalisedWhereTheMeaningIsClear() {
        val result = formatText(
            "module M {\nf::(float a,float b)->float{\nfloat c=a+b*2.0;\nif(c>=1.0&&a!=b){c-=1.0;}\nreturn c;\n}\n}"
        )

        assertEquals(
            """
            module M {
                f :: (float a, float b) -> float {
                    float c = a + b * 2.0;
                    if (c >= 1.0 && a != b) { c -= 1.0; }
                    return c;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testSignsGenericBracketsQualifiersAndPointersAreLeftAlone() {
        val source = """
            module M {
                f :: (float a) -> float {
                    float b = -a;
                    float c = a * -b;
                    int^ p = ^i;
                    float d = Other::scale(a);
                    return b + c;
                }
            }
            pipeline P {
                resources {
                    output: buffer<float>
                }
            }
        """.trimIndent()

        assertEquals(source, formatText(source))
    }

    fun testBracelessBodiesAreIndentedAndElseSitsWithItsIf() {
        val result = formatText(
            """
            module M {
            f :: (float a) -> float {
            if (a > 0.0)
            return 1.0;
            else
            return 2.0;
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: (float a) -> float {
                    if (a > 0.0)
                        return 1.0;
                    else
                        return 2.0;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testNestedBracelessBodiesEndTogetherAndTheElseBelongsToTheInnerIf() {
        val result = formatText(
            """
            module M {
            f :: (float a, float b) -> float {
            float x = 0.0;
            if (a > 0.0)
            if (b > 0.0)
            x = 1.0;
            else
            x = 2.0;
            return x;
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: (float a, float b) -> float {
                    float x = 0.0;
                    if (a > 0.0)
                        if (b > 0.0)
                            x = 1.0;
                        else
                            x = 2.0;
                    return x;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testABodyOnTheSameLineAsItsHeaderStaysThere() {
        val source = """
            module M {
                f :: (float a) -> float {
                    float x = 0.0;
                    if (a > 0.0) x = 1.0;
                    else if (a < 0.0) x = 2.0;
                    else x = 3.0;
                    return x;
                }
            }
        """.trimIndent()

        assertEquals(source, formatText(source))
    }

    fun testALoopWithOrWithoutAHeaderIndentsItsBody() {
        val result = formatText(
            """
            module M {
            f :: () -> float {
            float s = 0.0;
            loop (3) {
            s = s + 1.0;
            }
            loop {
            s = s + 1.0;
            }
            for (int i = 0; i < 3; i++)
            s = s + 1.0;
            return s;
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: () -> float {
                    float s = 0.0;
                    loop (3) {
                        s = s + 1.0;
                    }
                    loop {
                        s = s + 1.0;
                    }
                    for (int i = 0; i < 3; i++)
                        s = s + 1.0;
                    return s;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testAContinuedExpressionIsIndentedWhetherTheOperatorEndsOrStartsTheLine() {
        val result = formatText(
            """
            module M {
            f :: (float a, float b) -> float {
            float x = a +
            b * 2.0 +
            a;
            float y = a
            + b;
            return x + y;
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: (float a, float b) -> float {
                    float x = a +
                        b * 2.0 +
                        a;
                    float y = a
                        + b;
                    return x + y;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testLinesInsideParenthesesAreContinuations() {
        val result = formatText(
            """
            module M {
            f :: (float a, float b) -> float {
            return max(a,
            b);
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                f :: (float a, float b) -> float {
                    return max(a,
                        b);
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testCommentsTakeTheIndentOfTheCodeAroundThem() {
        val result = formatText(
            """
            module M {
            // about f
            f :: () -> float {
            // inside
            return 1.0; // trailing
            // last
            }
            }
            """.trimIndent()
        )

        assertEquals(
            """
            module M {
                // about f
                f :: () -> float {
                    // inside
                    return 1.0; // trailing
                    // last
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testBlankLinesAreKeptAndTrailingSpacesAreRemoved() {
        val result = formatText("module M {   \n\n\n    f :: () -> float {   \n        return 1.0;\n    }\n}\n")

        assertEquals("module M {\n\n\n    f :: () -> float {\n        return 1.0;\n    }\n}\n", result)
    }

    fun testFormattingTwiceChangesNothing() {
        val once = formatText(
            "module M {\nf::(float a,float b)->float{\nif(a>0.0)\nreturn a +\nb;\nelse\nreturn -b;\n}\n}"
        )

        assertEquals(once, formatText(once))
    }

    fun testEnterAfterAnOpeningBraceIndentsTheNextLine() {
        myFixture.configureByText("enter.bwsl", "module M {<caret>\n}")

        myFixture.type("\n")

        myFixture.checkResult("module M {\n    <caret>\n}")
    }

    fun testEnterAfterAStatementKeepsTheIndent() {
        myFixture.configureByText(
            "enter.bwsl",
            "module M {\n    f :: () -> float {\n        float x = 1.0;<caret>\n    }\n}"
        )

        myFixture.type("\n")

        myFixture.checkResult("module M {\n    f :: () -> float {\n        float x = 1.0;\n        <caret>\n    }\n}")
    }

    fun testEnterAfterABracelessHeaderIndentsTheBody() {
        myFixture.configureByText(
            "enter.bwsl",
            "module M {\n    f :: (float a) -> float {\n        if (a > 0.0)<caret>\n    }\n}"
        )

        myFixture.type("\n")

        myFixture.checkResult("module M {\n    f :: (float a) -> float {\n        if (a > 0.0)\n            <caret>\n    }\n}")
    }

    fun testTypingAClosingBraceMovesItToItsBlocksIndent() {
        myFixture.configureByText(
            "close.bwsl",
            "module M {\n    f :: () -> float {\n        return 1.0;\n        <caret>\n    }\n}"
        )

        myFixture.type("}")

        myFixture.checkResult("module M {\n    f :: () -> float {\n        return 1.0;\n    }<caret>\n    }\n}")
    }

    /**
     * The compiler's own sources, when they are next to the compiler this test runs: every file is
     * formatted twice. The second pass must change nothing, and neither pass may add, drop or reorder
     * a token, which is the same as keeping the program's meaning.
     */
    fun testFormattingSampleOfTheCompilersOwnSourcesKeepsEveryTokenAndIsStable() {
        val repository = System.getProperty("bwslc.path")?.let { File(it).parentFile?.parentFile } ?: return
        val files = listOf("tests", "modules").map { File(repository, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "bwsl" }.toList() }
            .sortedBy { it.path }
            .filterIndexed { index, _ -> index % 5 == 0 }
        if (files.isEmpty()) return

        val problems = ArrayList<String>()
        for (file in files) {
            val original = file.readText().replace("\r\n", "\n")
            val once = formatText(original)
            if (collectTokens(once) != collectTokens(original)) problems += "${file.name}: ${describeFirstTokenDifference(original, once)}"
            if (formatText(once) != once) problems += "${file.name}: not stable, first change at ${describeFirstTextDifference(once, formatText(once))}"
        }

        assertTrue("${problems.size} of ${files.size} files: ${problems.take(10)}", problems.isEmpty())
    }
}
