package com.bwsl.plugin.completion

import com.bwsl.plugin.BwslStdlibSources
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import java.io.File
import java.nio.file.Files

/**
 * Completion of the names a file declares or imports: functions, structs, enums and modules, what
 * follows `Module::`, and what `import` and `using` take. Driven by the cached bwslc AST (what was
 * declared at the last compile) and, for aliases and imports, by the text.
 */
class BwslCompletionNamesTest : BwslCompletionScopeTestCase() {

    fun testFunctionsAndStructsOfTheEnclosingModuleAreSuggestedInABody() {
        assertCompletions(
            """
            module M {
                struct Box { float size; }
                helper :: (float x) -> float { return x; }
                other :: (Box b) -> float { return b.size; }
                f :: (float y) -> float {
                    return <caret>y;
                }
            }
            module Elsewhere {
                notHere :: () -> float { return 1.0; }
            }
            """.trimIndent(),
            present = setOf("helper", "other", "f", "Box"),
            absent = setOf("notHere")
        )
    }

    fun testPipelineLevelFunctionsAreVisibleEverywhereAndPassLevelOnesOnlyInsideTheirPass() {
        assertCompletions(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                common :: (float v) -> float { return v; }
                pass "A" {
                    use attributes { position }
                    outputs {
                        result: float4
                    }
                    onlyA :: (float v) -> float { return v; }
                    vertex {
                        output.pos = attributes.position;
                    }
                    fragment {
                        float t = <caret>1.0;
                        output.result = float4(t);
                    }
                }
                pass "B" {
                    use attributes { position }
                    outputs {
                        result: float4
                    }
                    onlyB :: (float v) -> float { return v; }
                    vertex {
                        output.pos = attributes.position;
                    }
                    fragment {
                        output.result = float4(onlyB(1.0));
                    }
                }
            }
            """.trimIndent(),
            present = setOf("common", "onlyA"),
            absent = setOf("onlyB")
        )
    }

    fun testAModuleQualifierIsFollowedByThatModulesMembersAndNothingElse() {
        assertCompletions(
            """
            module M {
                import Math
                f :: (float x) -> float {
                    return Math::<caret>PI * x;
                }
            }
            """.trimIndent(),
            present = setOf("PI", "TAU", "inverse_lerp"),
            absent = setOf("return", "float", "sin", "x", "f", "Math")
        )
    }

    fun testMembersAreOfferedInsideAnExistingCallNameToo() {
        assertCompletions(
            """
            module M {
                import Math
                f :: (float x) -> float {
                    return Math::<caret>inverse_lerp(0.0, 1.0, x);
                }
            }
            """.trimIndent(),
            present = setOf("PI", "inverse_lerp")
        )
    }

    fun testAnAliasQualifierOffersTheAliasedModulesMembers() {
        assertCompletions(
            """
            module M {
                import Math as MM
                f :: (float x) -> float {
                    return MM::<caret>PI * x;
                }
            }
            """.trimIndent(),
            present = setOf("PI", "inverse_lerp")
        )
    }

    fun testAnEnumQualifierOffersItsValues() {
        assertCompletions(
            """
            module M {
                enum Mode {
                    Solid,
                    Wire
                }
                f :: (float x) -> float {
                    Mode m = Mode::<caret>Solid;
                    return x;
                }
            }
            """.trimIndent(),
            present = setOf("Solid", "Wire"),
            absent = setOf("Mode", "return")
        )
    }

    fun testAnUnknownQualifierOffersNothing() {
        assertCompletions(
            """
            module M {
                f :: (float x) -> float {
                    float a = Nowhere::<caret>b;
                    return x;
                }
            }
            """.trimIndent(),
            absent = setOf("return", "float", "f")
        )
    }

    fun testImportedModulesAreSuggestedAsQualifiers() {
        assertCompletions(
            """
            module M {
                import Math
                f :: (float x) -> float {
                    return <caret>x;
                }
            }
            """.trimIndent(),
            present = setOf("Math")
        )
    }

    fun testUsingMakesAModulesFunctionsAndConstantsAvailableWithoutAQualifier() {
        val withUsing = """
            module M {
                import Math
                using Math
                f :: (float x) -> float {
                    return <caret>x;
                }
            }
        """.trimIndent()
        assertCompletions(withUsing, present = setOf("inverse_lerp", "PI"))

        assertCompletions(withUsing.replace("    using Math\n", ""), absent = setOf("inverse_lerp", "PI"))
    }

    fun testNamesAreNotOfferedAfterADot() {
        assertCompletions(
            """
            module M {
                struct Box { float size; }
                helper :: (Box b) -> float { return b.<caret>size; }
            }
            """.trimIndent(),
            absent = setOf("helper", "Box")
        )
    }

    fun testNoNamesAreOfferedWithoutACachedAst() {
        myFixture.configureByText("no_ast.bwsl", "module M {\n    helper :: () -> float { return 1.0; }\n    f :: () -> float { return <caret>; }\n}")

        val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertFalse("helper must not be suggested without a cached AST, got: $strings", strings.contains("helper"))
    }

    fun testAFunctionIsShownWithItsSignatureAndInsertedWithParentheses() {
        val source = """
            module M {
                helper :: (float x, float2 y) -> float { return x; }
                noArguments :: () -> float { return 1.0; }
                f :: (float z) -> float {
                    return <caret>z;
                }
            }
        """.trimIndent()
        myFixture.configureByText("signature.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val items = myFixture.completeBasic().associateBy { it.lookupString }
        val presentation = LookupElementPresentation().also { items.getValue("helper").renderElement(it) }

        assertEquals("float", presentation.typeText)
        assertEquals("(float x, float2 y)", presentation.tailText)

        (LookupManager.getActiveLookup(myFixture.editor) as LookupImpl).currentItem = items.getValue("helper")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        // With parameters the caret goes inside the parentheses to type the first argument.
        myFixture.checkResult(source.replace("<caret>z;", "helper(<caret>)z;"))
    }

    fun testAFunctionWithoutParametersIsInsertedWithTheCaretAfterTheParentheses() {
        val source = """
            module M {
                noArguments :: () -> float { return 1.0; }
                f :: (float z) -> float {
                    return <caret>z;
                }
            }
        """.trimIndent()
        myFixture.configureByText("signature.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val items = myFixture.completeBasic().associateBy { it.lookupString }
        (LookupManager.getActiveLookup(myFixture.editor) as LookupImpl).currentItem = items.getValue("noArguments")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult(source.replace("<caret>z;", "noArguments()<caret>z;"))
    }

    fun testChoosingAModuleContinuesWithTheQualifier() {
        val source = """
            module M {
                import Math
                f :: (float x) -> float {
                    return <caret>x;
                }
            }
        """.trimIndent()
        myFixture.configureByText("qualifier.bwsl", source)
        BwslcAstHelper.parseAndCache(source.replace("<caret>", ""), myFixture.file.virtualFile.path)

        val items = myFixture.completeBasic().associateBy { it.lookupString }
        (LookupManager.getActiveLookup(myFixture.editor) as LookupImpl).currentItem = items.getValue("Math")
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult(source.replace("<caret>x;", "Math::<caret>x;"))
    }

    private fun withStandardModulesFetched(vararg files: Pair<String, String>, body: () -> Unit) {
        val original = BwslStdlibSources.cacheRoot
        val root = Files.createTempDirectory("bwsl_stdlib_names_").toFile()
        try {
            val modules = File(root, "master/modules").also { it.mkdirs() }
            for ((name, text) in files) File(modules, name).writeText(text)
            BwslStdlibSources.cacheRoot = root.toPath()
            body()
        } finally {
            BwslStdlibSources.cacheRoot = original
            root.deleteRecursively()
        }
    }

    fun testImportSuggestsTheStandardModulesAndOtherModulesOfTheFileButNotOnesAlreadyImported() {
        withStandardModulesFetched("math.bwsl" to "module Math {\n}\n", "noise.bwsl" to "module Noise {\n}\n", "color.bwsl" to "module Color {\n}\n") {
            myFixture.configureByText("imports.bwsl", "module Helper {\n}\nmodule M {\n    import Math\n    import <caret>\n}")

            val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

            assertTrue("Noise and Color are importable, got: $strings", strings.containsAll(listOf("Noise", "Color")))
            assertFalse("Math is imported already, got: $strings", strings.contains("Math"))
        }
    }

    fun testUsingSuggestsTheModulesAndAliasesTheFileImports() {
        myFixture.configureByText(
            "using.bwsl",
            "module M {\n    import Math as MM\n    import Noise\n    using <caret>\n}"
        )

        val strings = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()

        assertEquals(listOf("MM", "Noise"), strings.sorted())
    }
}
