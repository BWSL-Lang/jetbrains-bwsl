package com.bwsl.plugin

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.CommandProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * The live templates: each expands where it belongs to code the real compiler accepts, and is offered only there
 * (`module`/`pipeline` at the top level, `pass` in a pipeline, functions where they can be declared, loops in a body).
 */
class BwslLiveTemplatesTest : BasePlatformTestCase() {

    private val compiler: String get() = System.getProperty("bwslc.path") ?: error("System property 'bwslc.path' is not set")

    private val inModule = "module M {\n    <caret>\n}\n"
    private val inPipeline = "pipeline P {\n    attributes { position: float4 }\n    <caret>\n}\n"
    private val inBody = "module M {\n    f :: (int count, float[4] values, int width, int height, bool done, bool condition) -> float {\n" +
        "        float sum = 0.0;\n        <caret>\n        return sum;\n    }\n}\n"

    override fun setUp() {
        super.setUp()
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    }

    /** Types [abbreviation] at the caret in [source], expands it with Tab and takes every default. */
    private fun expand(source: String, abbreviation: String): String {
        myFixture.configureByText("templates.bwsl", source)
        myFixture.type(abbreviation)
        myFixture.performEditorAction(IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
        val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
        if (state != null) CommandProcessor.getInstance().executeCommand(project, { state.gotoEnd(false) }, null, null)
        return myFixture.editor.document.text
    }

    private fun collectErrors(text: String): List<String> {
        val path = Files.createTempDirectory("bwsl_templates_").resolve("templates.bwsl").toString()
        return collectDiagnostics(compiler, path, emptyList(), stdinText = text).filter { it.severity == "error" }.map { "${it.line}:${it.column} ${it.message}" }
    }

    private fun assertExpandsToValidCode(source: String, abbreviation: String, expected: String) {
        val text = expand(source, abbreviation)
        assertTrue("'$abbreviation' did not expand:\n$text", text.contains(expected))
        assertEquals("'$abbreviation' expands to:\n$text", emptyList<String>(), collectErrors(text))
    }

    fun testModule() = assertExpandsToValidCode("<caret>\n", "mod", "module Name {")

    fun testPipeline() = assertExpandsToValidCode("<caret>\n", "pipe", "pipeline Name {\n    attributes {\n        position: float4\n    }")

    fun testPass() = assertExpandsToValidCode(inPipeline, "pass", "    pass \"Main\" {\n        use attributes { position }")

    fun testFunctionInAModuleIsIndentedToIt() = assertExpandsToValidCode(inModule, "fn", "module M {\n    name :: (float x) -> float {\n")

    fun testLoops() {
        for ((abbreviation, expected) in listOf(
            "fori" to "for (int i = 0; i < count; i++) {",
            "forr" to "for (i in 0..count) {",
            "fore" to "for (item in values) {",
            "forxy" to "foreach (x in 0..width, y in 0..height) {",
            "loopn" to "loop (count) {",
            "loopu" to "} until (done)",
            "wh" to "while (condition) {",
        )) {
            assertExpandsToValidCode(inBody, abbreviation, expected)
        }
    }

    private fun collectApplicable(source: String): Set<String> {
        myFixture.configureByText("context.bwsl", source)
        val context = TemplateActionContext.expanding(myFixture.file, myFixture.editor)
        return TemplateManagerImpl.listApplicableTemplates(context).filter { it.groupName == "BWSL" }.map { it.key }.toSet()
    }

    fun testEachTemplateIsOfferedOnlyWhereItBelongs() {
        val loops = setOf("fori", "forr", "fore", "forxy", "loopn", "loopu", "wh")
        assertEquals(setOf("mod", "pipe"), collectApplicable("<caret>\n"))
        assertEquals(setOf("fn"), collectApplicable(inModule))
        assertEquals(setOf("fn", "pass"), collectApplicable(inPipeline))
        assertEquals(setOf("fn"), collectApplicable("pipeline P {\n    pass \"Main\" {\n        <caret>\n    }\n}\n"))
        assertEquals(setOf("fn"), collectApplicable("module M {\n    struct S {\n        float a;\n        <caret>\n    }\n}\n"))
        assertEquals(loops, collectApplicable(inBody))
        assertEquals(loops, collectApplicable("pipeline P {\n    pass \"Main\" {\n        vertex {\n            if (true) {\n                <caret>\n            }\n        }\n    }\n}\n"))
        assertEquals(emptySet<String>(), collectApplicable("pipeline P {\n    attributes {\n        <caret>\n    }\n}\n"))
        assertEquals(emptySet<String>(), collectApplicable("// a comment <caret>\nmodule M { }\n"))
    }
}
