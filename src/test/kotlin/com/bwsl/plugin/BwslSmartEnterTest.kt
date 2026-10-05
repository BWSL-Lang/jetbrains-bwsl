package com.bwsl.plugin

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Complete Current Statement (Ctrl+Shift+Enter): close brackets, add the `;`, or add the block a header needs. */
class BwslSmartEnterTest : BasePlatformTestCase() {

    private fun body(line: String): String = "module M {\n    f :: (float a, float b) -> float {\n        $line\n    }\n}"

    private fun completeStatement(sourceWithCaret: String) {
        myFixture.configureByText("enter.bwsl", sourceWithCaret)
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_COMPLETE_STATEMENT)
    }

    fun testAStatementWithoutItsSemicolonGetsOne() {
        completeStatement(body("float x = a * b<caret>"))

        myFixture.checkResult(body("float x = a * b;<caret>"))
    }

    fun testOpenBracketsAreClosedBeforeTheSemicolon() {
        completeStatement(body("return max(a, blend(b<caret>"))

        myFixture.checkResult(body("return max(a, blend(b));<caret>"))
    }

    fun testIndexingBracketsAreClosedToo() {
        completeStatement(body("float x = values[1<caret>"))

        myFixture.checkResult(body("float x = values[1];<caret>"))
    }

    fun testItWorksFromAnywhereOnTheLine() {
        completeStatement(body("float x = <caret>a * b"))

        myFixture.checkResult(body("float x = a * b;<caret>"))
    }

    fun testAnIfHeaderGetsItsConditionClosedAndABlock() {
        completeStatement(body("if (a > 0.0<caret>"))

        myFixture.checkResult(body("if (a > 0.0) {\n            <caret>\n        }"))
    }

    fun testALoopHeaderGetsABlock() {
        completeStatement(body("for (int i = 0; i < 3; i++)<caret>"))

        myFixture.checkResult(body("for (int i = 0; i < 3; i++) {\n            <caret>\n        }"))
    }

    fun testAnElseGetsABlock() {
        completeStatement(body("else<caret>"))

        myFixture.checkResult(body("else {\n            <caret>\n        }"))
    }

    fun testAFunctionDeclarationGetsItsBody() {
        completeStatement("module M {\n    g :: (float x) -> float<caret>\n}")

        myFixture.checkResult("module M {\n    g :: (float x) -> float {\n        <caret>\n    }\n}")
    }

    fun testAModuleHeaderGetsItsBlock() {
        completeStatement("module M<caret>")

        myFixture.checkResult("module M {\n    <caret>\n}")
    }

    fun testAStructFieldAndAConstantGetASemicolon() {
        completeStatement("module M {\n    struct S {\n        float b<caret>\n    }\n}")
        myFixture.checkResult("module M {\n    struct S {\n        float b;<caret>\n    }\n}")

        completeStatement("module M {\n    const float K = 2.0<caret>\n}")
        myFixture.checkResult("module M {\n    const float K = 2.0;<caret>\n}")
    }

    fun testWhereNoSemicolonIsWrittenNothingIsAdded() {
        // An entry of an attributes block, an import and a `case` label are not statements.
        for (line in listOf("position: float4", "import Math", "case 0:")) {
            val source = "module M {\n    $line<caret>\n}"
            completeStatement(source)

            assertFalse("no semicolon after '$line', got: ${myFixture.editor.document.text}", myFixture.editor.document.text.contains("$line;"))
        }
        completeStatement("pipeline P {\n    attributes {\n        position: float4<caret>\n    }\n}")
        assertFalse(myFixture.editor.document.text.contains("float4;"))
    }

    fun testALineThatGoesOnIsNotGivenASemicolon() {
        completeStatement(body("float x = a +<caret>"))

        assertFalse(myFixture.editor.document.text.contains("+;"))
    }

    fun testACompleteStatementOrAnEmptyLineJustStartsANewLine() {
        completeStatement(body("float x = a;<caret>"))
        assertEquals(1, myFixture.editor.document.text.lines().count { it.trim() == "float x = a;" })
        assertTrue("a new line follows, got: ${myFixture.editor.document.text}", myFixture.editor.document.lineCount > 5)

        completeStatement(body("// a note<caret>"))
        assertTrue(myFixture.editor.document.text.contains("// a note"))
    }

    fun testAHeaderThatAlreadyHasItsBlockIsLeftAlone() {
        completeStatement(body("if (a > 0.0) {<caret>"))

        assertFalse(myFixture.editor.document.text.contains("{ {"))
        assertEquals(1, Regex("if \\(a > 0.0\\)").findAll(myFixture.editor.document.text).count())
    }

    fun testThePlanNamesTheEditsWithoutTouchingTheDocument() {
        myFixture.configureByText("plan.bwsl", body("float x = a * b<caret>"))

        val plan = planStatementCompletion(myFixture.file, myFixture.editor.caretModel.offset)

        assertNotNull(plan)
        assertEquals(listOf(";"), plan!!.insertions.map { it.text })
        assertFalse(plan.opensBlock)
        assertEquals(body("float x = a * b<caret>").replace("<caret>", ""), myFixture.file.text)
    }
}
