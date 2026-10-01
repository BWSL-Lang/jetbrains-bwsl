package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Exercises [resolveSymbolAt] directly against real bwslc output: plain identifier reads, the
 * name-vs-type split for a caret on a declaration, parameters, struct fields, consts, stage
 * interfaces and fragment outputs, declarations in imported modules and submodules, and
 * `builtin:*` targets resolving to nothing.
 */
class BwslAstResolverTest : BasePlatformTestCase() {

    private fun buildIndex(source: String, modules: Map<String, String> = emptyMap()): BwslAstIndex {
        val root = BwslcAstHelper.parse(source, modules)
        val raw = BwslcAstHelper.parseRaw(source, modules)
        return BwslAstIndex(root, raw, source)
    }

    private val commonModule =
        "module Common {\n" +
            "    struct Box {\n" +
            "        float size;\n" +
            "    }\n" +
            "\n" +
            "    helper :: () -> float {\n" +
            "        return 1.0;\n" +
            "    }\n" +
            "}\n"

    /**
     * Configures a file importing [commonModule]. The module is NOT added to the project: navigation
     * has to find it through the `sourceFile` bwslc reports, not by looking for a file by name.
     */
    private fun configureImporting(body: String): BwslAstIndex {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    import Common\n" +
                "\n" +
                "    run :: () -> float {\n" +
                body +
                "    }\n" +
                "}\n"
        )
        return buildIndex(myFixture.file.text, mapOf("Common" to commonModule))
    }

    fun testVariableUsageResolvesToDeclarationName() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normalized = float2(1.0, 2.0);\n" +
                "        return normali<caret>zed;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("normalized", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testDeclarationsOwnNameHasNoReference() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: () -> float2 {\n" +
                "        float2 normali<caret>zed = float2(1.0, 2.0);\n" +
                "        return normalized;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        assertTrue(
            "A caret on a VARIABLE_DECL's own name must not resolve anywhere",
            resolveSymbolAt(myFixture.file, index, myFixture.caretOffset).isEmpty()
        )
    }

    fun testDeclaredTypeResolvesToStructDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    struct Foo {\n" +
                "        method :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    f1 :: () -> void {\n" +
                "        F<caret>oo x;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("Foo", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testParameterUsageResolvesToParameterDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    rotate :: (float2 pos) -> float2 {\n" +
                "        return po<caret>s;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("pos", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testQualifiedStructFieldUsageResolvesToFieldDeclaration() {
        // Field access must be receiver-qualified ("c.radius") to appear in the reference index at
        // all - an *unqualified* self-reference to a struct's own field from inside one of its own
        // methods (e.g. `return radius;`) produces no reference edge whatsoever, so there is
        // nothing to resolve.
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    struct Foo {\n" +
                "        float radius;\n" +
                "        area :: () -> float { return 1.0; }\n" +
                "    }\n" +
                "    f :: () -> void {\n" +
                "        Foo c;\n" +
                "        c.rad<caret>ius = 2.0;\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("radius", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.caretOffset)
    }

    fun testFragmentInputResolvesToVertexOutputAssignment() {
        myFixture.configureByText(
            "test.bwsl",
            "pipeline P {\n" +
                "    pass \"Main\" {\n" +
                "        vertex {\n" +
                "            output.uv = float2(0, 0);\n" +
                "        }\n" +
                "        fragment {\n" +
                "            output.color = float4(input.u<caret>v, 0.0, 1.0);\n" +
                "        }\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("uv", resolved[0].text)
        val expectedOffset = myFixture.file.text.indexOf("output.uv") + "output.".length
        assertEquals(expectedOffset, resolved[0].textOffset)
    }

    private val fragmentOutputSource =
        "pipeline P {\n" +
            "    attributes {\n" +
            "        position: float4\n" +
            "        color: float4\n" +
            "    }\n" +
            "    pass \"Main\" {\n" +
            "        use attributes { position, color }\n" +
            "        outputs {\n" +
            "            color: float4\n" +
            "            result: float4 @location(3)\n" +
            "        }\n" +
            "        vertex {\n" +
            "            output.pos = attributes.position;\n" +
            "        }\n" +
            "        fragment {\n" +
            "            output.result = float4(1.0, 0.0, 0.0, 1.0);\n" +
            "            output.color = float4(0.0);\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

    fun testFragmentOutputResolvesToItsEntryInTheOutputsBlock() {
        myFixture.configureByText("test.bwsl", fragmentOutputSource.replace("output.result", "output.res<caret>ult"))
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("result", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("result: float4"), resolved[0].textOffset)
    }

    fun testFragmentOutputSharingAnAttributesNameStillResolvesToTheOutputsEntry() {
        myFixture.configureByText("test.bwsl", fragmentOutputSource.replace("output.color", "output.co<caret>lor"))
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        // Not the attribute declaration and not the `use attributes { color }` name.
        assertEquals(myFixture.file.text.indexOf("color: float4", myFixture.file.text.indexOf("outputs")), resolved[0].textOffset)
    }

    fun testBuiltinIntrinsicCallResolvesToNothing() {
        myFixture.configureByText(
            "test.bwsl",
            "module M {\n" +
                "    f1 :: (float angle) -> float { return co<caret>s(angle); }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        assertTrue(
            "A call to a builtin intrinsic has no source declaration to navigate to",
            resolveSymbolAt(myFixture.file, index, myFixture.caretOffset).isEmpty()
        )
    }

    fun testReceiverBasedCallResolvesToMethodDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    struct testStruct {\n" +
                "        test :: () -> float {\n" +
                "            return 1.0;\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test3 :: () -> void {\n" +
                "        LengthMethodTest::testStruct s1;\n" +
                "        s1.te<caret>st();\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("test", resolved[0].text)
    }

    fun testQualifiedCallResolvesToModuleLevelFunction() {
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    test :: (float[5] values) -> float {\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test2 :: () -> float {\n" +
                "        float[5] values;\n" +
                "        return LengthMethodTest::tes<caret>t(values);\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("test", resolved[0].text)
        assertTrue(resolved[0].textOffset < myFixture.file.text.indexOf("test2"))
    }

    fun testModuleQualifierOnFunctionCallResolvesToModuleDeclaration() {
        // The module name in a qualified call like "LengthMethodTest::test(...)" is the FUNCTION_CALL's
        // "qualifier": a real IDENTIFIER node with its own [qualifier] edge to the module.
        myFixture.configureByText(
            "test.bwsl",
            "module LengthMethodTest {\n" +
                "    test :: (float[5] values) -> float {\n" +
                "        return 1.0;\n" +
                "    }\n" +
                "}\n" +
                "module LengthTest2 {\n" +
                "    test2 :: () -> float {\n" +
                "        float[5] values;\n" +
                "        return Length<caret>MethodTest::test(values);\n" +
                "    }\n" +
                "}"
        )
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("LengthMethodTest", resolved[0].text)
    }

    private val declaredTypeSource = """
        module LengthMethodTest {
            struct testStruct {
                test :: () -> float { return 1.0; }
            }
        }
        module LengthTest2 {
            test3 :: () -> void {
                LengthMethodTest::testStruct s1;
            }
        }
    """.trimIndent()

    fun testModuleQualifierOnVariableDeclTypeResolvesToTheModule() {
        // A declared type "Mod::Type" has a positioned type-qualifier node with its own [qualifier]
        // edge to the module, the same as the qualifier of a call.
        myFixture.configureByText("test.bwsl", declaredTypeSource.replace("LengthMethodTest::testStruct", "Length<caret>MethodTest::testStruct"))
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("LengthMethodTest", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("LengthMethodTest"), resolved[0].textOffset)
    }

    fun testTypeNameOfAQualifiedVariableDeclTypeResolvesToTheStruct() {
        myFixture.configureByText("test.bwsl", declaredTypeSource.replace("LengthMethodTest::testStruct", "LengthMethodTest::test<caret>Struct"))
        val index = buildIndex(myFixture.file.text)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("testStruct", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("testStruct"), resolved[0].textOffset)
    }

    fun testCaretOnADeclarationsOwnNameDoesNotFollowItsTypeEdge() {
        // A parameter, a local variable and a function declared with a struct type all have a
        // `type`/`return-type` edge describing what they declare. A caret on the *name* must not
        // navigate to that type.
        myFixture.configureByText(
            "test.bwsl",
            """
            module M {
                struct Inner { float x; }
                make :: (Inner param) -> Inner {
                    Inner local = param;
                    return local;
                }
            }
            """.trimIndent()
        )
        val source = myFixture.file.text
        val index = buildIndex(source)

        val nameOffsets = mapOf(
            "param" to source.indexOf("param)") + 2,
            "local" to source.indexOf("local =") + 2,
            "make" to source.indexOf("make") + 1
        )
        for ((name, offset) in nameOffsets) {
            assertTrue("caret on the name \"$name\" should resolve to nothing", resolveSymbolAt(myFixture.file, index, offset).isEmpty())
        }
    }

    fun testUseAttributesNameResolvesToItsAttributeDeclaration() {
        // Each name in a use-block is a positioned used-attribute node with an [attribute] edge.
        myFixture.configureByText(
            "test.bwsl",
            "pipeline P {\n" +
                "    attributes {\n" +
                "        position: float4\n" +
                "        lastTransform: float4\n" +
                "        color: float4\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        use attributes { position, lastT<caret>ransform, color }\n" +
                "        vertex {\n" +
                "            output.pos = attributes.position;\n" +
                "        }\n" +
                "    }\n" +
                "}"
        )
        val source = myFixture.file.text
        val index = buildIndex(source)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("lastTransform", resolved[0].text)
        assertEquals(source.indexOf("lastTransform: float4"), resolved[0].textOffset)
    }

    fun testUseAttributesNameResolvesWithinItsOwnPipelineWhenNamesCollide() {
        // Two pipelines each declare "position" - the use-block in the second must resolve to the
        // second pipeline's attribute, not the first's (each use-attribute node has its own edge).
        myFixture.configureByText(
            "test.bwsl",
            "pipeline A {\n" +
                "    attributes {\n" +
                "        position: float4\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        use attributes { position }\n" +
                "        vertex {\n" +
                "            output.pos = attributes.position;\n" +
                "        }\n" +
                "    }\n" +
                "}\n" +
                "pipeline B {\n" +
                "    attributes {\n" +
                "        position: float4\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        use attributes { posi<caret>tion }\n" +
                "        vertex {\n" +
                "            output.pos = attributes.position;\n" +
                "        }\n" +
                "    }\n" +
                "}"
        )
        val source = myFixture.file.text
        val index = buildIndex(source)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals(source.lastIndexOf("position: float4"), resolved[0].textOffset)
    }

    fun testAttributesMemberAccessResolvesToAttributeDeclaration() {
        myFixture.configureByText(
            "test.bwsl",
            "pipeline P {\n" +
                "    attributes {\n" +
                "        position: float4\n" +
                "    }\n" +
                "    pass \"Main\" {\n" +
                "        use attributes { position }\n" +
                "        vertex {\n" +
                "            output.pos = attributes.posi<caret>tion;\n" +
                "        }\n" +
                "    }\n" +
                "}"
        )
        val source = myFixture.file.text
        val index = buildIndex(source)

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("position", resolved[0].text)
        assertEquals(source.indexOf("position: float4"), resolved[0].textOffset)
    }

    // --- Declarations in imported modules: navigate into the file the AST names as their sourceFile ---

    fun testCallIntoImportedModuleNavigatesToThatModulesFile() {
        val index = configureImporting(
            "        Common::Box b;\n" +
                "        return Common::hel<caret>per();\n"
        )

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("Common.bwsl", resolved[0].containingFile.name)
        assertEquals("helper", resolved[0].text)
        // Line 6 of Common.bwsl - not line 6 of the importing file, where the same coordinates
        // would land on `return Common::helper();`.
        assertEquals(commonModule.indexOf("helper ::"), resolved[0].textOffset)
    }

    fun testDeclaredTypeInImportedModuleNavigatesToStructInThatFile() {
        val index = configureImporting(
            "        Common::Bo<caret>x b;\n" +
                "        return Common::helper();\n"
        )

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("Common.bwsl", resolved[0].containingFile.name)
        assertEquals("Box", resolved[0].text)
        assertEquals(commonModule.indexOf("Box"), resolved[0].textOffset)
    }

    fun testModuleQualifierOnImportedCallNavigatesToTheModuleDeclarationInItsFile() {
        val index = configureImporting(
            "        Common::Box b;\n" +
                "        return Comm<caret>on::helper();\n"
        )

        val resolved = resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)

        assertEquals(1, resolved.size)
        assertEquals("Common.bwsl", resolved[0].containingFile.name)
        assertEquals("Common", resolved[0].text)
        assertEquals(commonModule.indexOf("Common"), resolved[0].textOffset)
    }

    fun testImportedNodesDoNotProduceFalseHitsInTheImportingFile() {
        // Common's `helper` is declared at line 6, cols 5-10 of Common.bwsl. In the importing file,
        // line 6 is `        return Common::helper();`, whose cols 9-10 are the start of `return`.
        // Indexed against the wrong file, `helper` would claim those columns. (resolveSymbolAt
        // alone can't show this - the mis-indexed function's only edge is a return-type to a
        // builtin, which resolves to nothing either way - so check the index directly.)
        val index = configureImporting(
            "        Common::Box b;\n" +
                "        r<caret>eturn Common::helper();\n"
        )

        assertNull(index.nodeAtOffset(myFixture.caretOffset))
    }

    // --- Constants, `using`, and members merged in from a submodule ---

    private val submoduleParent = """
        module SubmoduleParent {
            const float BASE = 2.0;

            baseValue :: (float x) -> float {
                return x + BASE;
            }
        }
    """.trimIndent() + "\n"

    private val submoduleExtra = """
        submodule SubmoduleParentExtra extends SubmoduleParent {
            const float EXTRA = 3.0;

            extraValue :: (float x) -> float {
                return baseValue(x) + EXTRA;
            }
        }
    """.trimIndent() + "\n"

    private val constsAndSubmoduleMain = """
        module M {
            import SubmoduleParent
            using SubmoduleParent

            const float SCALE = 2.0;

            run :: () -> float {
                float a = baseValue(1.0);
                float b = SubmoduleParent::extraValue(a);
                return a * SCALE + b + SubmoduleParent::BASE;
            }
        }
    """.trimIndent() + "\n"

    /** Configures [textWithCaret] as the compiled file (with both module files available) and resolves its caret. */
    private fun resolveInConstsAndSubmoduleMain(textWithCaret: String): List<com.intellij.psi.PsiElement> {
        myFixture.configureByText("test.bwsl", textWithCaret)
        val index = buildIndex(
            myFixture.file.text,
            mapOf("SubmoduleParent" to submoduleParent, "SubmoduleParentExtra" to submoduleExtra)
        )
        return resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)
    }

    fun testUseOfAFoldedConstResolvesToItsDeclaration() {
        // The parser folds `SCALE` into a literal; the compiler keeps a positioned `foldedFrom`
        // identifier with a `read` edge to the const's declaration.
        val resolved = resolveInConstsAndSubmoduleMain(constsAndSubmoduleMain.replace("a * SCALE", "a * SC<caret>ALE"))

        assertEquals(1, resolved.size)
        assertEquals("SCALE", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("SCALE ="), resolved[0].textOffset)
    }

    fun testQualifiedConstUseNavigatesIntoTheModulesFile() {
        val resolved = resolveInConstsAndSubmoduleMain(constsAndSubmoduleMain.replace("::BASE", "::BA<caret>SE"))

        assertEquals(1, resolved.size)
        assertEquals("SubmoduleParent.bwsl", resolved[0].containingFile.name)
        assertEquals(submoduleParent.indexOf("BASE ="), resolved[0].textOffset)
    }

    fun testFunctionMergedFromASubmoduleNavigatesToTheSubmodulesOwnFile() {
        // `extraValue` is merged into module SubmoduleParent but written in SubmoduleParentExtra.bwsl;
        // its position is relative to that file, which its own sourceFile names.
        val resolved = resolveInConstsAndSubmoduleMain(constsAndSubmoduleMain.replace("::extraValue", "::extra<caret>Value"))

        assertEquals(1, resolved.size)
        assertEquals("SubmoduleParentExtra.bwsl", resolved[0].containingFile.name)
        assertEquals("extraValue", resolved[0].text)
        assertEquals(submoduleExtra.indexOf("extraValue ::"), resolved[0].textOffset)
    }

    fun testUsingNameNavigatesToTheModuleDeclarationInItsFile() {
        val resolved = resolveInConstsAndSubmoduleMain(constsAndSubmoduleMain.replace("using SubmoduleParent", "using Submodule<caret>Parent"))

        assertEquals(1, resolved.size)
        assertEquals("SubmoduleParent.bwsl", resolved[0].containingFile.name)
        assertEquals(submoduleParent.indexOf("SubmoduleParent"), resolved[0].textOffset)
    }

    fun testImportNameNavigatesToTheModuleDeclarationInItsFile() {
        val resolved = resolveInConstsAndSubmoduleMain(constsAndSubmoduleMain.replace("import SubmoduleParent", "import Submodule<caret>Parent"))

        assertEquals(1, resolved.size)
        assertEquals("SubmoduleParent.bwsl", resolved[0].containingFile.name)
        assertEquals(submoduleParent.indexOf("SubmoduleParent"), resolved[0].textOffset)
    }

    // --- const declarations below module level: pipeline, pass, stage and function bodies ---

    private val constScopesSource = """
        pipeline P {
            attributes {
                position: float4
            }
            const float PIPELINE_SCALE = 4.0;
            pass "Main" {
                use attributes { position }
                const float PASS_SCALE = 2.0;
                outputs {
                    result: float4
                }
                vertex {
                    const float LOCAL_SCALE = 3.0;
                    output.pos = attributes.position * PIPELINE_SCALE * PASS_SCALE * LOCAL_SCALE;
                }
                fragment {
                    output.result = float4(PASS_SCALE);
                }
            }
        }
    """.trimIndent() + "\n"

    private fun resolveConstUse(textWithCaret: String): List<com.intellij.psi.PsiElement> {
        myFixture.configureByText("test.bwsl", textWithCaret)
        val index = buildIndex(myFixture.file.text)
        return resolveSymbolAt(myFixture.file, index, myFixture.caretOffset)
    }

    fun testPipelineLevelConstUseResolvesToItsDeclaration() {
        val resolved = resolveConstUse(constScopesSource.replace("* PIPELINE_SCALE", "* PIPELINE_<caret>SCALE"))

        assertEquals(1, resolved.size)
        assertEquals("PIPELINE_SCALE", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("PIPELINE_SCALE ="), resolved[0].textOffset)
    }

    fun testPassLevelConstUseInTheVertexStageResolvesToItsDeclaration() {
        val resolved = resolveConstUse(constScopesSource.replace("* PASS_SCALE *", "* PASS_<caret>SCALE *"))

        assertEquals(1, resolved.size)
        assertEquals("PASS_SCALE", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("PASS_SCALE ="), resolved[0].textOffset)
    }

    fun testPassLevelConstUseInTheFragmentStageResolvesToTheSameDeclaration() {
        // The pass's const is visible to both of its stages.
        val resolved = resolveConstUse(constScopesSource.replace("float4(PASS_SCALE)", "float4(PASS_<caret>SCALE)"))

        assertEquals(1, resolved.size)
        assertEquals(myFixture.file.text.indexOf("PASS_SCALE ="), resolved[0].textOffset)
    }

    fun testStageLocalConstUseResolvesToItsDeclaration() {
        val resolved = resolveConstUse(constScopesSource.replace("* LOCAL_SCALE;", "* LOCAL_<caret>SCALE;"))

        assertEquals(1, resolved.size)
        assertEquals("LOCAL_SCALE", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("LOCAL_SCALE ="), resolved[0].textOffset)
    }

    fun testConstDeclaredInAFunctionBodyResolvesToItsDeclaration() {
        val resolved = resolveConstUse(
            """
            module M {
                run :: () -> float {
                    const float K = 1.0;
                    return <caret>K * 2.0;
                }
            }
            """.trimIndent()
        )

        assertEquals(1, resolved.size)
        assertEquals("K", resolved[0].text)
        assertEquals(myFixture.file.text.indexOf("K ="), resolved[0].textOffset)
    }

    fun testSameNamedPassLevelConstsResolveWithinTheirOwnPass() {
        // The compiler scopes the folded use to the pass it's in; the second pass must not
        // navigate to the first pass's declaration.
        val resolved = resolveConstUse(
            """
            pipeline P {
                attributes {
                    position: float4
                }
                pass "A" {
                    use attributes { position }
                    const float K = 1.0;
                    vertex {
                        output.pos = attributes.position * K;
                    }
                }
                pass "B" {
                    use attributes { position }
                    const float K = 2.0;
                    vertex {
                        output.pos = attributes.position * <caret>K;
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(1, resolved.size)
        assertEquals("K", resolved[0].text)
        assertEquals(myFixture.file.text.lastIndexOf("K = 2.0"), resolved[0].textOffset)
    }

    fun testCaretOnAConstDeclarationsOwnNameResolvesToNothing() {
        // A const is a VARIABLE_DECL with a `type` edge; its own name must not follow that edge.
        val resolved = resolveConstUse(constScopesSource.replace("const float PASS_SCALE", "const float PASS_<caret>SCALE"))

        assertTrue(resolved.isEmpty())
    }

    // --- A stage output assigned more than once: one symbol, one `output` edge per assignment ---

    private val repeatedAssignmentsSource = """
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
                    output.uv = float2(0.0, 1.0);
                    output.uv = float2(1.0, 0.0);
                }
                fragment {
                    output.result = float4(input.uv, 0.0, 1.0);
                    output.result = float4(1.0);
                }
            }
        }
    """.trimIndent() + "\n"

    fun testEveryAssignmentOfAStageInterfaceValueResolvesToTheFirstOne() {
        val first = repeatedAssignmentsSource.indexOf("output.uv") + "output.".length
        val second = repeatedAssignmentsSource.lastIndexOf("output.uv") + "output.".length

        for ((label, text) in mapOf(
            "second assignment" to repeatedAssignmentsSource.replace("output.uv = float2(1.0", "output.u<caret>v = float2(1.0"),
            "input read" to repeatedAssignmentsSource.replace("input.uv", "input.u<caret>v")
        )) {
            myFixture.configureByText("test.bwsl", text)
            val resolved = resolveSymbolAt(myFixture.file, buildIndex(myFixture.file.text), myFixture.caretOffset)

            assertEquals("$label: expected one result", 1, resolved.size)
            assertEquals("$label: should resolve to the first assignment, not the second", first, resolved[0].textOffset)
            assertTrue(first < second)
        }
    }

    fun testEveryAssignmentOfAFragmentOutputResolvesToItsOutputsEntry() {
        for (marker in listOf("output.result = float4(input", "output.result = float4(1.0")) {
            myFixture.configureByText(
                "test.bwsl",
                repeatedAssignmentsSource.replace(marker, marker.replace("output.res", "output.re<caret>s"))
            )
            val resolved = resolveSymbolAt(myFixture.file, buildIndex(myFixture.file.text), myFixture.caretOffset)

            assertEquals(1, resolved.size)
            assertEquals(myFixture.file.text.indexOf("result: float4"), resolved[0].textOffset)
        }
    }
}
