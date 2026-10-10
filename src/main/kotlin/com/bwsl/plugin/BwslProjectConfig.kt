package com.bwsl.plugin

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val log = logger<BwslProjectConfig>()

/** The name of the project's build configuration file, in the project root. */
const val BWSL_PROJECT_CONFIG_FILE = "bwsl.json"

/**
 * The project's build configuration, read from `bwsl.json` in the project root. Paths are relative
 * to the project root (an absolute path is used as it is).
 *
 * - [modulePaths]: directories handed to bwslc as `-modules`, in addition to the ones in the IDE settings.
 * - [exclude]: files and directories the project index leaves out (scratch or vendored shaders that
 *   are not meant to compile on their own).
 */
data class BwslProjectConfig(
    val modulePaths: List<String> = emptyList(),
    val exclude: List<String> = emptyList()
)

/** The configuration written in [json], or null if it is not valid JSON. */
fun parseProjectConfig(json: String): BwslProjectConfig? =
    try {
        Gson().fromJson(json, BwslProjectConfig::class.java)
    } catch (_: JsonSyntaxException) {
        null
    }

private val configCache = ConcurrentHashMap<String, Pair<Long, BwslProjectConfig>>()

/** The project's `bwsl.json`, or an empty configuration when there is none or it cannot be read. */
fun readProjectConfig(project: Project): BwslProjectConfig {
    val basePath = project.basePath ?: return BwslProjectConfig()
    val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$BWSL_PROJECT_CONFIG_FILE")
        ?: return BwslProjectConfig()
    configCache[file.path]?.takeIf { it.first == file.modificationStamp }?.let { return it.second }

    val config = parseProjectConfig(String(file.contentsToByteArray(), Charsets.UTF_8))
    if (config == null) log.warn("$BWSL_PROJECT_CONFIG_FILE in $basePath is not valid JSON; ignoring it")
    return (config ?: BwslProjectConfig()).also { configCache[file.path] = file.modificationStamp to it }
}

/** The directories bwslc searches for modules: the IDE settings' module paths, then the project's `bwsl.json` ones. */
fun collectModulePaths(project: Project): List<String> = collectModulePaths(project, BwslSettings.getInstance().modulePaths)

/** The IDE setting's [settingPaths] plus the module paths of the project's `bwsl.json`, without duplicates. */
fun collectModulePaths(project: Project, settingPaths: List<String>): List<String> {
    val basePath = project.basePath
    val fromConfig = if (basePath == null) emptyList()
    else readProjectConfig(project).modulePaths.map { File(basePath).resolve(it).path }
    return (settingPaths + fromConfig).distinctBy { normalizePathKey(it) }
}

/** [path] in a form that compares equal for the same file: forward slashes, and lower case on a case-insensitive file system. */
fun normalizePathKey(path: String): String {
    val slashed = path.replace('\\', '/')
    return if (SystemInfo.isFileSystemCaseSensitive) slashed else slashed.lowercase()
}

/** Whether [config] leaves [filePath] out of the index: it is, or is inside, one of the `exclude` entries. */
fun isExcludedBy(config: BwslProjectConfig, basePath: String, filePath: String): Boolean {
    val file = normalizePathKey(filePath)
    return config.exclude.any { entry ->
        val excluded = normalizePathKey(File(basePath).resolve(entry).path).trimEnd('/')
        file == excluded || file.startsWith("$excluded/")
    }
}

/**
 * Every BWSL file the compiler can be asked about: the project's own, and the files directly inside
 * each module path (bwslc looks for `<Module>.bwsl` in those directories only, not below them) -
 * minus the ones `bwsl.json` excludes. Find Usages and Rename search exactly these.
 */
internal fun collectIndexedFiles(project: Project): List<VirtualFile> = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
    val config = readProjectConfig(project)
    val basePath = project.basePath
    val files = LinkedHashMap<String, VirtualFile>()
    for (file in FileTypeIndex.getFiles(BwslFileType, GlobalSearchScope.projectScope(project))) {
        files[normalizePathKey(file.path)] = file
    }
    for (directory in collectModulePaths(project)) {
        val children = LocalFileSystem.getInstance().findFileByPath(directory)?.children ?: continue
        for (file in children) {
            if (!file.isDirectory && file.extension == "bwsl") files[normalizePathKey(file.path)] = file
        }
    }
    files.values.filter { basePath == null || !isExcludedBy(config, basePath, it.path) }
}
