package com.bwsl.plugin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Navigation into the compiler's embedded standard modules. The compiler names them
 * `stdlib://modules/<File>.bwsl` and gives a GitHub `sourceUrl`; the plugin keeps local copies of
 * those files. The downloads here are served from the compiler repository's own `modules` directory
 * (found next to the compiler under test), so nothing touches the network; the tests are skipped
 * when that directory is not there.
 */
class BwslStdlibNavigationTest : BwslAstFixtureTestCase() {

    private lateinit var originalCacheRoot: Path
    private lateinit var originalFetchText: (String) -> String?
    private lateinit var temporaryCache: File
    private var repositoryModules: File? = null
    private val fetchedUrls = ArrayList<String>()

    /** Replaces what the fake downloader serves for a file, by file name. */
    private val overrides = HashMap<String, String>()

    override fun setUp() {
        super.setUp()
        originalCacheRoot = BwslStdlibSources.cacheRoot
        originalFetchText = BwslStdlibSources.fetchText
        temporaryCache = Files.createTempDirectory("bwsl_stdlib_test_").toFile()
        repositoryModules = System.getProperty("bwslc.path")
            ?.let { File(it).parentFile?.parentFile?.resolve("modules") }?.takeIf { it.isDirectory }
        BwslStdlibSources.cacheRoot = temporaryCache.toPath()
        BwslStdlibSources.forgetSession()
        BwslStdlibSources.fetchText = { url -> serve(url) }
    }

    override fun tearDown() {
        try {
            BwslStdlibSources.cacheRoot = originalCacheRoot
            BwslStdlibSources.fetchText = originalFetchText
            BwslStdlibSources.forgetSession()
            temporaryCache.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        } finally {
            super.tearDown()
        }
    }

    /** What GitHub would answer for [url]: a file's text, or a directory's listing. */
    private fun serve(url: String): String? {
        fetchedUrls += url
        val modules = repositoryModules ?: return null
        if (url.startsWith("https://api.github.com/")) {
            val names = modules.listFiles { f -> f.extension == "bwsl" }.orEmpty().map { it.name }.sorted()
            return names.joinToString(",", "[", "]") { "{\"name\":\"$it\",\"type\":\"file\"}" }
        }
        val name = url.substringAfterLast('/')
        return overrides[name] ?: modules.resolve(name).takeIf { it.isFile }?.readText()?.replace("\r\n", "\n")
    }

    private val source = "module UsesMath {\n    import Math\n    f :: (float x) -> float {\n        return Math::<caret>PI * x + Math::inverse_lerp(0.0, 1.0, x);\n    }\n}"

    private fun downloadWhatTheAstNames() {
        val urls = collectStringFields(BwslAstCache.findRawRoot(myFixture.file.virtualFile.path)!!, "sourceUrl")
        assertTrue("the AST names the standard module's source URL", urls.isNotEmpty())
        BwslStdlibSources.download(urls)
    }

    private fun resolveAt(needle: String): com.intellij.psi.PsiElement? {
        val offset = myFixture.file.text.indexOf(needle)
        return myFixture.file.findReferenceAt(offset + 1)?.resolve()
    }

    fun testAConstantInAStandardModuleNavigatesToItsCopiedSource() {
        if (repositoryModules == null) return
        configureAndCache(source)
        downloadWhatTheAstNames()

        val target = resolveAt("PI")

        assertNotNull("PI resolves once the source has been fetched", target)
        assertEquals("math.bwsl", target!!.containingFile.name)
        assertEquals("PI", target.text)
        assertTrue(target.containingFile.virtualFile.path.replace('\\', '/').startsWith(temporaryCache.path.replace('\\', '/')))
    }

    fun testAFunctionInAStandardModuleNavigatesToItsDeclaration() {
        if (repositoryModules == null) return
        configureAndCache(source)
        downloadWhatTheAstNames()

        val target = resolveAt("inverse_lerp")

        assertNotNull(target)
        assertEquals("inverse_lerp", target!!.text)
        assertEquals("math.bwsl", target.containingFile.name)
    }

    fun testNothingIsResolvedUntilTheSourceHasBeenFetched() {
        if (repositoryModules == null) return
        configureAndCache(source)

        assertNull("no copy yet, so no guess", resolveAt("PI"))
    }

    fun testACopyThatNoLongerMatchesTheCompilerResolvesToNothing() {
        val modules = repositoryModules ?: return
        configureAndCache(source)
        // The copy has moved on from the compiler's build: an extra line shifts every declaration.
        overrides["math.bwsl"] = "// a line the compiler's version does not have\n" + modules.resolve("math.bwsl").readText().replace("\r\n", "\n")
        downloadWhatTheAstNames()

        assertNull("the text at that position is not PI, so it is not trusted", resolveAt("PI"))
        assertNotNull("a name is still found where it really is", BwslStdlibSources.findCopyOf(
            collectStringFields(BwslAstCache.findRawRoot(myFixture.file.virtualFile.path)!!, "sourceUrl").first()
        ))
    }

    fun testTheWholeStandardLibraryDirectoryIsFetchedOnceAndItsModulesAreKnown() {
        if (repositoryModules == null) return
        configureAndCache(source)

        downloadWhatTheAstNames()
        val afterFirst = fetchedUrls.size
        downloadWhatTheAstNames()

        val names = BwslStdlibSources.collectModuleNames()
        assertTrue("Math and other standard modules are known, got: $names", "Math" in names && names.size > 3)
        assertEquals("the second download in a session asks for nothing again", afterFirst, fetchedUrls.size)
    }

    fun testCopiesAreReadOnlyAndKnownByTheirStdlibName() {
        if (repositoryModules == null) return
        configureAndCache(source)
        downloadWhatTheAstNames()
        val target = resolveAt("PI")!!
        val path = target.containingFile.virtualFile.path

        assertFalse("a copy is read-only", File(path).canWrite())
        assertEquals("stdlib://modules/math.bwsl", BwslStdlibSources.findSourceKeyOf(path))
        assertTrue(BwslStdlibSources.isCopy(path))
        assertFalse(BwslStdlibSources.isCopy(myFixture.file.virtualFile.path))
    }

    fun testAStandardDeclarationCannotBeRenamed() {
        if (repositoryModules == null) return
        configureAndCache(source)
        downloadWhatTheAstNames()
        val target = resolveAt("PI")!!

        assertFalse(BwslRenameProcessor().canProcessElement(target))
    }

    fun testUsagesOfAStandardDeclarationAreFoundInTheProject() {
        if (repositoryModules == null) return
        configureAndCache(source)
        downloadWhatTheAstNames()
        val target = resolveAt("PI")!!

        val usages = myFixture.findUsages(target).filter { it.file == myFixture.file }

        assertEquals(1, usages.size)
        assertEquals(myFixture.file.text.indexOf("PI"), usages.single().navigationOffset)
    }
}
