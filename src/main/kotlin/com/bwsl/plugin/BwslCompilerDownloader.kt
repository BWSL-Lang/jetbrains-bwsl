package com.bwsl.plugin

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private data class GitHubAsset(val name: String = "", val browser_download_url: String = "")
private data class GitHubRelease(val tag_name: String = "", val assets: List<GitHubAsset> = emptyList())

/**
 * Which bwslc to download: the newest release, or the newest development build of the compiler's
 * master branch. The dev build is one rolling GitHub release under the `dev` tag whose assets keep
 * their names (`bwslc-linux-x64`), so its URLs never change.
 */
enum class BwslCompilerChannel(val displayName: String) {
    RELEASE("Latest release"),
    DEV("Latest dev build");

    companion object {
        /** The channel a setting or a command-line argument names (`RELEASE`, `dev`), or null for anything else. */
        fun findByName(name: String): BwslCompilerChannel? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Downloads bwslc for the current OS/architecture from GitHub: the latest release or the latest dev build. */
object BwslCompilerDownloader {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/BWSL-Lang/BWSL/releases/latest"
    private const val DEV_DOWNLOAD_URL = "https://github.com/BWSL-Lang/BWSL/releases/download/dev"

    /** The tag of the newest release (`v0.9.0`), or null when GitHub cannot be reached or says something else. */
    fun findLatestReleaseTag(): String? = fetchTextFromGitHub(LATEST_RELEASE_URL)?.let { parseLatestReleaseTag(it) }

    /** Where a downloaded compiler is stored, so it can be reused across IDE sessions. */
    fun getInstallPath(): Path {
        val osName = System.getProperty("os.name").lowercase()
        val extension = if (osName.contains("win")) ".exe" else ""
        return Path.of(PathManager.getSystemPath(), "bwsl", "bwslc$extension")
    }

    /**
     * Downloads the latest bwslc of [channel] matching the current OS/architecture to [target],
     * overwriting any existing file there. Returns the installed path.
     */
    fun downloadLatest(
        channel: BwslCompilerChannel = BwslCompilerChannel.RELEASE,
        target: Path = getInstallPath()
    ): Path {
        val assetPrefix = computeAssetPrefix(System.getProperty("os.name"), System.getProperty("os.arch"))
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val url = when (channel) {
            BwslCompilerChannel.RELEASE -> findLatestReleaseAssetUrl(client, assetPrefix)
            BwslCompilerChannel.DEV -> buildDevAssetUrl(assetPrefix)
        }

        Files.createDirectories(target.parent)
        val tempFile = Files.createTempFile("bwslc_download_", "")
        try {
            val response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(tempFile))
            // A failed download would otherwise be installed as the compiler.
            if (response.statusCode() != 200) {
                error("Failed to download $url: HTTP ${response.statusCode()}")
            }
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tempFile)
        }
        target.toFile().setExecutable(true)
        return target
    }

    /** The download URL of the latest release's asset for [assetPrefix]; release asset names end in the version. */
    private fun findLatestReleaseAssetUrl(client: HttpClient, assetPrefix: String): String {
        val releaseRequestBuilder = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL))
        val token = System.getenv("GITHUB_TOKEN")
        if (!token.isNullOrBlank()) {
            releaseRequestBuilder.header("Authorization", "Bearer $token")
        }
        val releaseResponse = client.send(releaseRequestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (releaseResponse.statusCode() != 200) {
            error("Failed to fetch latest BWSL release: HTTP ${releaseResponse.statusCode()} - ${releaseResponse.body()}")
        }
        val release = Gson().fromJson(releaseResponse.body(), GitHubRelease::class.java)
        val asset = release.assets.firstOrNull { it.name.startsWith(assetPrefix) }
            ?: error("No BWSL release asset found matching '$assetPrefix-*' in latest release ${release.tag_name}")
        return asset.browser_download_url
    }

    /** The stable URL of the dev build's asset for [assetPrefix]: `.../releases/download/dev/bwslc-windows-x64.exe`. */
    internal fun buildDevAssetUrl(assetPrefix: String): String {
        val extension = if (assetPrefix.startsWith("bwslc-windows")) ".exe" else ""
        return "$DEV_DOWNLOAD_URL/$assetPrefix$extension"
    }
}

/** The start of every asset name for [osName] and [archName] (`os.name`, `os.arch`): `bwslc-<platform>-<arch>`. */
internal fun computeAssetPrefix(osName: String, archName: String): String {
    val os = osName.lowercase()
    val arch = archName.lowercase()
    val platform = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    return "bwslc-$platform-${if (arch == "aarch64" || arch == "arm64") "arm64" else "x64"}"
}

/**
 * CLI entry point used by the Gradle `fetchBwslCompiler` task: `main <targetPath> [release|dev]`, the
 * latest release by default. Does nothing when the target exists.
 */
fun main(args: Array<String>) {
    val target = Path.of(args[0])
    if (Files.exists(target)) return
    val channel = args.getOrNull(1)?.let { BwslCompilerChannel.findByName(it) ?: error("Unknown channel '$it': use release or dev") }
        ?: BwslCompilerChannel.RELEASE
    val installed = BwslCompilerDownloader.downloadLatest(channel, target)
    println("Downloaded the ${channel.displayName.lowercase()} of bwslc to $installed")
}
