package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Noticing a newer bwslc release: reading the compiler's version, comparing it, and what is offered. */
class BwslCompilerUpdatesTest : BasePlatformTestCase() {

    private lateinit var originalFindLatestTag: () -> String?
    private lateinit var originalReadVersion: (String) -> String?

    override fun setUp() {
        super.setUp()
        originalFindLatestTag = BwslCompilerUpdates.findLatestTag
        originalReadVersion = BwslCompilerUpdates.readVersion
    }

    override fun tearDown() {
        try {
            BwslCompilerUpdates.findLatestTag = originalFindLatestTag
            BwslCompilerUpdates.readVersion = originalReadVersion
        } finally {
            super.tearDown()
        }
    }

    fun testTheVersionIsReadFromTheCompilersBanner() {
        val banner = "    │ » Brawl Shading Language            │\n    │ » Compiler v 0.9.0                  │\n    │ » Made by someone                   │"

        assertEquals("0.9.0", parseCompilerVersion(banner))
        assertEquals("1.2.3", parseCompilerVersion("Compiler v1.2.3\n"))
        assertNull(parseCompilerVersion("no version here"))
    }

    fun testTheRealCompilerReportsAVersion() {
        val path = System.getProperty("bwslc.path") ?: error("bwslc.path is not set")

        val version = readCompilerVersion(path)

        assertNotNull("bwslc -h prints its version", version)
        assertTrue("a version, got: $version", version!!.matches(Regex("\\d+\\.\\d+\\.\\d+.*")))
    }

    fun testACompilerThatCannotBeRunHasNoVersion() {
        assertNull(readCompilerVersion("C:/no/such/bwslc.exe"))
    }

    fun testNewerReleasesAreRecognisedByTheirNumbersNotTheirText() {
        assertTrue(isNewerVersion("0.8.0", "v0.9.0"))
        assertTrue(isNewerVersion("v0.9.0", "0.9.1"))
        assertTrue(isNewerVersion("0.9.9", "0.10.0"))
        assertTrue(isNewerVersion("0.9", "0.9.1"))
        assertFalse(isNewerVersion("0.9.0", "v0.9.0"))
        assertFalse(isNewerVersion("0.10.0", "0.9.9"))
        assertFalse(isNewerVersion("1.0.0", "0.9.0"))
    }

    fun testADevelopmentBuildOrAnythingThatIsNotAVersionIsNeverOutOfDate() {
        assertFalse(isNewerVersion("0.0.0-dev", "v0.9.0"))
        assertFalse(isNewerVersion("0.0.0", "v0.9.0"))
        assertFalse(isNewerVersion("0.9.0-rc1", "v0.9.0"))
        assertFalse(isNewerVersion("0.8.0", "latest"))
        assertFalse(isNewerVersion("", ""))
    }

    fun testAnOfferIsMadeForANewerReleaseUnlessItWasSkipped() {
        assertEquals(CompilerUpdateOffer("0.8.0", "v0.9.0"), decideUpdate("0.8.0", "v0.9.0", skippedVersion = ""))
        assertNull("the user skipped exactly this version", decideUpdate("0.8.0", "v0.9.0", skippedVersion = "v0.9.0"))
        assertEquals(
            "an even newer release is offered again",
            CompilerUpdateOffer("0.8.0", "v0.10.0"),
            decideUpdate("0.8.0", "v0.10.0", skippedVersion = "v0.9.0")
        )
        assertNull("nothing is newer", decideUpdate("0.9.0", "v0.9.0", skippedVersion = ""))
    }

    fun testTheOfferComesFromTheCompilersVersionAndTheLatestTag() {
        BwslCompilerUpdates.readVersion = { "0.8.0" }
        BwslCompilerUpdates.findLatestTag = { "v0.9.0" }

        assertEquals(CompilerUpdateOffer("0.8.0", "v0.9.0"), BwslCompilerUpdates.findOffer("bwslc", ""))
    }

    fun testNoOfferWhenEitherVersionCannotBeFound() {
        BwslCompilerUpdates.readVersion = { null }
        BwslCompilerUpdates.findLatestTag = { "v0.9.0" }
        assertNull(BwslCompilerUpdates.findOffer("bwslc", ""))

        BwslCompilerUpdates.readVersion = { "0.8.0" }
        BwslCompilerUpdates.findLatestTag = { null }
        assertNull(BwslCompilerUpdates.findOffer("bwslc", ""))
    }

    fun testACheckIsDueOnceADayHasPassed() {
        val day = 24L * 60 * 60 * 1000

        assertTrue(BwslCompilerUpdates.isCheckDue(lastCheckMillis = 0, now = 1_000_000_000_000))
        assertFalse(BwslCompilerUpdates.isCheckDue(lastCheckMillis = 1_000, now = 1_000 + day - 1))
        assertTrue(BwslCompilerUpdates.isCheckDue(lastCheckMillis = 1_000, now = 1_000 + day))
    }

    fun testTheLatestReleaseTagIsReadFromGitHubsAnswer() {
        assertEquals("v0.9.0", parseLatestReleaseTag("""{"tag_name":"v0.9.0","assets":[]}"""))
        assertNull(parseLatestReleaseTag("""{"message":"Not Found"}"""))
        assertNull(parseLatestReleaseTag("not json"))
    }

    fun testWhatIsSaidWhenNothingIsNewer() {
        assertTrue(describeNoUpdate("0.9.0").contains("0.9.0 is the latest release"))
        assertTrue(describeNoUpdate("0.0.0-dev").contains("development build"))
        assertTrue(describeNoUpdate(null).contains("could not be read"))
    }

    fun testCheckingForUpdatesIsOnByDefault() {
        val state = BwslSettings.State()

        assertTrue(state.checkForCompilerUpdates)
        assertEquals(0L, state.lastCompilerUpdateCheck)
        assertEquals("", state.skippedCompilerVersion)
    }
}
