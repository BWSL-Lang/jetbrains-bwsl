package com.bwsl.plugin

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val log = logger<BwslStdlibSources>()

private val BLOB_URL = Regex("^https://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$")
private val MODULE_DECLARATION = Regex("(?m)^\\s*(?:sub)?module\\s+(\\w+)")

/** What the AST's `sourceUrl` of an embedded standard-library file says: where it is in the compiler's repository. */
internal data class StdlibLocation(val owner: String, val repo: String, val ref: String, val path: String) {
    val rawUrl: String get() = "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
    val directoryListingUrl: String get() = "https://api.github.com/repos/$owner/$repo/contents/${path.substringBeforeLast('/')}?ref=$ref"

    /** A tag names one fixed version of the file; a branch (a development build's `master`) moves. */
    val isFixedVersion: Boolean get() = ref.matches(Regex("v?\\d+(\\.\\d+)*.*"))

    fun inSameDirectory(fileName: String): StdlibLocation = copy(path = path.substringBeforeLast('/') + "/" + fileName)
}

/**
 * The compiler embeds its standard modules (`Math`, `Color`, ...), so they have no file on disk: the
 * AST names them `stdlib://modules/math.bwsl` and gives a `sourceUrl` on GitHub, pinned to the
 * compiler's release tag (or `master` for a development build). This keeps a local, read-only copy of
 * those files, fetched in the background when an AST that uses one is cached, so navigation can open
 * them. A tag is fetched once; a branch once per session, since it may have moved.
 *
 * A copy is only trusted where it can be checked: the resolver confirms the text at a position is
 * the name the compiler gave it, so a copy that no longer matches the compiler resolves to nothing.
 */
object BwslStdlibSources {

    @Volatile
    internal var cacheRoot: Path = Path.of(PathManager.getSystemPath(), "bwsl", "stdlib")

    /** Reads the text at a URL, or null when it cannot be read. Replaced in tests. */
    @Volatile
    internal var fetchText: (String) -> String? = ::fetchTextFromGitHub

    private val listedThisSession = ConcurrentHashMap.newKeySet<String>()
    private val fetchedThisSession = ConcurrentHashMap.newKeySet<String>()

    /** Forgets what was fetched this session, so the next download asks again. For tests. */
    internal fun forgetSession() {
        listedThisSession.clear()
        fetchedThisSession.clear()
    }

    internal fun parseLocation(sourceUrl: String): StdlibLocation? =
        BLOB_URL.matchEntire(sourceUrl)?.destructured?.let { (owner, repo, ref, path) -> StdlibLocation(owner, repo, ref, path) }

    private fun fileOf(location: StdlibLocation): File = cacheRoot.resolve(location.ref).resolve(location.path).toFile()

    /** Whether [sourceFile] (`stdlib://modules/math.bwsl`) names an embedded standard-library file. */
    fun isStdlibKey(sourceFile: String): Boolean = sourceFile.startsWith("stdlib://")

    /** The local copy of the file at [sourceUrl], or null if it has not been fetched. */
    fun findCopyOf(sourceUrl: String): File? =
        parseLocation(sourceUrl)?.let { fileOf(it) }?.takeIf { it.isFile && it.length() > 0 }

    /** The `stdlib://` name of the local copy at [path], or null if [path] is not one of the copies. */
    fun findSourceKeyOf(path: String): String? {
        val relative = runCatching { cacheRoot.toAbsolutePath().normalize().relativize(Path.of(path).toAbsolutePath().normalize()) }
            .getOrNull() ?: return null
        if (relative.startsWith("..") || relative.nameCount < 3) return null
        return "stdlib://" + (1 until relative.nameCount).joinToString("/") { relative.getName(it).toString() }
    }

    /** Whether [path] is one of the local copies, which are not the user's to edit. */
    fun isCopy(path: String): Boolean = findSourceKeyOf(path) != null

    /** The names of the modules declared in the copies fetched so far. */
    fun collectModuleNames(): List<String> {
        val root = cacheRoot.toFile()
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension == "bwsl" }
            .flatMap { MODULE_DECLARATION.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .distinct().sorted().toList()
    }

    /** Fetches the files at [sourceUrls] and the rest of their directories, on a background thread. */
    fun downloadInBackground(sourceUrls: Collection<String>) {
        if (sourceUrls.none { parseLocation(it) != null }) return
        AppExecutorUtil.getAppExecutorService().execute {
            val written = download(sourceUrls)
            if (written.isNotEmpty()) LocalFileSystem.getInstance().refreshIoFiles(written, true, false, null)
        }
    }

    /**
     * Fetches the files at [sourceUrls], and every other `.bwsl` file in the same directory of the
     * repository (so the modules that are not imported yet can be offered too). Returns the files
     * written. Anything that cannot be fetched is skipped.
     */
    fun download(sourceUrls: Collection<String>): List<File> {
        val locations = sourceUrls.mapNotNull { parseLocation(it) }.distinct()
        val written = ArrayList<File>()
        for (location in locations.distinctBy { it.ref to it.path.substringBeforeLast('/') }) {
            written += downloadDirectoryOf(location)
        }
        for (location in locations) writeCopyIfNeeded(location)?.let { written += it }
        return written
    }

    private fun downloadDirectoryOf(location: StdlibLocation): List<File> {
        val key = "${location.owner}/${location.repo}@${location.ref}:${location.path.substringBeforeLast('/')}"
        if (!listedThisSession.add(key)) return emptyList()
        val listing = fetchText(location.directoryListingUrl) ?: return emptyList()
        val names = runCatching {
            JsonParser.parseString(listing).asJsonArray.mapNotNull { it.asJsonObject.get("name")?.asString }
        }.getOrElse {
            log.warn("The standard library listing for ${location.directoryListingUrl} was not understood", it)
            emptyList()
        }
        return names.filter { it.endsWith(".bwsl") }.mapNotNull { writeCopyIfNeeded(location.inSameDirectory(it)) }
    }

    private fun writeCopyIfNeeded(location: StdlibLocation): File? {
        val file = fileOf(location)
        val needsFetch = if (location.isFixedVersion) !file.isFile else fetchedThisSession.add(file.path) || !file.isFile
        if (!needsFetch) return null
        val text = fetchText(location.rawUrl) ?: return null
        file.parentFile.mkdirs()
        if (file.exists()) file.setWritable(true)
        file.writeText(text)
        file.setWritable(false)
        return file
    }
}

/** The text at [url] (GitHub, with `GITHUB_TOKEN` if set), or null when it cannot be read. */
private fun fetchTextFromGitHub(url: String): String? =
    try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "bwsl-jetbrains-plugin")
            .header("Accept", "application/vnd.github+json, text/plain")
        System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    } catch (e: Exception) {
        log.info("Could not fetch $url: ${e.message}")
        null
    }
