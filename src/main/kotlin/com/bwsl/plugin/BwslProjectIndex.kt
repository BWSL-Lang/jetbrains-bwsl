package com.bwsl.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable

private val log = logger<BwslProjectIndex>()

private const val MAX_PARALLEL_COMPILES = 4

/**
 * Whether the cached AST for [file] was built from exactly the saved text now on disk: the file and
 * every file it imported from. [filesByKey] maps [normalizePathKey] to the project's known files; a
 * file outside it is looked up on disk.
 */
internal fun hasCurrentAst(file: VirtualFile, filesByKey: Map<String, VirtualFile>): Boolean {
    val inputs = BwslAstCache.findCompiledInputs(file.path) ?: return false
    if (BwslAstCache.findRoot(file.path) == null) return false
    return inputs.all { (key, hash) ->
        val input = filesByKey[key] ?: LocalFileSystem.getInstance().findFileByPath(key)
        input != null && BwslAstCache.hashText(LoadTextUtil.loadText(input).toString()) == hash
    }
}

/**
 * Whether bwslc already failed to produce an AST for [file] from exactly what it could read now, so
 * compiling it again would give the same answer.
 */
internal fun isKnownUncompilable(file: VirtualFile, modulePaths: List<String>): Boolean {
    val recorded = BwslAstCache.findUncompilableInputs(file.path) ?: return false
    return recorded == snapshotCandidateInputs(file, modulePaths)
}

/**
 * What is wrong with the compiler's view of [file] - the reason a rename starting from it cannot be
 * trusted - or null when its AST is current. A file with unsaved changes, one that changed since the
 * compiler checked it, one the compiler cannot compile, and one it has never checked are all unsafe:
 * each may hold a usage the compiler cannot report.
 */
internal fun checkCompilerViewOf(file: VirtualFile, filesByKey: Map<String, VirtualFile>, modulePaths: List<String>): String? {
    if (FileDocumentManager.getInstance().isFileModified(file)) {
        return "${file.name} has unsaved changes. Save it and let the compiler re-check it, then rename."
    }
    if (isKnownUncompilable(file, modulePaths)) {
        return "${file.name} does not compile, so the compiler cannot say where it uses the name. " +
            "Fix its errors or list it under \"exclude\" in $BWSL_PROJECT_CONFIG_FILE, then rename."
    }
    if (hasCurrentAst(file, filesByKey)) return null
    return if (BwslAstCache.findRoot(file.path) != null) {
        "${file.name} has changed since the compiler last checked it. Save it and let the compiler re-check it, then rename."
    } else {
        "${file.name} has not been compiled yet. Let the compiler check it, then rename."
    }
}

/**
 * The compiler's AST for every BWSL file in the project ([collectIndexedFiles]), kept current in the
 * background: compiled when the project opens and again whenever a BWSL file or `bwsl.json` changes.
 * bwslc takes one input per `-ast-json` run, so each file is its own compile.
 */
@Service(Service.Level.PROJECT)
class BwslProjectIndex(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Refreshes the index in the background after [delayMillis]; a request made meanwhile replaces this one. */
    fun scheduleRefresh(delayMillis: Int = 1000) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed) {
                object : Task.Backgroundable(project, "Checking BWSL files", true) {
                    override fun run(indicator: ProgressIndicator) {
                        refreshNow(indicator, includeStandardModules = true)
                    }
                }.queue()
            }
        }, delayMillis)
    }

    /**
     * Compiles every indexed file that has no current AST; returns how many it compiled. With
     * [includeStandardModules] the compiler's standard modules are compiled too (through a probe
     * module each), which gives completion their members (auto-import); a rename or a search does not
     * wait for them.
     */
    @Synchronized
    fun refreshNow(indicator: ProgressIndicator? = null, includeStandardModules: Boolean = false): Int {
        val compilerPath = resolveCompilerPath() ?: return 0
        val modulePaths = collectModulePaths(project)
        val stale = collectFilesToCompile(modulePaths) +
            if (includeStandardModules) collectStandardModuleFilesToCompile(modulePaths) else emptyList()
        if (stale.isEmpty()) return 0

        val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("BWSL project index", MAX_PARALLEL_COMPILES)
        val futures = stale.map { file -> executor.submit(Callable { compileProjectFile(compilerPath, file, modulePaths) }) }
        futures.forEachIndexed { done, future ->
            indicator?.checkCanceled()
            indicator?.fraction = done.toDouble() / futures.size
            future.get()
        }
        return stale.size
    }

    /** Whether any indexed file still has to be compiled, which needs a compiler to be configured. */
    fun hasFilesToCompile(): Boolean = resolveCompilerPath() != null && collectFilesToCompile().isNotEmpty()

    /**
     * Brings the index up to date before the caller relies on it, behind a progress dialog the user can
     * cancel; does nothing, and shows nothing, when the index is already current.
     */
    fun refreshWithProgress() {
        if (!hasFilesToCompile()) return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { refreshNow(ProgressManager.getInstance().progressIndicator) },
            "Checking BWSL files",
            true,
            project
        )
    }

    /** The files on the local disk (bwslc needs a real path) that have no current AST and no known failure. */
    internal fun collectFilesToCompile(modulePaths: List<String> = collectModulePaths(project)): List<VirtualFile> {
        val files = collectIndexedFiles(project).filter { it.fileSystem == LocalFileSystem.getInstance() }
        val filesByKey = files.associateBy { normalizePathKey(it.path) }
        return files.filter { !hasCurrentAst(it, filesByKey) && !isKnownUncompilable(it, modulePaths) }
    }

    /**
     * The probe modules (see [BwslStdlibSources.writeProbeFiles]) of the compiler's standard modules that
     * have no current AST and no known failure. Compiling one caches the standard module it imports.
     */
    internal fun collectStandardModuleFilesToCompile(modulePaths: List<String> = collectModulePaths(project)): List<VirtualFile> {
        val probes = BwslStdlibSources.writeProbeFiles().mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        val probesByKey = probes.associateBy { normalizePathKey(it.path) }
        return probes.filter { !hasCurrentAst(it, probesByKey) && !isKnownUncompilable(it, modulePaths) }
    }

    private fun compileProjectFile(compilerPath: String, file: VirtualFile, modulePaths: List<String>) {
        try {
            val candidateInputs = ReadAction.compute<Map<String, Int>, RuntimeException> { snapshotCandidateInputs(file, modulePaths) }
            compileAndCache(compilerPath, file.path, modulePaths, candidateInputs)
        } catch (e: Exception) {
            log.warn("bwslc -ast-json failed for ${file.path}", e)
        }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): BwslProjectIndex = project.getService(BwslProjectIndex::class.java)
    }
}

/** Starts compiling the project's BWSL files as soon as the project has opened. */
class BwslProjectIndexStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        BwslProjectIndex.getInstance(project).scheduleRefresh(0)
    }
}

/** Re-checks the project's BWSL files whenever a `.bwsl` file or `bwsl.json` is created, changed, moved or deleted. */
class BwslFileChangeListener : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (events.none { doesAffectIndex(it.path) }) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) BwslProjectIndex.getInstance(project).scheduleRefresh()
        }
    }

    private fun doesAffectIndex(path: String): Boolean =
        path.endsWith(".bwsl") || path.endsWith("/$BWSL_PROJECT_CONFIG_FILE")
}
