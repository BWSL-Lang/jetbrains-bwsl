package com.bwsl.plugin

import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val log = logger<BwslIntrinsicDocs>()

/** What the documentation page of an intrinsic says: its one-line [summary] and the first paragraph, as [introHtml]. */
data class IntrinsicDoc(val summary: String, val introHtml: String)

/**
 * The official documentation of the intrinsics, `https://www.bwsl.dev/docs/intrinsics/<name>`. Its text
 * is read from the page itself and kept on disk (a week, then fetched again in the background), so the
 * hover shows what the documentation says without the plugin keeping its own copy of it.
 */
object BwslIntrinsicDocs {

    const val BASE_URL = "https://www.bwsl.dev/docs/intrinsics"
    private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * Intrinsics of the compiler's table that have no page on the site (checked against its listing by
     * `BwslIntrinsicDocsTest`). `discard` is the opposite: a keyword here, a page there, see [isDocumented].
     */
    internal val UNDOCUMENTED: Set<String> = setOf("fmod", "barrier", "memoryBarrier", "storageBarrier")

    internal var cacheRoot: Path = Path.of(PathManager.getSystemPath(), "bwsl", "intrinsic-docs")

    /** Reads a page; replaced by tests, which never reach the network. */
    internal var fetchText: (String) -> String? = ::fetchTextFromWeb

    private val requestedThisSession = ConcurrentHashMap.newKeySet<String>()
    private val gson = Gson()

    /** Forgets what was asked for this session, so the next lookup fetches again. For tests. */
    internal fun reset() = requestedThisSession.clear()

    fun buildPageUrl(name: String): String = "$BASE_URL/$name"

    /** Whether the site has a page for the intrinsic [name] (or for `discard`). */
    fun isDocumented(name: String): Boolean = (name in BwslIntrinsics.NAMES && name !in UNDOCUMENTED) || name == "discard"

    private fun fileOf(name: String): File = cacheRoot.resolve("$name.json").toFile()

    /**
     * The documentation of [name] as last fetched, or null when it has not been (yet). A missing or week-old
     * copy is fetched on a background thread, once per session, and is there for the next lookup.
     */
    fun findDoc(name: String): IntrinsicDoc? {
        if (!isDocumented(name)) return null
        val file = fileOf(name)
        val isFresh = file.isFile && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MILLIS
        if (!isFresh && requestedThisSession.add(name)) {
            AppExecutorUtil.getAppExecutorService().execute { fetchNow(name) }
        }
        return runCatching { gson.fromJson(file.readText(), IntrinsicDoc::class.java) }.getOrNull()
    }

    /** Fetches the page of [name], keeps what it says and returns it; null when the page cannot be read. */
    internal fun fetchNow(name: String): IntrinsicDoc? {
        val doc = fetchText(buildPageUrl(name))?.let { parseDocPage(it) } ?: return null
        runCatching {
            val file = fileOf(name)
            file.parentFile.mkdirs()
            file.writeText(gson.toJson(doc))
        }.onFailure { log.info("Could not keep the documentation of $name: ${it.message}") }
        return doc
    }
}

/**
 * The summary (the page's `description`) and the first paragraph of the text of an intrinsic's page.
 * Only inline code and emphasis are kept of the paragraph. Null when the page has no description.
 */
internal fun parseDocPage(html: String): IntrinsicDoc? {
    val summary = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return null
    val start = html.indexOf("""class="prose docs-prose">""")
    val intro = if (start < 0) "" else Regex("""^<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        .find(html.substring(start + """class="prose docs-prose">""".length))?.groupValues?.get(1).orEmpty()
    val kept = intro.replace(Regex("""<(?!/?(?:code|em|strong)\b)[^>]*>"""), "").replace(Regex("""\s+"""), " ").trim()
    return IntrinsicDoc(summary, kept)
}

/** The text at [url], or null when it cannot be read. */
internal fun fetchTextFromWeb(url: String): String? =
    try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "bwsl-jetbrains-plugin")
            .header("Accept", "text/html")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null
    } catch (e: Exception) {
        null
    }
