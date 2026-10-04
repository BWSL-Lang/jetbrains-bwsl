package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/**
 * Declarations that shadow another name in scope. bwslc allows shadowing without a word, so the
 * plugin finds it from the compiled AST, using the same notion of "in scope" as completion.
 */
class BwslShadowingTest : BasePlatformTestCase() {

    private fun collectShadowing(source: String): List<ShadowingDeclaration> {
        val (_, root) = BwslcAstHelper.buildIndexAndRoot(source)
        return collectShadowingDeclarations(root, BwslcAstHelper.parseRaw(source))
    }

    /** "name at line:column" for each shadowing declaration, so a test reads as the list of places. */
    private fun describePlaces(source: String): List<String> =
        collectShadowing(source).map { "${it.name} ${it.kind.label} over ${it.shadowed.label} at ${it.line}:${it.column}" }

    fun testLocalThatReusesAParameterNameShadowsIt() {
        val places = describePlaces(
            """
            module M {
                f :: (float x) -> float {
                    float x = 1.0;
                    return x;
                }
            }
            """.trimIndent()
        )

        assertEquals(listOf("x variable over parameter at 3:15"), places)
    }

    fun testLocalInANestedBlockShadowsTheOneOutside() {
        val places = describePlaces(
            """
            module M {
                f :: (float a) -> float {
                    float y = a;
                    if (a > 0.0) {
                        float y = 2.0;
                        return y;
                    }
                    return y;
                }
            }
            """.trimIndent()
        )

        assertEquals(listOf("y variable over variable at 5:19"), places)
    }

    fun testSameNameInSiblingBlocksOrAfterABlockHasClosedIsNotShadowing() {
        val places = describePlaces(
            """
            module M {
                f :: (float a) -> float {
                    if (a > 0.0) {
                        float t = 1.0;
                    }
                    if (a < 0.0) {
                        float t = 2.0;
                    }
                    float t = 3.0;
                    return t;
                }
            }
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), places)
    }

    fun testSameNameInAnotherFunctionIsNotShadowing() {
        val places = describePlaces(
            """
            module M {
                f :: (float x) -> float { float s = x; return s; }
                g :: (float x) -> float { float s = x; return s; }
            }
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), places)
    }

    fun testParameterAndLocalThatReuseAModuleConstNameShadowIt() {
        val places = describePlaces(
            """
            module M {
                const float K = 1.0;
                f :: (float K) -> float { return K; }
                g :: () -> float {
                    float K = 2.0;
                    return K;
                }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("K parameter over constant at 3:17", "K variable over constant at 5:15"),
            places
        )
    }

    fun testLoopVariablesShadowAndAreShadowed() {
        val places = describePlaces(
            """
            module M {
                f :: (float n) -> float {
                    float s = 0.0;
                    for (n in 0..4) {
                        float s = 1.0;
                        s = s + float(n);
                    }
                    return s;
                }
            }
            """.trimIndent()
        )

        assertEquals(
            listOf("n loop variable over parameter at 4:14", "s variable over variable at 5:19"),
            places
        )
    }

    fun testTheSameLoopVariableNameInSiblingLoopsIsNotShadowing() {
        val places = describePlaces(
            """
            module M {
                f :: () -> float {
                    float s = 0.0;
                    for (int i = 0; i < 3; i++) {
                        s = s + float(i);
                    }
                    for (int i = 0; i < 3; i++) {
                        s = s + float(i);
                    }
                    return s;
                }
            }
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), places)
    }

    fun testPlainCodeHasNoShadowing() {
        val places = describePlaces(
            """
            module M {
                const float K = 2.0;
                f :: (float a, float b) -> float {
                    float c = a * K;
                    return c + b;
                }
            }
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), places)
    }

    fun testTheWarningCoversTheDeclaredNameAndSaysWhatItShadows() {
        val source = """
            module M {
                f :: (float x) -> float {
                    float x = 1.0;
                    return x;
                }
            }
        """.trimIndent()

        val warnings = buildShadowingWarnings(source, collectShadowing(source))

        assertEquals(1, warnings.size)
        val (range, message) = warnings.single()
        assertEquals("x", source.substring(range.startOffset, range.endOffset))
        assertEquals(source.indexOf("float x = 1.0;") + "float ".length, range.startOffset)
        assertEquals("Variable 'x' shadows a parameter of the same name", message)
    }

    fun testTheEditorShowsAWeakWarningForTheShadowingDeclarationAndOnlyWhileTheTextIsWhatWasCompiled() {
        val original = BwslSettings.getInstance().compilerPath
        BwslSettings.getInstance().compilerPath = System.getProperty("bwslc.path")
            ?: error("System property 'bwslc.path' is not set (expected to be provided by the 'test' Gradle task)")
        val directory = Files.createTempDirectory("bwsl_shadow_test_").toFile()
        try {
            val file = File(directory, "Shadow.bwsl")
            file.writeText("module Shadow {\n    f :: (float x) -> float {\n        float x = 1.0;\n        return x;\n    }\n}\n")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
            myFixture.configureFromExistingVirtualFile(virtualFile)

            val warnings = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            assertEquals(
                "one weak warning, got: ${warnings.map { it.description }}",
                listOf("Variable 'x' shadows a parameter of the same name"),
                warnings.map { it.description }
            )

            // An unsaved edit moves the text away from what bwslc compiled, so its positions are not trusted.
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "// edited\n")
            }
            val afterEdit = myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
                .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            assertEquals("no warning while the editor differs from the compiled text", emptyList<String>(), afterEdit.map { it.description })
        } finally {
            BwslSettings.getInstance().compilerPath = original
            directory.deleteRecursively()
        }
    }
}
