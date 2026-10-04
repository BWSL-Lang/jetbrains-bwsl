package com.bwsl.plugin

import com.google.gson.Gson
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = logger<BwslCompilerUpdates>()

private val BANNER_VERSION = Regex("Compiler v\\s*(\\S+)")
private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** A release newer than the compiler in use, as it is offered to the user. */
data class CompilerUpdateOffer(val currentVersion: String, val latestVersion: String)

/**
 * Notices a newer `bwslc` release and offers to install it. The compiler's version is what its
 * banner says (`bwslc -h`); a development build says `0.0.0-dev`, which is not comparable to a
 * release, so it is never nagged. The newest release is asked of GitHub at most once a day, in the
 * background, and a version the user skipped is not offered again.
 */
object BwslCompilerUpdates {

    /** The newest release's tag (`v0.9.0`), or null when it cannot be read. Replaced in tests. */
    @Volatile
    internal var findLatestTag: () -> String? = { BwslCompilerDownloader.findLatestReleaseTag() }

    /** The version a compiler reports, or null when it cannot be run. Replaced in tests. */
    @Volatile
    internal var readVersion: (String) -> String? = ::readCompilerVersion

    /** The offer to update, or null when the compiler is up to date, is a development build, or its version cannot be told. */
    fun findOffer(compilerPath: String, skippedVersion: String): CompilerUpdateOffer? {
        val current = readVersion(compilerPath) ?: return null
        val latest = findLatestTag() ?: return null
        return decideUpdate(current, latest, skippedVersion)
    }

    /** Whether a check is due: the last one was more than a day before [now]. */
    fun isCheckDue(lastCheckMillis: Long, now: Long): Boolean = now - lastCheckMillis >= DAY_MILLIS

    private val checkedThisSession = AtomicBoolean(false)

    /**
     * Checks in the background, once a session and at most once a day, and says so if a newer release
     * exists. Does nothing when checking is turned off or no compiler is configured.
     */
    fun checkInBackground(project: Project) {
        val settings = BwslSettings.getInstance()
        if (!settings.checkForCompilerUpdates || !isCheckDue(settings.lastCompilerUpdateCheck, System.currentTimeMillis())) return
        if (!checkedThisSession.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread { runCheck(project, announceNothing = false) }
    }

    /** The user asked for a check (Tools menu): says what was found, including that nothing is newer. */
    fun checkNow(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread { runCheck(project, announceNothing = true) }
    }

    private fun runCheck(project: Project, announceNothing: Boolean) {
        val compilerPath = resolveCompilerPath()
        if (compilerPath == null) {
            if (announceNothing) notify(project, "No compiler is configured. Set one under Settings → BWSL.", NotificationType.WARNING)
            return
        }
        val settings = BwslSettings.getInstance()
        // An explicit check also offers a version the user skipped.
        val offer = findOffer(compilerPath, if (announceNothing) "" else settings.skippedCompilerVersion)
        settings.lastCompilerUpdateCheck = System.currentTimeMillis()
        if (offer != null) {
            offerUpdate(project, offer)
        } else if (announceNothing) {
            val version = readVersion(compilerPath)
            notify(project, describeNoUpdate(version), NotificationType.INFORMATION)
        }
    }

    private fun offerUpdate(project: Project, offer: CompilerUpdateOffer) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("BWSL Compiler")
                .createNotification(
                    "A newer BWSL compiler is available",
                    "bwslc ${offer.latestVersion} is out; you are using ${offer.currentVersion}.",
                    NotificationType.INFORMATION
                )
                .addAction(NotificationAction.createSimpleExpiring("Update") { install(project, offer) })
                .addAction(NotificationAction.createSimpleExpiring("Skip this version") {
                    BwslSettings.getInstance().skippedCompilerVersion = offer.latestVersion
                })
                .notify(project)
        }
    }

    /** Downloads the latest release and makes it the configured compiler. */
    private fun install(project: Project, offer: CompilerUpdateOffer) {
        object : Task.Backgroundable(project, "Downloading BWSL compiler ${offer.latestVersion}", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val installed = BwslCompilerDownloader.downloadLatest()
                    BwslSettings.getInstance().compilerPath = installed.toString()
                    notify(project, "bwslc ${offer.latestVersion} is installed at $installed and is now the compiler.", NotificationType.INFORMATION)
                } catch (e: Exception) {
                    log.warn("Could not download bwslc ${offer.latestVersion}", e)
                    notify(project, "Could not download bwslc ${offer.latestVersion}: ${e.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance().getNotificationGroup("BWSL Compiler")
                .createNotification(content, type)
                .notify(project)
        }
    }
}

