package com.bwsl.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * What the plugin links to, checked against the official documentation site itself. These need the network:
 * when the site cannot be reached they are reported as skipped (not passed, not failed).
 */
class BwslDocumentationSiteTest {

    private fun readPage(path: String): String? = fetchTextFromWeb("$DOCS_BASE_URL/$path")

    @Test
    fun testThePluginsIntrinsicNamesAreTheSitesNames() {
        val listing = readPage("intrinsics")
        assumeTrue(listing != null, "the documentation site could not be reached")

        val site = Regex("""href="/docs/intrinsics/(\w+)"""").findAll(listing!!).map { it.groupValues[1] }.toSet()
        assertTrue(site.size > 100, "the listing was read, got ${site.size} names")

        val documentedHere = BwslIntrinsics.NAMES - BwslIntrinsicDocs.UNDOCUMENTED + "discard"
        assertEquals(emptySet<String>(), site - documentedHere, "pages the site has and the plugin does not know")
        assertEquals(emptySet<String>(), documentedHere - site, "names the plugin takes to have a page and the site does not list")
    }

    @Test
    fun testEveryPageTheKeywordsLeadToExists() {
        assumeTrue(readPage("intrinsics") != null, "the documentation site could not be reached")

        val missing = collectKeywordPages().sorted().filter { readPage(it) == null }

        assertEquals(emptyList<String>(), missing, "keyword pages that do not exist")
    }
}
