package com.bwsl.plugin

import com.bwsl.plugin.completion.BwslcAstHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Find Usages for declarations, driven the way the IDE does it: the target is whatever is at the
 * caret (a declaration's own name, or a use that resolves to one), and the usages come from the
 * compiler's incoming reference edges in the cached ASTs.
 */
class BwslFindUsagesTest : BasePlatformTestCase() {

    /** Configures [sourceWithCaret], caches its real AST, and returns the offsets of all usages of the target at the caret. */
    private fun collectUsageOffsetsAtCaret(sourceWithCaret: String, modules: Map<String, String> = emptyMap()): List<Int> {
        myFixture.configureByText("usages.bwsl", sourceWithCaret)
        BwslcAstHelper.parseAndCache(myFixture.file.text, myFixture.file.virtualFile.path, modules)
        val target = myFixture.elementAtCaret
        return myFixture.findUsages(target).mapNotNull { it.file?.takeIf { f -> f == myFixture.file }?.let { _ -> it.navigationOffset } }.sorted()
    }

    /** The offsets of every occurrence of [needle] in the configured file, optionally the [nth] only. */
    private fun collectOffsetsOf(needle: String, startingAt: Int = 0): List<Int> {
        val text = myFixture.file.text
        val result = ArrayList<Int>()
        var i = text.indexOf(needle, startingAt)
        while (i >= 0) { result.add(i); i = text.indexOf(needle, i + 1) }
        return result
    }

    fun testFunctionUsagesFromItsDeclarationName() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                hel<caret>per :: (float x) -> float { return x; }
                first :: () -> float { return helper(1.0); }
                second :: () -> float { return helper(2.0) + helper(3.0); }
            }
            """.trimIndent()
        )

        assertEquals("the declaration is not a usage; the three calls are", collectOffsetsOf("helper("), usages)
    }

    fun testFunctionUsagesFromACall() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                helper :: (float x) -> float { return x; }
                first :: () -> float { return hel<caret>per(1.0); }
                second :: () -> float { return helper(2.0); }
            }
            """.trimIndent()
        )

        assertEquals("both calls; the declaration is not a usage", collectOffsetsOf("helper("), usages)
    }

    fun testSameNamedFunctionsInDifferentModulesHaveSeparateUsages() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module A {
                sc<caret>ale :: (float x) -> float { return x; }
                run :: () -> float { return scale(1.0); }
            }
            module B {
                scale :: (float x) -> float { return x * 2.0; }
                run :: () -> float { return scale(1.0); }
            }
            """.trimIndent()
        )

        // Only the call inside module A.
        assertEquals(listOf(collectOffsetsOf("scale(").first { it > myFixture.file.text.indexOf("run") }), usages)
    }

    fun testParameterUsagesStayInsideTheirFunction() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                first :: (float <caret>v) -> float {
                    float t = v;
                    t = t + v;
                    return t;
                }
                second :: (float v) -> float {
                    float t = v;
                    return t + v;
                }
            }
            """.trimIndent()
        )

        val firstFunctionEnd = myFixture.file.text.indexOf("second")
        val uses = collectOffsetsOf("v").filter { it < firstFunctionEnd }
            .filter { myFixture.file.text[it + 1].let { c -> !c.isLetterOrDigit() } && myFixture.file.text[it - 1].let { c -> !c.isLetterOrDigit() } }
        assertEquals("every use of v in the first function, not its declaration", uses.drop(1), usages)
    }

    fun testLocalVariableUsagesIncludeReadsAndWrites() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                first :: (float v) -> float {
                    float <caret>t = v;
                    t = t + v;
                    return t;
                }
            }
            """.trimIndent()
        )

        val text = myFixture.file.text
        val declaration = text.indexOf("float t") + "float ".length
        val expected = Regex("\\bt\\b").findAll(text).map { it.range.first }.filter { it != declaration }.toList()
        assertEquals(3, expected.size)
        assertEquals(expected, usages)
    }

    fun testStructUsagesAreItsTypeUses() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                struct B<caret>ox {
                    float size;
                }
                make :: (Box seed) -> Box {
                    Box b;
                    b.size = seed.size;
                    return b;
                }
            }
            """.trimIndent()
        )

        val text = myFixture.file.text
        val declaration = text.indexOf("struct Box") + "struct ".length
        val expected = collectOffsetsOf("Box").filter { it != declaration }
        assertEquals("parameter type, return type and the local's declared type", 3, expected.size)
        assertEquals(expected, usages)
    }

    fun testStructFieldUsagesAreItsMemberAccesses() {
        val usages = collectUsageOffsetsAtCaret(
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
            """.trimIndent()
        )

        val text = myFixture.file.text
        val declaration = text.indexOf("float size") + "float ".length
        val expected = collectOffsetsOf("size").filter { it != declaration }
        assertEquals("b.size and seed.size", 2, expected.size)
        assertEquals(expected, usages)
    }

    fun testConstUsagesIncludeUsesTheParserFoldedIntoLiterals() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                const float <caret>K = 2.0;
                f :: () -> float { return K * K; }
            }
            """.trimIndent()
        )

        val declaration = myFixture.file.text.indexOf("float K") + "float ".length
        val expected = collectOffsetsOf("K").filter { it != declaration }
        assertEquals("both uses, which the parser folded into literals", 2, expected.size)
        assertEquals(expected, usages)
    }

    fun testAttributeUsagesAreItsUseListNameAndMemberAccesses() {
        val usages = collectUsageOffsetsAtCaret(
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
            """.trimIndent()
        )

        val declaration = myFixture.file.text.indexOf("position: float4")
        assertEquals(collectOffsetsOf("position").filter { it != declaration }, usages)
    }

    fun testFragmentOutputUsagesAreItsAssignments() {
        val usages = collectUsageOffsetsAtCaret(
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
            """.trimIndent()
        )

        val declaration = myFixture.file.text.indexOf("result: float4")
        assertEquals(collectOffsetsOf("result").filter { it != declaration }, usages)
    }

    fun testStageValueUsagesAreEveryAssignmentAndReadOfIt() {
        val usages = collectUsageOffsetsAtCaret(
            """
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
                        output.<caret>uv = float2(0.0);
                        output.other = float2(1.0);
                    }
                    fragment {
                        float2 t = input.uv;
                        output.result = float4(t.x, input.uv.y, input.other.x, 1.0);
                    }
                }
            }
            """.trimIndent()
        )

        assertEquals(
            "the vertex assignment and both fragment reads, and not the other stage value",
            collectOffsetsOf("uv"),
            usages
        )
    }

    fun testUsagesOfAnImportedFunctionAreFoundFromACallIntoItsFile() {
        // The target is in Common.bwsl, which has no AST of its own cached: it is identified through
        // this file's AST, where it is an imported declaration.
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                import Common

                run :: () -> float { return Common::hel<caret>per(1.0) + Common::helper(2.0); }
            }
            """.trimIndent(),
            mapOf("Common" to "module Common {\n    helper :: (float a) -> float { return a; }\n}\n")
        )

        assertEquals(collectOffsetsOf("helper("), usages)
    }

    fun testUsagesOfAnImportedModuleAreItsImportAndQualifiers() {
        val usages = collectUsageOffsetsAtCaret(
            """
            module M {
                import Common

                run :: () -> float { return Comm<caret>on::helper(1.0) + Common::helper(2.0); }
            }
            """.trimIndent(),
            mapOf("Common" to "module Common {\n    helper :: (float a) -> float { return a; }\n}\n")
        )

        assertEquals("the import and both qualifiers", collectOffsetsOf("Common"), usages)
    }

    fun testOnlyDeclarationNamesCanBeFoundUsagesFor() {
        myFixture.configureByText(
            "usages_provider.bwsl",
            """
            module M {
                helper :: (float x) -> float { return x; }
                run :: () -> float { return helper(1.0); }
            }
            """.trimIndent()
        )
        BwslcAstHelper.parseAndCache(myFixture.file.text, myFixture.file.virtualFile.path)
        val provider = BwslFindUsagesProvider()
        val text = myFixture.file.text

        val declaration = myFixture.file.findElementAt(text.indexOf("helper ::"))!!
        val call = myFixture.file.findElementAt(text.indexOf("helper(1.0)"))!!
        val keyword = myFixture.file.findElementAt(text.indexOf("return x"))!!

        assertTrue(provider.canFindUsagesFor(declaration))
        assertFalse("a use is not a declaration", provider.canFindUsagesFor(call))
        assertFalse("a keyword is not a declaration", provider.canFindUsagesFor(keyword))
        assertEquals("function", provider.getType(declaration))
        assertEquals("helper", provider.getDescriptiveName(declaration))
    }

    fun testNothingIsFoundWithoutACachedAst() {
        myFixture.configureByText(
            "usages_no_ast.bwsl",
            "module M { hel<caret>per :: (float x) -> float { return x; } run :: () -> float { return helper(1.0); } }"
        )

        val provider = BwslFindUsagesProvider()
        val declaration = myFixture.file.findElementAt(myFixture.caretOffset)!!

        assertFalse("no AST means no known declarations and no fallback to scanning text", provider.canFindUsagesFor(declaration))
    }
}