/** What to say when an explicit check finds nothing newer. */
internal fun describeNoUpdate(version: String?): String = when {
    version == null -> "The compiler's version could not be read, so it cannot be compared with the latest release."
    parseReleaseVersion(version) == null -> "This is a development build ($version) of the compiler; it is not compared with releases."
    else -> "bwslc $version is the latest release."
}

/** Asks the compiler for its version: `Compiler v <version>` in the banner `bwslc -h` prints. */
internal fun readCompilerVersion(compilerPath: String): String? =
    try {
        val process = ProcessBuilder(compilerPath, "-h").redirectErrorStream(true).start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            parseCompilerVersion(output.get())
        }
    } catch (e: Exception) {
        log.info("Could not read the version of $compilerPath: ${e.message}")
        null
    }

/** The version in a compiler banner (`» Compiler v 0.9.0`), or null if the text has none. */
internal fun parseCompilerVersion(bannerText: String): String? = BANNER_VERSION.find(bannerText)?.groupValues?.get(1)

/**
 * The numbers of a release version (`v0.9.0` or `0.9.0` -> [0, 9, 0]), or null for anything else: a
 * development build (`0.0.0-dev`), a pre-release, or text that is not a version.
 */
internal fun parseReleaseVersion(version: String): List<Int>? {
    val numbers = version.removePrefix("v").split('.')
    if (numbers.isEmpty() || numbers.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    val parsed = numbers.map { it.toInt() }
    return parsed.takeIf { it.any { number -> number != 0 } }
}

/** Whether release [latest] is newer than [current]. False when either is not a comparable release version. */
internal fun isNewerVersion(current: String, latest: String): Boolean {
    val have = parseReleaseVersion(current) ?: return false
    val newest = parseReleaseVersion(latest) ?: return false
    for (index in 0 until maxOf(have.size, newest.size)) {
        val a = have.getOrElse(index) { 0 }
        val b = newest.getOrElse(index) { 0 }
        if (a != b) return b > a
    }
    return false
}

/** The offer for the compiler at [current] when release [latest] is newer and has not been skipped. */
internal fun decideUpdate(current: String, latest: String, skippedVersion: String): CompilerUpdateOffer? {
    if (!isNewerVersion(current, latest)) return null
    if (skippedVersion.isNotEmpty() && skippedVersion == latest) return null
    return CompilerUpdateOffer(current, latest)
}

/** Checks for a newer compiler when a project opens. */
class BwslCompilerUpdateStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        BwslCompilerUpdates.checkInBackground(project)
    }
}

/** The release `GitHub` calls the latest, as [Gson] reads it. */
internal data class LatestRelease(val tag_name: String = "")

/** Parses the answer of GitHub's `releases/latest` into its tag, or null if it is not one. */
internal fun parseLatestReleaseTag(json: String): String? =
    try {
        Gson().fromJson(json, LatestRelease::class.java)?.tag_name?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

/** **Tools → Check for BWSL Compiler Update**: asks now, and says what it found. */
class BwslCheckCompilerUpdateAction : com.intellij.openapi.actionSystem.AnAction() {

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT

    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        val project = e.project ?: return
        BwslCompilerUpdates.checkNow(project)
    }
}
