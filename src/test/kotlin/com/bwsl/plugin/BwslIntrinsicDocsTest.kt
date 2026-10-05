package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

/** The intrinsic documentation pulled from the official site: parsing a page, keeping it, and the names it covers. */
class BwslIntrinsicDocsTest : BasePlatformTestCase() {

    private val page = """
        <html><head><title>fma | BWSL Docs</title><meta name="description" content="Computes a fused multiply-add."/></head>
        <body><article><header><h1>fma</h1></header><div class="prose docs-prose"><p>The <code>fma</code> function computes
        <code>a * b + c</code> as a <a href="/x">fused</a> multiply-add. <em>Fast.</em></p>
        <h2 id="signature">Signature</h2></div></article></body></html>
    """.trimIndent()

    private lateinit var originalFetch: (String) -> String?
    private lateinit var originalRoot: Path
    private val fetched = ArrayList<String>()

    override fun setUp() {
        super.setUp()
        originalFetch = BwslIntrinsicDocs.fetchText
        originalRoot = BwslIntrinsicDocs.cacheRoot
        BwslIntrinsicDocs.cacheRoot = Files.createTempDirectory("bwsl_intrinsic_docs_test_")
        BwslIntrinsicDocs.reset()
        BwslIntrinsicDocs.fetchText = { url -> fetched += url; page }
    }

    override fun tearDown() {
        try {
            BwslIntrinsicDocs.cacheRoot.toFile().deleteRecursively()
            BwslIntrinsicDocs.cacheRoot = originalRoot
            BwslIntrinsicDocs.fetchText = originalFetch
            BwslIntrinsicDocs.reset()
        } finally {
            super.tearDown()
        }
    }

    fun testASummaryAndTheFirstParagraphAreReadFromAPage() {
        val doc = parseDocPage(page)!!

        assertEquals("Computes a fused multiply-add.", doc.summary)
        assertEquals("The <code>fma</code> function computes <code>a * b + c</code> as a fused multiply-add. <em>Fast.</em>", doc.introHtml)
    }

    fun testAPageWithoutADescriptionGivesNothing() {
        assertNull(parseDocPage("<html><body>nothing</body></html>"))
    }

    fun testAFetchedPageIsKeptAndRead() {
        val fetchedDoc = BwslIntrinsicDocs.fetchNow("fma")

        assertEquals(listOf("https://www.bwsl.dev/docs/intrinsics/fma"), fetched)
        assertEquals(fetchedDoc, BwslIntrinsicDocs.findDoc("fma"))
    }

    fun testALookupFetchesInTheBackgroundOnceAndAnswersNothingUntilThen() {
        assertNull(BwslIntrinsicDocs.findDoc("fma"))
        assertNull(BwslIntrinsicDocs.findDoc("fma"))
        // The fetch is on a pooled thread: wait for what it leaves behind.
        val deadline = System.currentTimeMillis() + 10_000
        while (BwslIntrinsicDocs.findDoc("fma") == null && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals("Computes a fused multiply-add.", BwslIntrinsicDocs.findDoc("fma")?.summary)
        assertEquals("asked once, not on every lookup", 1, fetched.size)
    }

    fun testAnIntrinsicWithoutAPageIsNeverAskedFor() {
        for (name in listOf("fmod", "barrier", "notAnIntrinsic")) assertNull(BwslIntrinsicDocs.findDoc(name))

        assertTrue(fetched.isEmpty())
        assertFalse(BwslIntrinsicDocs.isDocumented("storageBarrier"))
        assertTrue(BwslIntrinsicDocs.isDocumented("discard"))
        assertTrue(BwslIntrinsicDocs.isDocumented("fma"))
    }

    // Reads the site's own listing: skipped, and reported as skipped, when the site cannot be reached.
    fun testThePluginsNamesAreTheSitesNames() {
        val listing = fetchTextFromWeb(BwslIntrinsicDocs.BASE_URL)
        Assume.assumeTrue("the documentation site could not be reached", listing != null)

        val site = Regex("""href="/docs/intrinsics/(\w+)"""").findAll(listing!!).map { it.groupValues[1] }.toSet()
        assertTrue("the listing was read, got ${site.size} names", site.size > 100)

        val documentedHere = BwslIntrinsics.NAMES - BwslIntrinsicDocs.UNDOCUMENTED + "discard"
        assertEquals("pages the site has and the plugin does not know", emptySet<String>(), site - documentedHere)
        assertEquals("names the plugin takes to have a page and the site does not list", emptySet<String>(), documentedHere - site)
    }
}
