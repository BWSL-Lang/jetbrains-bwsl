package com.bwsl.plugin

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** What a compiler download fetches: the asset for this machine, from the latest release or the dev build. */
class BwslCompilerDownloaderTest : BasePlatformTestCase() {

    fun testTheAssetNameIsBuiltFromTheOperatingSystemAndArchitecture() {
        assertEquals("bwslc-windows-x64", computeAssetPrefix("Windows 11", "amd64"))
        assertEquals("bwslc-linux-x64", computeAssetPrefix("Linux", "amd64"))
        assertEquals("bwslc-linux-arm64", computeAssetPrefix("Linux", "aarch64"))
        assertEquals("bwslc-macos-arm64", computeAssetPrefix("Mac OS X", "aarch64"))
    }

    fun testTheDevBuildHasAStableUrlPerAsset() {
        val base = "https://github.com/BWSL-Lang/BWSL/releases/download/dev"

        assertEquals("$base/bwslc-windows-x64.exe", BwslCompilerDownloader.buildDevAssetUrl("bwslc-windows-x64"))
        assertEquals("$base/bwslc-linux-x64", BwslCompilerDownloader.buildDevAssetUrl("bwslc-linux-x64"))
        assertEquals("$base/bwslc-macos-arm64", BwslCompilerDownloader.buildDevAssetUrl("bwslc-macos-arm64"))
    }

    fun testAChannelIsFoundByItsNameInAnyCase() {
        assertEquals(BwslCompilerChannel.DEV, BwslCompilerChannel.findByName("dev"))
        assertEquals(BwslCompilerChannel.RELEASE, BwslCompilerChannel.findByName("RELEASE"))
        assertNull(BwslCompilerChannel.findByName("nightly"))
    }

    fun testAnUnknownStoredChannelFallsBackToTheLatestRelease() {
        val settings = BwslSettings()
        assertEquals(BwslCompilerChannel.RELEASE, settings.compilerChannel)

        settings.loadState(BwslSettings.State(compilerChannel = "something-old"))
        assertEquals(BwslCompilerChannel.RELEASE, settings.compilerChannel)

        settings.compilerChannel = BwslCompilerChannel.DEV
        assertEquals("DEV", settings.state.compilerChannel)
    }
}
