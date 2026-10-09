package com.bwsl.plugin

/** The type of a resource (`item: Lib.Item`) and of a fragment output: the compiler names it `typeName`, not `dataType`. */
class BwslResourceTypeNavigationTest : BwslAstFixtureTestCase() {

    private val lib = mapOf("Lib" to "module Lib {\n    struct Item { float w; }\n}")

    private fun pipeline(resourceType: String) =
        "pipeline P {\n    import Lib\n    resources {\n        item: $resourceType\n    }\n    attributes { position: float4 }\n" +
            "    pass \"Main\" {\n        use attributes { position }\n        use resources { item }\n        outputs { c: float4 }\n" +
            "        vertex { output.pos = attributes.position; }\n        fragment { output.c = float4(resources.item.w); }\n    }\n}"

    private fun offsetOf(text: String, word: String, after: String) = text.indexOf(after) + after.indexOf(word) + 1

    private fun resolveAt(offset: Int): List<String> {
        val index = buildAstIndex(myFixture.file)!!
        return resolveSymbolAt(myFixture.file, index, offset).map { it.containingFile.name + ":" + it.text }
    }

    fun testTheStructNameInAResourceTypeNavigatesToTheStruct() {
        for (spelling in listOf("Lib.Item", "Lib::Item")) {
            val text = configureAndCache(pipeline(spelling), lib)

            assertEquals(spelling, listOf("Lib.bwsl:Item"), resolveAt(offsetOf(text, "Item", "item: $spelling")))
        }
    }

    fun testTheModuleNameInAResourceTypeNavigatesToTheModule() {
        val text = configureAndCache(pipeline("Lib.Item"), lib)

        assertEquals(listOf("Lib.bwsl:Lib"), resolveAt(offsetOf(text, "Lib", "item: Lib.Item")))
    }

    fun testTheTypeRangeIsWhatIsWrittenEvenThoughTheCompilerNamesItWithColons() {
        val text = configureAndCache(pipeline("Lib.Item"), lib)
        val index = buildAstIndex(myFixture.file)!!

        val resource = index.nodesById.getValue("RESOURCE_DECL:0")
        val range = index.findTypeRangeOf(resource)!!

        assertEquals("Lib::Item", resource.typeName)
        assertEquals("Lib.Item", text.substring(range.first, range.last + 1))
    }

    fun testAFragmentOutputsTypeHasARangeToo() {
        val text = configureAndCache(pipeline("Lib.Item"), lib)
        val index = buildAstIndex(myFixture.file)!!

        val output = index.nodesById.getValue("PASS:0/fragment-output:0")
        val range = index.findTypeRangeOf(output)!!

        assertEquals("float4", text.substring(range.first, range.last + 1))
    }

    fun testTheStructsUsagesIncludeTheResourceThatHasItsType() {
        configureAndCache(pipeline("Lib.Item"), lib)
        val index = buildAstIndex(myFixture.file)!!

        val usages = index.refsByTo.getValue("STRUCT_DECL:0").filter { it.role == "type" }.map { it.from }

        assertTrue("got: $usages", "RESOURCE_DECL:0" in usages)
    }
}
