package com.bwsl.plugin.completion

/**
 * Completion after a `.`: a struct's fields and methods, a vector's swizzles, an array's length, and
 * inside a struct's methods its own fields and methods. The type of the value before the dot is worked
 * out from the tokens and the cached bwslc AST.
 */
class BwslCompletionMembersTest : BwslCompletionScopeTestCase() {

    private fun module(body: String): String = """
        module M {
            struct Light {
                float3 color;
                float intensity;
                float[4] weights;

                scaled :: (float k) -> float {
                    float a = intensity * k;
                    return a;
                }
                twice :: () -> float { return intensity; }
            }

            makeLight :: () -> Light {
                Light made;
                return made;
            }

            f :: (float4 v, Light l) -> float {
                $body
                return 1.0;
            }
        }
    """.trimIndent()

    private val allSwizzlesOfAVector4 = setOf("x", "y", "z", "w", "r", "g", "b", "a", "xy", "xyz", "xyzw", "rg", "rgb", "rgba")

    fun testAStructValuesFieldsAndMethodsFollowTheDot() {
        assertCompletions(
            module("float x = l.<caret>intensity;"),
            present = setOf("color", "intensity", "weights", "scaled", "twice"),
            absent = setOf("f", "makeLight", "v", "return", "float", "x", "xy")
        )
    }

    fun testAVectorsSwizzlesFollowTheDot() {
        assertCompletions(
            module("float x = v.<caret>x;"),
            present = allSwizzlesOfAVector4,
            absent = setOf("xyzwx", "color", "return", "float")
        )
    }

    fun testAShorterVectorHasOnlyItsOwnComponents() {
        assertCompletions(
            module("float3 c = l.color;\n                float x = c.<caret>x;"),
            present = setOf("x", "y", "z", "r", "g", "b", "xy", "xyz", "rgb"),
            absent = setOf("w", "a", "xyzw", "rgba")
        )
    }

    fun testASwizzleInProgressIsExtendedWithTheComponentsThatCanFollow() {
        assertCompletions(
            module("float x = v.xy<caret>x;"),
            present = setOf("xy", "xyx", "xyy", "xyz", "xyw"),
            absent = setOf("xyr", "xyzwx", "r", "color")
        )
    }

    fun testTheTwoComponentFamiliesAreNotMixed() {
        assertCompletions(
            module("float x = v.rg<caret>x;"),
            present = setOf("rgr", "rgg", "rgb", "rga"),
            absent = setOf("rgx", "rgz", "rgw")
        )
    }

    fun testAFieldOfAStructGivesItsOwnType() {
        assertCompletions(
            module("float x = l.color.<caret>x;"),
            present = setOf("x", "y", "z", "xyz", "rgb"),
            absent = setOf("w", "intensity", "scaled")
        )
    }

    fun testTheResultOfACallIsTypedByTheFunctionsReturnType() {
        assertCompletions(
            module("float x = makeLight().<caret>intensity;"),
            present = setOf("color", "intensity", "scaled"),
            absent = setOf("x", "w")
        )
    }

    fun testAConstructorCallHasTheTypeItConstructs() {
        assertCompletions(
            module("float x = float4(1.0, 2.0, 3.0, 4.0).<caret>x;"),
            present = setOf("x", "w", "rgba")
        )
    }

    fun testAMethodsResultIsTypedByItsReturnTypeAndAScalarHasNoMembers() {
        assertCompletions(
            module("float x = l.scaled(2.0).<caret>x;"),
            absent = setOf("x", "y", "color", "intensity", "scaled")
        )
    }

    /** The `length` completions at the caret of [sourceWithCaret], as the type text each shows: the array's own member says `int`, the generic intrinsic says `intrinsic`. */
    private fun collectLengthTypeTexts(sourceWithCaret: String): List<String?> {
        myFixture.configureByText("length.bwsl", sourceWithCaret)
        BwslcAstHelper.parseAndCache(sourceWithCaret.replace("<caret>", ""), myFixture.file.virtualFile.path)
        return myFixture.completeBasic().orEmpty().filter { it.lookupString == "length" }
            .map { item -> com.intellij.codeInsight.lookup.LookupElementPresentation().also { item.renderElement(it) }.typeText }
    }

