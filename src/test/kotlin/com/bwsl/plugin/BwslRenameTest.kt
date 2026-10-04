package com.bwsl.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManager

/**
 * Rename of declarations, driven the way the IDE does it: the target is whatever is at the caret (a
 * declaration's own name, or a use that resolves to one), and the new name is applied to the
 * declaration and to every usage the compiler's reference index found.
 */
class BwslRenameTest : BwslAstFixtureTestCase() {

    /** Configures [sourceWithCaret] with its real AST cached, renames the target at the caret, and returns the resulting text. */
    private fun renameTargetAtCaret(sourceWithCaret: String, newName: String): String {
        configureAndCache(sourceWithCaret)
        myFixture.renameElementAtCaret(newName)
        return myFixture.file.text
    }

    fun testRenameFunctionFromItsDeclarationNameRenamesTheCalls() {
        val result = renameTargetAtCaret(
            """
            module M {
                hel<caret>per :: (float x) -> float { return x; }
                first :: () -> float { return helper(1.0); }
                second :: () -> float { return helper(2.0) + helper(3.0); }
            }
            """.trimIndent(),
            "adjust"
        )

        assertEquals(
            """
            module M {
                adjust :: (float x) -> float { return x; }
                first :: () -> float { return adjust(1.0); }
                second :: () -> float { return adjust(2.0) + adjust(3.0); }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameFunctionFromACallRenamesTheDeclarationToo() {
        val result = renameTargetAtCaret(
            """
            module M {
                helper :: (float x) -> float { return x; }
                first :: () -> float { return hel<caret>per(1.0); }
            }
            """.trimIndent(),
            "adjust"
        )

        assertEquals(
            """
            module M {
                adjust :: (float x) -> float { return x; }
                first :: () -> float { return adjust(1.0); }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameLeavesSameNamedFunctionsInOtherModulesAlone() {
        val result = renameTargetAtCaret(
            """
            module A {
                sc<caret>ale :: (float x) -> float { return x; }
                run :: () -> float { return scale(1.0); }
            }
            module B {
                scale :: (float x) -> float { return x * 2.0; }
                run :: () -> float { return scale(1.0); }
            }
            """.trimIndent(),
            "grow"
        )

        assertEquals(
            """
            module A {
                grow :: (float x) -> float { return x; }
                run :: () -> float { return grow(1.0); }
            }
            module B {
                scale :: (float x) -> float { return x * 2.0; }
                run :: () -> float { return scale(1.0); }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameParameterStaysInsideItsFunction() {
        val result = renameTargetAtCaret(
            """
            module M {
                first :: (float <caret>v) -> float {
                    float t = v;
                    return t + v;
                }
                second :: (float v) -> float {
                    return v;
                }
            }
            """.trimIndent(),
            "amount"
        )

        assertEquals(
            """
            module M {
                first :: (float amount) -> float {
                    float t = amount;
                    return t + amount;
                }
                second :: (float v) -> float {
                    return v;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameLocalVariableRenamesReadsAndWrites() {
        val result = renameTargetAtCaret(
            """
            module M {
                first :: (float v) -> float {
                    float <caret>t = v;
                    t = t + v;
                    return t;
                }
            }
            """.trimIndent(),
            "total"
        )

        assertEquals(
            """
            module M {
                first :: (float v) -> float {
                    float total = v;
                    total = total + v;
                    return total;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameStructRenamesEveryTypeUse() {
        val result = renameTargetAtCaret(
            """
            module M {
                struct B<caret>ox {
                    float size;
                }
                make :: (Box seed) -> Box {
                    Box b;
                    return b;
                }
            }
            """.trimIndent(),
            "Crate"
        )

        assertEquals(
            """
            module M {
                struct Crate {
                    float size;
                }
                make :: (Crate seed) -> Crate {
                    Crate b;
                    return b;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameFieldRenamesItsMemberAccesses() {
        val result = renameTargetAtCaret(
            """
            module M {
                struct Box {
                    float si<caret>ze;
                }
                make :: (Box seed) -> Box {
                    Box b;
                    b.size = seed.size;
                    return b;
                }
            }
            """.trimIndent(),
            "extent"
        )

        assertEquals(
            """
            module M {
                struct Box {
                    float extent;
                }
                make :: (Box seed) -> Box {
                    Box b;
                    b.extent = seed.extent;
                    return b;
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameConstRenamesUsesTheParserFoldedIntoLiterals() {
        val result = renameTargetAtCaret(
            """
            module M {
                const float <caret>K = 2.0;
                f :: () -> float { return K * K; }
            }
            """.trimIndent(),
            "SCALE"
        )

        assertEquals(
            """
            module M {
                const float SCALE = 2.0;
                f :: () -> float { return SCALE * SCALE; }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameAttributeRenamesTheUseListAndMemberAccesses() {
        val result = renameTargetAtCaret(
            """
            pipeline P {
                attributes {
                    position: float4
                    nor<caret>mal: float3
                }
                pass "Main" {
                    use attributes { position, normal }
                    vertex {
                        output.pos = attributes.position;
                        output.n = attributes.normal;
                    }
                }
            }
            """.trimIndent(),
            "direction"
        )

        assertEquals(
            """
            pipeline P {
                attributes {
                    position: float4
                    direction: float3
                }
                pass "Main" {
                    use attributes { position, direction }
                    vertex {
                        output.pos = attributes.position;
                        output.n = attributes.direction;
                    }
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenamingTheFirstAttributeIsReportedBecauseTheCompilerRequiresItsName() {
        val failure = renameFailureAtCaret(
            """
            pipeline P {
                attributes {
                    posi<caret>tion: float4
                }
                pass "Main" {
                    use attributes { position }
                    vertex {
                        output.pos = attributes.position;
                    }
                }
            }
            """.trimIndent(),
            "vertexPosition"
        )

        assertTrue("expected the compiler's rule, got: $failure", failure.contains("First attribute must be 'position'"))
    }

    fun testRenameFragmentOutputRenamesItsAssignments() {
        val result = renameTargetAtCaret(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                pass "Main" {
                    use attributes { position }
                    outputs {
                        res<caret>ult: float4
                    }
                    vertex {
                        output.pos = attributes.position;
                    }
                    fragment {
                        output.result = float4(1.0);
                    }
                }
            }
            """.trimIndent(),
            "color"
        )

        assertEquals(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                pass "Main" {
                    use attributes { position }
                    outputs {
                        color: float4
                    }
                    vertex {
                        output.pos = attributes.position;
                    }
                    fragment {
                        output.color = float4(1.0);
                    }
                }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameModuleRenamesItsQualifiers() {
        val result = renameTargetAtCaret(
            """
            module Co<caret>mmon {
                helper :: () -> float { return 1.0; }
            }
            module M {
                run :: () -> float { return Common::helper(); }
            }
            """.trimIndent(),
            "Shared"
        )

        assertEquals(
            """
            module Shared {
                helper :: () -> float { return 1.0; }
            }
            module M {
                run :: () -> float { return Shared::helper(); }
            }
            """.trimIndent(),
            result
        )
    }

    fun testRenameModuleDeclaredInAFileOfTheSameNameRenamesTheFileToo() {
        // The compiler finds a module in a file named after it, so the file has to follow.
        val source = """
            module Co<caret>mmon {
                helper :: () -> float { return 1.0; }
            }
        """.trimIndent()
        myFixture.configureByText("Common.bwsl", source)
        com.bwsl.plugin.completion.BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        myFixture.renameElementAtCaret("Shared")

        assertEquals("Shared.bwsl", myFixture.file.name)
        assertTrue(myFixture.file.text.startsWith("module Shared {"))
    }

    /** Adds a new caller of `helper` inside the module, after the compile - the usage a stale AST cannot know about. */
    private fun addCallerOfHelperAfterTheCompile() {
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.insertString(document.text.lastIndexOf("}"), "    later :: () -> float { return helper(2.0); }\n")
            // The IDE commits documents before it starts a refactoring.
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
    }

    private fun configureModuleWithHelperAtCaret() {
        configureAndCache(
            """
            module M {
                hel<caret>per :: (float x) -> float { return x; }
                first :: () -> float { return helper(1.0); }
            }
            """.trimIndent()
        )
    }

    fun testRenameRefusesAFileWithUnsavedChanges() {
        configureModuleWithHelperAtCaret()
        addCallerOfHelperAfterTheCompile()
        val before = myFixture.file.text

        val failure = runCatching { myFixture.renameElementAtCaret("adjust") }.exceptionOrNull()

        assertTrue("expected the unsaved-changes refusal, got: ${describeFailure(failure)}", describeFailure(failure).contains("unsaved changes"))
        assertEquals("a rename must not touch a file with changes the compiler has not seen", before, myFixture.file.text)
    }

    fun testRenameRefusesASavedFileThatChangedSinceTheCompile() {
        // Saved, so there are no unsaved changes, and the positions the compiler knows are still right -
        // but the new caller is not in its AST, and a rename would leave it pointing at the old name.
        configureModuleWithHelperAtCaret()
        addCallerOfHelperAfterTheCompile()
        FileDocumentManager.getInstance().saveAllDocuments()
        val before = myFixture.file.text

        val failure = runCatching { myFixture.renameElementAtCaret("adjust") }.exceptionOrNull()

        assertTrue("expected the changed-since-compile refusal, got: ${describeFailure(failure)}", describeFailure(failure).contains("has changed since the compiler last checked"))
        assertEquals("a rename must not leave a usage behind", before, myFixture.file.text)
    }

    fun testRenameWorksAgainOnceTheCompilerHasSeenTheChange() {
        configureModuleWithHelperAtCaret()
        addCallerOfHelperAfterTheCompile()
        FileDocumentManager.getInstance().saveAllDocuments()
        // The compiler re-checks the saved text.
        val text = myFixture.file.text
        com.bwsl.plugin.completion.BwslcAstHelper.parseAndCache(text, myFixture.file.virtualFile.path)

        myFixture.renameElementAtCaret("adjust")

        assertEquals(
            text.replace("helper", "adjust"),
            myFixture.file.text
        )
        assertTrue("the new caller is renamed too", myFixture.file.text.contains("return adjust(2.0);"))
    }

    /** Configures [sourceWithCaret], tries to rename the target at the caret, and returns why it was refused or "no failure". */
    private fun renameFailureAtCaret(sourceWithCaret: String, newName: String): String {
        configureAndCache(sourceWithCaret)
        return describeFailure(runCatching { myFixture.renameElementAtCaret(newName) }.exceptionOrNull())
    }

    fun testRenameToAnotherNameInTheSameBlockIsReportedAsAConflict() {
        val source = """
            module M {
                f :: (float a) -> float {
                    float first = a;
                    float sec<caret>ond = first;
                    return second;
                }
            }
        """.trimIndent()

        val failure = renameFailureAtCaret(source, "first")

        assertTrue("expected the compiler's duplicate-variable error, got: $failure", failure.contains("already declared in this scope"))
        assertTrue("the original text must be untouched", myFixture.file.text.contains("float second = first;"))
    }

    fun testRenameLocalToAParameterNameItWouldCaptureIsReportedAsAConflict() {
        val failure = renameFailureAtCaret(
            """
            module M {
                f :: (float v) -> float {
                    float <caret>t = 1.0;
                    return t + v;
                }
            }
            """.trimIndent(),
            "v"
        )

        assertTrue("expected the changed-meaning conflict, got: $failure", failure.contains("'v' would refer to the variable 'v' instead of the parameter 'v'"))
    }

    fun testRenameFunctionToAnExistingOverloadIsReportedAsAConflict() {
        val failure = renameFailureAtCaret(
            """
            module M {
                f :: (float x) -> float { return x; }
                g<caret> :: (float y) -> float { return y; }
            }
            """.trimIndent(),
            "f"
        )

        assertTrue("expected the compiler's overload error, got: $failure", failure.contains("overload already declared"))
    }

    fun testRenameStructToAnExistingStructNameIsReportedAsAConflict() {
        val failure = renameFailureAtCaret(
            """
            module M {
                struct Box { float a; }
                struct Cra<caret>te { float b; }
            }
            """.trimIndent(),
            "Box"
        )

        assertTrue("expected the compiler's duplicate-struct error, got: $failure", failure.contains("Duplicate struct"))
    }

    fun testRenameToAnUnusedNameReportsNoConflict() {
        val source = """
            module M {
                f :: (float v) -> float {
                    float <caret>t = 1.0;
                    return t + v;
                }
            }
        """.trimIndent()

        assertEquals("no failure", renameFailureAtCaret(source, "w"))
        assertTrue(myFixture.file.text.contains("float w = 1.0;"))
    }

    private val stagePipeline = """
        pipeline P {
            attributes {
                position: float4
            }
            pass "Main" {
                use attributes { position }
                outputs {
                    result: float4
                }
                vertex {
                    output.pos = attributes.position;
                    output.uv = float2(0.0);
                    output.st = float2(1.0);
                }
                fragment {
                    float2 t = input.uv;
                    output.result = float4(t.x, input.uv.y, input.st.x, 1.0);
                }
            }
        }
    """.trimIndent()

    fun testRenameStageValueFromItsFirstAssignmentRenamesEveryWriteAndRead() {
        val result = renameTargetAtCaret(stagePipeline.replace("output.uv", "output.<caret>uv"), "texcoord")

        assertEquals(stagePipeline.replace("uv", "texcoord"), result)
    }

    fun testRenameStageValueFromAFragmentReadRenamesTheVertexAssignmentToo() {
        val result = renameTargetAtCaret(stagePipeline.replace("input.uv.y", "input.<caret>uv.y"), "texcoord")

        assertEquals(stagePipeline.replace("uv", "texcoord"), result)
    }

    fun testRenameStageValueLeavesOtherStageValuesAlone() {
        val result = renameTargetAtCaret(stagePipeline.replace("output.uv", "output.<caret>uv"), "texcoord")

        assertTrue(result.contains("output.st = float2(1.0);"))
        assertTrue(result.contains("input.st.x"))
    }

    fun testRenameStageValueToAnExistingOneIsReportedAsAMerge() {
        val failure = renameFailureAtCaret(stagePipeline.replace("output.uv", "output.<caret>uv"), "st")

        assertTrue("expected the merge conflict, got: $failure", failure.contains("'st' is already a stage value in this pass"))
    }

    fun testRenameStageValueToTheClipPositionIsReportedAsAConflict() {
        val failure = renameFailureAtCaret(stagePipeline.replace("output.uv", "output.<caret>uv"), "pos")

        assertTrue("expected a conflict with the position output, got: $failure", failure.contains("pos"))
        assertFalse("the rename must not go through silently", failure == "no failure")
    }

    fun testNamesValidatorAcceptsPlainIdentifiers() {
        val validator = BwslNamesValidator()

        for (name in listOf("adjust", "_scratch", "value2", "CamelCase")) {
            assertTrue("$name should be a valid name", validator.isIdentifier(name, project))
            assertFalse("$name is not a keyword", validator.isKeyword(name, project))
        }
    }

    fun testNamesValidatorRejectsKeywordsTypesAndNonIdentifiers() {
        val validator = BwslNamesValidator()

        for (name in listOf("module", "pipeline", "return", "float4")) {
            assertTrue("$name is a keyword or type", validator.isKeyword(name, project))
            assertFalse("$name must not be a valid name", validator.isIdentifier(name, project))
        }
        for (name in listOf("", "1abc", "a-b", "two words", "a.b")) {
            assertFalse("'$name' must not be a valid name", validator.isIdentifier(name, project))
        }
    }
}
