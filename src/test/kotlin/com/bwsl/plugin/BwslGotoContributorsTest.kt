package com.bwsl.plugin

import com.intellij.navigation.NavigationItem

/** Go to Class and Go to Symbol (and so Search Everywhere): the project's declarations, from the compiler's AST. */
class BwslGotoContributorsTest : BwslAstFixtureTestCase() {

    private val source = """
        module Shapes {
            const float K = 2.0;
            struct Circle {
                float radius;
                area :: () -> float { return radius; }
            }
            scale :: (float x) -> float {
                const float local = 1.0;
                return x * K * local;
            }
        }
    """.trimIndent()

    private fun describe(entries: List<BwslSymbolEntry>) = entries.map { "${it.kind.label} ${it.container}.${it.name}".replace(" .", " ") }.sorted()

    fun testDeclarationsOfEveryKindAreListedWithTheirContainer() {
        configureAndCache(source)

        val entries = collectProjectSymbols(project)

        assertEquals(
            listOf("constant Shapes.K", "function Shapes.scale", "method Circle.area", "module Shapes", "struct Shapes.Circle"),
            describe(entries)
        )
    }

    fun testEntriesPointAtTheNameInTheFile() {
        val text = configureAndCache(source)

        for (entry in collectProjectSymbols(project)) {
            assertEquals(entry.name, text.substring(entry.offset, entry.offset + entry.name.length))
        }
    }

    fun testGoToClassHoldsTypesAndGoToSymbolTheRest() {
        configureAndCache(source)

        assertEquals(setOf("Shapes", "Circle"), BwslGotoClassContributor().getNames(project, false).toSet())
        assertEquals(setOf("K", "scale", "area"), BwslGotoSymbolContributor().getNames(project, false).toSet())
    }

    fun testAnItemShowsWhatItIsAndWhereAndOpensTheFile() {
        configureAndCache(source)

        val item: NavigationItem = BwslGotoSymbolContributor().getItemsByName("scale", "scale", project, false).single()

        assertEquals("scale", item.presentation!!.presentableText)
        assertEquals("Shapes (test.bwsl) - function", item.presentation!!.locationString)
        assertTrue(item.canNavigate())
    }

    fun testNothingIsListedForAFileEditedSinceItWasCompiled() {
        configureAndCache(source)
        myFixture.type("\n")

        assertTrue(collectProjectSymbols(project).isEmpty())
    }
}