    fun testAnArrayLocalHasLengthAsAMember() {
        val source = module("Light[2] lights;\n                float x = lights.<caret>length();")

        val typeTexts = collectLengthTypeTexts(source)

        assertTrue("the array's own length, got: $typeTexts", typeTexts.contains("int"))
        assertCompletions(source, absent = setOf("color", "intensity"))
    }

    fun testTheElementsOfAnArrayLocalHaveTheirOwnMembersAndNoLength() {
        val source = module("Light[2] lights;\n                float x = lights[1].<caret>intensity;")
        assertCompletions(source, present = setOf("color", "intensity", "scaled"))

        val typeTexts = collectLengthTypeTexts(source)

        assertFalse("an element is not an array, got: $typeTexts", typeTexts.contains("int"))
    }

    fun testAnArrayFieldHasLengthAsAMember() {
        val typeTexts = collectLengthTypeTexts(module("float n = float(l.weights.<caret>length());"))

        assertTrue("got: $typeTexts", typeTexts.contains("int"))
    }

    fun testAMatrixIndexedOnceIsAColumnVector() {
        assertCompletions(
            module("mat4 m;\n                float x = m[1].<caret>x;"),
            present = setOf("x", "y", "z", "w", "rgba"),
            absent = setOf("color")
        )
    }

    fun testAStructFromAnImportedModuleHasItsMembers() {
        assertCompletions(
            """
            module M {
                import Other
                f :: (Other::Shape s) -> float {
                    float x = s.<caret>radius;
                    return x;
                }
            }
            """.trimIndent(),
            present = setOf("radius", "area"),
            absent = setOf("f", "return"),
            modules = mapOf("Other" to "module Other {\n    struct Shape {\n        float radius;\n        area :: () -> float { return radius; }\n    }\n}\n")
        )
    }

    fun testAnUnknownReceiverOffersNoMembersAndNoKeywordsOrTypes() {
        assertCompletions(
            module("float x = nowhere.<caret>x;"),
            present = setOf("normalize"),
            absent = setOf("color", "xy", "return", "float", "module", "struct")
        )
    }

    fun testKeywordsAndTypeNamesAreNeverOfferedAfterADot() {
        assertCompletions(
            module("float x = l.<caret>intensity;"),
            absent = setOf("return", "if", "for", "float3", "mat4", "struct", "import")
        )
    }

    fun testInsideAMethodTheStructsFieldsAndMethodsAreSuggestedWithoutAQualifier() {
        assertCompletions(
            """
            module M {
                struct Light {
                    float intensity;
                    float3 color;

                    scaled :: (float k) -> float {
                        float a = <caret>intensity * k;
                        return a;
                    }
                    twice :: () -> float { return intensity; }
                }
                other :: () -> float { return 1.0; }
            }
            """.trimIndent(),
            present = setOf("intensity", "color", "twice", "scaled", "k", "other")
        )
    }

    fun testOutsideTheMethodsTheFieldsAreNotSuggestedAsBareNames() {
        assertCompletions(
            module("float x = <caret>1.0;"),
            present = setOf("makeLight", "Light"),
            absent = setOf("intensity", "color", "twice")
        )
    }

    fun testSelfIsTheEnclosingStruct() {
        assertCompletions(
            """
            module M {
                struct Light {
                    float intensity;
                    float3 color;

                    scaled :: (float k) -> float {
                        float a = self.<caret>intensity * k;
                        return a;
                    }
                }
            }
            """.trimIndent(),
            present = setOf("intensity", "color", "scaled"),
            absent = setOf("k", "return")
        )
    }
}
