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

/** Downloads the latest bwslc release for the current OS/architecture from GitHub. */
object BwslCompilerDownloader {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/BWSL-Lang/BWSL/releases/latest"

    /** Where a downloaded compiler is stored, so it can be reused across IDE sessions. */
    fun installPath(): Path {
        val osName = System.getProperty("os.name").lowercase()
        val extension = if (osName.contains("win")) ".exe" else ""
        return Path.of(PathManager.getSystemPath(), "bwsl", "bwslc$extension")
    }

    /**
     * Downloads the latest bwslc release matching the current OS/architecture to [installPath],
     * overwriting any existing file there. Returns the installed path.
     */
    fun downloadLatest(target: Path = installPath()): Path {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val arch = if (archName == "aarch64" || archName == "arm64") "arm64" else "x64"
        val platform = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") -> "macos"
            else -> "linux"
        }
        val assetPrefix = "bwslc-$platform-$arch"

        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val releaseRequest = HttpRequest.newBuilder(URI.create(LATEST_RELEASE_URL)).build()
        val releaseJson = client.send(releaseRequest, HttpResponse.BodyHandlers.ofString()).body()
        val release = Gson().fromJson(releaseJson, GitHubRelease::class.java)

        val asset = release.assets.firstOrNull { it.name.startsWith(assetPrefix) }
            ?: error("No bwslc release asset found matching '$assetPrefix-*' in latest release ${release.tag_name}")

        Files.createDirectories(target.parent)
        val downloadRequest = HttpRequest.newBuilder(URI.create(asset.browser_download_url)).build()
        val tempFile = Files.createTempFile("bwslc_download_", "")
        client.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(tempFile))
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
        target.toFile().setExecutable(true)
        return target
    }
}

/** CLI entry point used by the Gradle `fetchBwslCompiler` task: `main <targetPath>`. */
fun main(args: Array<String>) {
    val target = Path.of(args[0])
    if (Files.exists(target)) return
    val installed = BwslCompilerDownloader.downloadLatest(target)
    println("Downloaded bwslc to $installed")
}
